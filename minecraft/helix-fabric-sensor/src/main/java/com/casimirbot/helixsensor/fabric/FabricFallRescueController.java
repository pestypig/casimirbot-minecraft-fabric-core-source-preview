package com.casimirbot.helixsensor.fabric;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Tick-local, short-lived safety lease. Codex may arm or disarm the lease via
 * the governed Minecraft command lane, but the time-critical water placement
 * is deterministic connector code and never waits on another model turn.
 */
final class FabricFallRescueController {
    static final int MIN_SECONDS = 5;
    static final int MAX_SECONDS = 300;
    private static final int MAX_LANDING_SCAN_BLOCKS = 24;
    private static final double MIN_TRIGGER_HEIGHT = 2.5d;
    private static final double MAX_TRIGGER_HEIGHT = 10.0d;
    private static final long WATER_CLEANUP_TICKS = 100L;
    private static final long RECENT_EVENT_TICKS = 20L * 30L;
    private static final long RECENT_RESULT_TICKS = 20L * 60L * 10L;

    record Operation(boolean ok, String message) {}

    private record RecentResult(
        ResourceKey<Level> dimension,
        int triggerCount,
        String lastOutcome,
        BlockPos lastPosition,
        long lastEventTick,
        long recordedAtTick
    ) {}

    enum OfflineLeaseAction {
        RETAIN,
        RESTORE_WATER,
        EXPIRE
    }

    private static final class Lease {
        final UUID playerId;
        ResourceKey<Level> dimension;
        long expiresAtTick;
        int triggerCount;
        String lastOutcome = "armed_waiting";
        BlockPos lastPosition;
        long lastEventTick = -1L;
        BlockPos placedWater;
        BlockState replacedState;
        long cleanupAtTick;

        Lease(
            UUID playerId,
            ResourceKey<Level> dimension,
            long expiresAtTick
        ) {
            this.playerId = playerId;
            this.dimension = dimension;
            this.expiresAtTick = expiresAtTick;
        }
    }

    private static final Map<UUID, Lease> LEASES = new HashMap<>();
    private static final Map<UUID, RecentResult> RECENT_RESULTS = new HashMap<>();

    private FabricFallRescueController() {}

