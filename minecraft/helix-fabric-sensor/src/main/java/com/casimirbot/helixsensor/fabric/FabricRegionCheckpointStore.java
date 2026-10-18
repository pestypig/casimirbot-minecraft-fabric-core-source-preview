package com.casimirbot.helixsensor.fabric;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Small, in-memory rollback checkpoints for bounded agent build operations.
 * Checkpoints never touch the host filesystem and deliberately skip block
 * entities during restore so container contents are not overwritten.
 */
final class FabricRegionCheckpointStore {
    static final int MAX_HORIZONTAL_RADIUS = 12;
    static final int MAX_VERTICAL_RADIUS = 8;
    static final int MAX_BOX_DISTANCE = 32;
    static final int MAX_BLOCKS = 16_384;
    private static final int MAX_CHECKPOINTS_PER_PLAYER = 4;
    private static final long TTL_TICKS = 20L * 60L * 15L;

    record Operation(boolean ok, String message) {}

    private record Checkpoint(
        UUID playerId,
        String name,
        ResourceKey<Level> dimension,
        BlockPos minimum,
        BlockPos maximum,
        Map<BlockPos, BlockState> states,
        int blockEntityPositions,
        long expiresAtTick
    ) {}

    private static final Map<String, Checkpoint> CHECKPOINTS = new HashMap<>();

    private FabricRegionCheckpointStore() {}

    static Operation capture(
        ServerPlayer player,
        String requestedName,
        int requestedHorizontalRadius,
        int requestedVerticalRadius
    ) {
        String name = normalizedName(requestedName);
        if (name == null) {
            return new Operation(
                false,
                "Checkpoint names must use 1-24 letters, numbers, underscores, or hyphens."
            );
        }
        int horizontalRadius = clamp(
            requestedHorizontalRadius,
            1,
            MAX_HORIZONTAL_RADIUS
        );
        int verticalRadius = clamp(
            requestedVerticalRadius,
            1,
            MAX_VERTICAL_RADIUS
        );
        int blockCount = volume(horizontalRadius, verticalRadius);
        if (blockCount > MAX_BLOCKS) {
            return new Operation(
                false,
                "Checkpoint region exceeds the bounded in-memory block limit."
            );
        }
        BlockPos center = player.blockPosition().immutable();
        BlockPos minimum = center.offset(
            -horizontalRadius,
            -verticalRadius,
            -horizontalRadius
        );
        BlockPos maximum = center.offset(
            horizontalRadius,
            verticalRadius,
            horizontalRadius
        );
        return captureRegion(player, name, minimum, maximum);
    }

    static Operation captureBox(
        ServerPlayer player,
        String requestedName,
        int firstX,
        int firstY,
        int firstZ,
        int secondX,
        int secondY,
        int secondZ
    ) {
        String name = normalizedName(requestedName);
        if (name == null) {
            return new Operation(
                false,
                "Checkpoint names must use 1-24 letters, numbers, underscores, or hyphens."
            );
        }
        BlockPos minimum = new BlockPos(
            Math.min(firstX, secondX),
            Math.min(firstY, secondY),
            Math.min(firstZ, secondZ)
        );
        BlockPos maximum = new BlockPos(
            Math.max(firstX, secondX),
            Math.max(firstY, secondY),
            Math.max(firstZ, secondZ)
        );
        ServerLevel level = (ServerLevel) player.level();
        if (
            minimum.getY() < level.getMinY() ||
            maximum.getY() >= level.getMaxY()
        ) {
            return new Operation(
                false,
                "Checkpoint box is outside the current dimension build height."
            );
        }
        BlockPos playerPosition = player.blockPosition();
        if (
            maximumAxisDistance(playerPosition, minimum) > MAX_BOX_DISTANCE ||
            maximumAxisDistance(playerPosition, maximum) > MAX_BOX_DISTANCE
        ) {
            return new Operation(
                false,
                "Checkpoint box must remain within " + MAX_BOX_DISTANCE +
                " blocks of the selected player."
            );
        }
        long blockCount = boxVolume(
            minimum.getX(),
            minimum.getY(),
            minimum.getZ(),
            maximum.getX(),
            maximum.getY(),
            maximum.getZ()
        );
        if (blockCount > MAX_BLOCKS) {
            return new Operation(
                false,
                "Checkpoint box exceeds the bounded in-memory block limit."
            );
        }
        return captureRegion(player, name, minimum, maximum);
    }