    static Operation arm(ServerPlayer player, int requestedSeconds) {
        ServerLevel level = (ServerLevel) player.level();
        if (level.dimensionType().ultraWarm()) {
            return new Operation(
                false,
                "Water fall rescue cannot be armed in an ultra-warm dimension."
            );
        }
        int seconds = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, requestedSeconds));
        Lease previous = LEASES.get(player.getUUID());
        if (previous != null) {
            ServerLevel previousLevel = level.getServer().getLevel(previous.dimension);
            if (previousLevel == null) {
                return new Operation(
                    false,
                    "The prior fall-rescue dimension is unavailable; the existing lease was retained for safe cleanup."
                );
            }
            restorePlacedWater(previousLevel, previous);
            LEASES.remove(player.getUUID());
        }
        Lease lease = new Lease(
            player.getUUID(),
            level.dimension(),
            level.getGameTime() + seconds * 20L
        );
        LEASES.put(player.getUUID(), lease);
        RECENT_RESULTS.remove(player.getUUID());
        return new Operation(
            true,
            "Armed deterministic water fall rescue for " +
            player.getGameProfile().getName() + " for " + seconds +
            " seconds in " + level.dimension().location() + "."
        );
    }

    static Operation disarm(ServerPlayer player) {
        Lease lease = LEASES.get(player.getUUID());
        if (lease == null) {
            return new Operation(false, "No water fall-rescue lease was active.");
        }
        ServerLevel leaseLevel = ((ServerLevel) player.level())
            .getServer()
            .getLevel(lease.dimension);
        if (leaseLevel == null) {
            return new Operation(
                false,
                "The fall-rescue dimension is unavailable; the lease remains armed for safe cleanup."
            );
        }
        restorePlacedWater(leaseLevel, lease);
        LEASES.remove(player.getUUID());
        return new Operation(
            true,
            "Disarmed water fall rescue for " +
            player.getGameProfile().getName() + "."
        );
    }

    static Operation status(ServerPlayer player) {
        Lease lease = LEASES.get(player.getUUID());
        if (lease == null) {
            RecentResult result = RECENT_RESULTS.get(player.getUUID());
            if (result == null) {
                return new Operation(false, "Water fall rescue is not armed.");
            }
            ServerLevel resultLevel = ((ServerLevel) player.level())
                .getServer()
                .getLevel(result.dimension());
            if (resultLevel == null) {
                return new Operation(
                    false,
                    "The recent fall-rescue result dimension is unavailable."
                );
            }
            long now = resultLevel.getGameTime();
            if (!recentResultAvailable(now, result.recordedAtTick())) {
                RECENT_RESULTS.remove(player.getUUID());
                return new Operation(false, "Water fall rescue is not armed.");
            }
            return new Operation(
                true,
                inactiveStatusMessage(
                    result.triggerCount(),
                    result.lastOutcome(),
                    result.lastPosition() == null
                        ? "none"
                        : coordinates(result.lastPosition()),
                    Math.max(0L, (now - result.recordedAtTick()) / 20L)
                )
            );
        }
        ServerLevel level = (ServerLevel) player.level();
        long remainingTicks = Math.max(0L, lease.expiresAtTick - level.getGameTime());
        String position = lease.lastPosition == null
            ? "none"
            : coordinates(lease.lastPosition);
        return new Operation(
            true,
            "Water fall rescue armed; remaining_seconds=" +
            ((remainingTicks + 19L) / 20L) +
            "; trigger_count=" + lease.triggerCount +
            "; last_outcome=" + lease.lastOutcome +
            "; last_position=" + position + "."
        );
    }

    static List<String> statusFlags(ServerPlayer player) {
        Lease lease = LEASES.get(player.getUUID());
        ServerLevel level = (ServerLevel) player.level();
        if (lease == null) {
            RecentResult result = RECENT_RESULTS.get(player.getUUID());
            if (
                result == null ||
                !recentResultAvailable(level.getGameTime(), result.recordedAtTick())
            ) {
                return List.of();
            }
            return result.triggerCount() > 0 &&
                result.lastEventTick() >= 0L &&
                level.getGameTime() - result.lastEventTick() <= RECENT_EVENT_TICKS
                ? List.of("fall_rescue_triggered")
                : List.of();
        }
        List<String> flags = new ArrayList<>();
        if (level.getGameTime() < lease.expiresAtTick) {
            flags.add("fall_rescue_armed");
        }
        if (
            lease.lastEventTick >= 0L &&
            level.getGameTime() - lease.lastEventTick <= RECENT_EVENT_TICKS
        ) {
            flags.add("fall_rescue_triggered");
        }
        return List.copyOf(flags);
    }

    static void tick(MinecraftServer server) {
        List<UUID> removals = new ArrayList<>();
        for (Lease lease : List.copyOf(LEASES.values())) {
            ServerLevel leaseLevel = server.getLevel(lease.dimension);
            if (leaseLevel == null) continue;
            ServerPlayer player = server.getPlayerList().getPlayer(lease.playerId);
            if (player == null) {
                long now = leaseLevel.getGameTime();
                OfflineLeaseAction offlineAction = offlineLeaseAction(
                    lease.placedWater != null,
                    now,
                    lease.cleanupAtTick,
                    lease.expiresAtTick
                );
                if (offlineAction == OfflineLeaseAction.EXPIRE) {
                    boolean waterWasPlaced = lease.placedWater != null;
                    restorePlacedWater(leaseLevel, lease);
                    settleExpiredOutcome(lease, waterWasPlaced, true);
                    rememberResult(lease, now);
                    removals.add(lease.playerId);
                } else if (
                    offlineAction == OfflineLeaseAction.RESTORE_WATER
                ) {
                    restorePlacedWater(leaseLevel, lease);
                    lease.lastOutcome = "rescue_completed_water_removed_offline";
                    lease.lastEventTick = now;
                }
                continue;
            }
            ServerLevel level = (ServerLevel) player.level();
            if (!lease.dimension.equals(level.dimension())) {
                ServerLevel priorLevel = server.getLevel(lease.dimension);
                if (priorLevel != null) restorePlacedWater(priorLevel, lease);
                lease.lastOutcome = "dimension_changed";
                lease.lastEventTick = level.getGameTime();
                rememberResult(lease, level.getGameTime());
                removals.add(lease.playerId);
                continue;
            }
            long now = level.getGameTime();
            if (lease.placedWater != null) {
                if (player.onGround() || now >= lease.cleanupAtTick) {
                    restorePlacedWater(level, lease);
                    lease.lastOutcome = "rescue_completed_water_removed";
                    lease.lastEventTick = now;
                }
            }
            if (now >= lease.expiresAtTick) {
                boolean waterWasPlaced = lease.placedWater != null;
                restorePlacedWater(level, lease);
                settleExpiredOutcome(lease, waterWasPlaced, false);
                rememberResult(lease, now);
                removals.add(lease.playerId);
                continue;
            }
            if (lease.placedWater == null) attemptRescue(level, player, lease);
        }
        removals.forEach(LEASES::remove);
        pruneRecentResults(server);
    }

    static OfflineLeaseAction offlineLeaseAction(
        boolean waterPlaced,
        long now,
        long cleanupAtTick,
        long expiresAtTick
    ) {
        if (now >= expiresAtTick) return OfflineLeaseAction.EXPIRE;
        if (waterPlaced && now >= cleanupAtTick) {
            return OfflineLeaseAction.RESTORE_WATER;
        }
        return OfflineLeaseAction.RETAIN;
    }

    static boolean recentResultAvailable(long now, long recordedAtTick) {
        return now >= recordedAtTick && now - recordedAtTick <= RECENT_RESULT_TICKS;
    }

    static String inactiveStatusMessage(
        int triggerCount,
        String lastOutcome,
        String lastPosition,
        long resultAgeSeconds
    ) {
        return "Water fall rescue inactive; remaining_seconds=0" +
            "; trigger_count=" + triggerCount +
            "; last_outcome=" + lastOutcome +
            "; last_position=" + lastPosition +
            "; result_age_seconds=" + resultAgeSeconds + ".";
    }

    private static void settleExpiredOutcome(
        Lease lease,
        boolean waterWasPlaced,
        boolean playerOffline
    ) {
        if (waterWasPlaced) {
            lease.lastOutcome = playerOffline
                ? "rescue_completed_water_removed_at_timeout_offline"
                : "rescue_completed_water_removed_at_timeout";
        } else if ("armed_waiting".equals(lease.lastOutcome)) {
            lease.lastOutcome = "expired_without_trigger";
        }
        if (lease.lastEventTick < 0L) lease.lastEventTick = lease.expiresAtTick;
    }

    private static void rememberResult(Lease lease, long now) {
        RECENT_RESULTS.put(
            lease.playerId,
            new RecentResult(
                lease.dimension,
                lease.triggerCount,
                lease.lastOutcome,
                lease.lastPosition,
                lease.lastEventTick,
                now
            )
        );
    }

    private static void pruneRecentResults(MinecraftServer server) {
        RECENT_RESULTS.entrySet().removeIf(entry -> {
            RecentResult result = entry.getValue();
            ServerLevel level = server.getLevel(result.dimension());
            return level == null ||
                !recentResultAvailable(level.getGameTime(), result.recordedAtTick());
        });
    }

    static void clear(MinecraftServer server) {
        for (Lease lease : List.copyOf(LEASES.values())) {
            ServerLevel level = server.getLevel(lease.dimension);
            if (level != null) restorePlacedWater(level, lease);
        }
        LEASES.clear();
        RECENT_RESULTS.clear();
    }

    static boolean shouldAttempt(
        double fallDistance,
        double verticalVelocity,
        boolean onGround,
        boolean flying,
        boolean gliding,
        boolean spectator,
        boolean creative
    ) {
        return !onGround &&
            !flying &&
            !gliding &&
            !spectator &&
            !creative &&
            fallDistance >= 3.0d &&
            verticalVelocity < -0.35d;
    }

    private static void attemptRescue(
        ServerLevel level,
        ServerPlayer player,
        Lease lease
    ) {
        Vec3 velocity = player.getDeltaMovement();
        if (
            !shouldAttempt(
                player.fallDistance,
                velocity.y,
                player.onGround(),
                player.getAbilities().flying,
                player.isFallFlying(),
                player.isSpectator(),
                player.isCreative()
            )
        ) {
            return;
        }
        int predictedX = (int) Math.floor(player.getX() + velocity.x * 2.0d);
        int predictedZ = (int) Math.floor(player.getZ() + velocity.z * 2.0d);
        int startY = player.blockPosition().getY() - 1;
        int minimumY = Math.max(
            level.getMinY(),
            startY - MAX_LANDING_SCAN_BLOCKS
        );
        BlockPos waterPosition = null;
        for (int y = startY; y >= minimumY; y--) {
            BlockPos floor = new BlockPos(predictedX, y, predictedZ);
            if (!level.getBlockState(floor).blocksMotion()) continue;
            BlockPos candidate = floor.above();
            double height = player.getY() - candidate.getY();
            if (height < MIN_TRIGGER_HEIGHT || height > MAX_TRIGGER_HEIGHT) {
                return;
            }
            waterPosition = candidate;
            break;
        }
        if (waterPosition == null) return;
        BlockState current = level.getBlockState(waterPosition);
        if (
            current.hasBlockEntity() ||
            !current.getFluidState().isEmpty() ||
            !(current.isAir() || current.canBeReplaced())
        ) {
            lease.lastOutcome = "landing_cell_not_replaceable";
            lease.lastPosition = waterPosition.immutable();
            lease.lastEventTick = level.getGameTime();
            return;
        }
        if (!level.setBlock(waterPosition, Blocks.WATER.defaultBlockState(), 3)) {
            lease.lastOutcome = "water_placement_rejected";
            lease.lastPosition = waterPosition.immutable();
            lease.lastEventTick = level.getGameTime();
            return;
        }
        lease.placedWater = waterPosition.immutable();
        lease.replacedState = current;
        lease.cleanupAtTick = level.getGameTime() + WATER_CLEANUP_TICKS;
        lease.triggerCount++;
        lease.lastOutcome = "water_placed";
        lease.lastPosition = waterPosition.immutable();
        lease.lastEventTick = level.getGameTime();
    }

    private static void restorePlacedWater(ServerLevel level, Lease lease) {
        if (lease.placedWater == null || lease.replacedState == null) return;
        if (level.getBlockState(lease.placedWater).is(Blocks.WATER)) {
            level.setBlock(lease.placedWater, lease.replacedState, 3);
        }
        lease.placedWater = null;
        lease.replacedState = null;
        lease.cleanupAtTick = 0L;
    }

    private static String coordinates(BlockPos position) {
        return position.getX() + "," + position.getY() + "," + position.getZ();
    }
}