    private static Operation captureRegion(
        ServerPlayer player,
        String name,
        BlockPos minimum,
        BlockPos maximum
    ) {
        long existingForPlayer = CHECKPOINTS.values().stream()
            .filter(checkpoint -> checkpoint.playerId().equals(player.getUUID()))
            .filter(checkpoint -> !checkpoint.name().equals(name))
            .count();
        if (existingForPlayer >= MAX_CHECKPOINTS_PER_PLAYER) {
            return new Operation(
                false,
                "Discard or restore an existing checkpoint before capturing another."
            );
        }
        int blockCount = (int) boxVolume(
            minimum.getX(),
            minimum.getY(),
            minimum.getZ(),
            maximum.getX(),
            maximum.getY(),
            maximum.getZ()
        );
        if (blockCount > MAX_BLOCKS) {
            return new Operation(
                false,
                "Checkpoint region exceeds the bounded in-memory block limit."
            );
        }
        ServerLevel level = (ServerLevel) player.level();
        Map<BlockPos, BlockState> states = new LinkedHashMap<>(blockCount);
        int blockEntityPositions = 0;
        for (int x = minimum.getX();; x++) {
            for (int z = minimum.getZ();; z++) {
                for (int y = minimum.getY();; y++) {
                    BlockPos position = new BlockPos(x, y, z);
                    BlockState state = level.getBlockState(position);
                    states.put(position, state);
                    if (state.hasBlockEntity()) blockEntityPositions++;
                    if (y == maximum.getY()) break;
                }
                if (z == maximum.getZ()) break;
            }
            if (x == maximum.getX()) break;
        }
        CHECKPOINTS.put(
            key(player.getUUID(), name),
            new Checkpoint(
                player.getUUID(),
                name,
                level.dimension(),
                minimum.immutable(),
                maximum.immutable(),
                Map.copyOf(states),
                blockEntityPositions,
                level.getGameTime() + TTL_TICKS
            )
        );
        return new Operation(
            true,
            "Captured checkpoint '" + name + "' with " + blockCount +
            " blocks from " + coordinates(minimum) + " through " +
            coordinates(maximum) + "; " +
            blockEntityPositions +
            " block-entity positions will be skipped during restore."
        );
    }

    static Operation restore(ServerPlayer player, String requestedName) {
        String name = normalizedName(requestedName);
        if (name == null) return new Operation(false, "Checkpoint name is invalid.");
        String key = key(player.getUUID(), name);
        Checkpoint checkpoint = CHECKPOINTS.get(key);
        if (checkpoint == null) {
            return new Operation(false, "Checkpoint '" + name + "' is unavailable or expired.");
        }
        ServerLevel level = (ServerLevel) player.level();
        if (!checkpoint.dimension().equals(level.dimension())) {
            return new Operation(false, "Checkpoint belongs to a different Minecraft dimension.");
        }
        if (level.getGameTime() >= checkpoint.expiresAtTick()) {
            CHECKPOINTS.remove(key);
            return new Operation(false, "Checkpoint '" + name + "' expired before restore.");
        }
        int restored = 0;
        int unchanged = 0;
        int skippedBlockEntities = 0;
        for (Map.Entry<BlockPos, BlockState> entry : checkpoint.states().entrySet()) {
            BlockState current = level.getBlockState(entry.getKey());
            BlockState original = entry.getValue();
            if (current.hasBlockEntity() || original.hasBlockEntity()) {
                skippedBlockEntities++;
                continue;
            }
            if (current.equals(original)) {
                unchanged++;
                continue;
            }
            if (level.setBlock(entry.getKey(), original, 3)) restored++;
        }
        CHECKPOINTS.remove(key);
        return new Operation(
            true,
            "Restored checkpoint '" + name + "': " + restored +
            " blocks changed, " + unchanged + " already matched, " +
            skippedBlockEntities + " block-entity positions skipped."
        );
    }

    static Operation discard(ServerPlayer player, String requestedName) {
        String name = normalizedName(requestedName);
        if (name == null) return new Operation(false, "Checkpoint name is invalid.");
        boolean removed = CHECKPOINTS.remove(key(player.getUUID(), name)) != null;
        return new Operation(
            removed,
            removed
                ? "Discarded checkpoint '" + name + "'."
                : "Checkpoint '" + name + "' was not active."
        );
    }

    static Operation status(ServerPlayer player) {
        long count = CHECKPOINTS.values().stream()
            .filter(checkpoint -> checkpoint.playerId().equals(player.getUUID()))
            .count();
        return new Operation(
            true,
            "Active in-memory region checkpoints for " +
            player.getGameProfile().getName() + ": " + count + "."
        );
    }

    static void tick(ServerLevel level) {
        long now = level.getGameTime();
        CHECKPOINTS.entrySet().removeIf(entry ->
            entry.getValue().dimension().equals(level.dimension()) &&
            now >= entry.getValue().expiresAtTick()
        );
    }

    static void clear() {
        CHECKPOINTS.clear();
    }

    static int volume(int horizontalRadius, int verticalRadius) {
        int width = horizontalRadius * 2 + 1;
        int height = verticalRadius * 2 + 1;
        return width * width * height;
    }

    static long boxVolume(
        int firstX,
        int firstY,
        int firstZ,
        int secondX,
        int secondY,
        int secondZ
    ) {
        long width = Math.abs((long) secondX - firstX) + 1L;
        long height = Math.abs((long) secondY - firstY) + 1L;
        long depth = Math.abs((long) secondZ - firstZ) + 1L;
        try {
            return Math.multiplyExact(Math.multiplyExact(width, height), depth);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static String normalizedName(String value) {
        String name = value == null ? "" : value.trim().toLowerCase();
        return name.matches("[a-z0-9_-]{1,24}") ? name : null;
    }

    private static String key(UUID playerId, String name) {
        return playerId + ":" + name;
    }

    private static String coordinates(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }

    private static long maximumAxisDistance(BlockPos first, BlockPos second) {
        return Math.max(
            Math.max(
                Math.abs((long) first.getX() - second.getX()),
                Math.abs((long) first.getY() - second.getY())
            ),
            Math.abs((long) first.getZ() - second.getZ())
        );
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
