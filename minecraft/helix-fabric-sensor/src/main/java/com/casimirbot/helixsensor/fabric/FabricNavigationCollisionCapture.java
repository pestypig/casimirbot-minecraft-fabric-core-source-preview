package com.casimirbot.helixsensor.fabric;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.shapes.CollisionContext;

/** Bounded capture primitive; perception probes may explicitly request a small capture.
 * The admitted observation path binds source/producer identity and provenance.
 * This class neither loads chunks nor schedules work onto another thread.
 */
final class FabricNavigationCollisionCapture {
    static final int MAX_CELLS = 4096;
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final long MAX_NANOS = 20_000_000L;
    private static final Gson JSON = new Gson();

    record Identity(String dimension, UUID player, BlockPos origin, long tick, boolean available) {}
    record Capture(Map<String, Object> replay, int cellCount, int unknownCount,
                   int unsupportedCount, int payloadBytes, long elapsedNanos) {}
    interface View {
        boolean onServerThread();
        Identity identity();
        boolean inBuildHeight(BlockPos position);
        boolean loaded(BlockPos position);
        Map<String, Object> measure(BlockPos position);
    }

    private FabricNavigationCollisionCapture() {}

    static Capture capture(ServerLevel level, ServerPlayer player, int horizontalRadius, int verticalRadius) {
        if (level == null || player == null) throw failure("selected_player_missing");
        if (!level.getServer().isSameThread()) throw failure("wrong_thread");
        CollisionContext collisionContext = CollisionContext.of(player);
        return capture(new View() {
            public boolean onServerThread() { return level.getServer().isSameThread(); }
            public Identity identity() {
                boolean selected = player.level() == level && !player.isRemoved() && player.isAlive()
                    && player.getPose() == Pose.STANDING
                    && Math.abs(player.getBbWidth() - 0.6f) < 0.0001f
                    && Math.abs(player.getBbHeight() - 1.8f) < 0.0001f;
                return new Identity(level.dimension().location().toString(), player.getUUID(),
                    player.blockPosition().immutable(), level.getGameTime(), selected);
            }
            public boolean inBuildHeight(BlockPos p) { return !level.isOutsideBuildHeight(p); }
            public boolean loaded(BlockPos p) { return level.hasChunkAt(p); }
            public Map<String, Object> measure(BlockPos p) {
                return FabricNavigationCollisionFacts.measure(true, level.getBlockState(p), level, p, collisionContext);
            }
        }, horizontalRadius, verticalRadius, System::nanoTime);
    }

    static Capture capture(View view, int horizontalRadius, int verticalRadius, LongSupplier clock) {
        if (horizontalRadius < 1 || horizontalRadius > 7 || verticalRadius < 1 || verticalRadius > 8) {
            throw failure("radius_out_of_bounds");
        }
        int count = (2 * horizontalRadius + 1) * (2 * horizontalRadius + 1) * (2 * verticalRadius + 1);
        if (count > MAX_CELLS) throw failure("cell_budget_exceeded");
        if (!view.onServerThread()) throw failure("wrong_thread");
        long started = clock.getAsLong();
        Identity initial = view.identity();
        if (initial == null || !initial.available() || initial.player() == null || initial.dimension() == null
            || initial.dimension().isBlank() || initial.dimension().length() > 160 || initial.origin() == null
            || initial.tick() < 0 || initial.tick() > 9_007_199_254_740_991L) throw failure("selected_player_unavailable");
        if ((long) initial.origin().getX() - horizontalRadius < -30_000_000L
            || (long) initial.origin().getX() + horizontalRadius > 30_000_000L
            || (long) initial.origin().getZ() - horizontalRadius < -30_000_000L
            || (long) initial.origin().getZ() + horizontalRadius > 30_000_000L
            || (long) initial.origin().getY() - verticalRadius < -4096L
            || (long) initial.origin().getY() + verticalRadius > 4096L) throw failure("coordinate_bounds");
        BlockPos min = initial.origin().offset(-horizontalRadius, -verticalRadius, -horizontalRadius);
        BlockPos max = initial.origin().offset(horizontalRadius, verticalRadius, horizontalRadius);
        if (min.getX() < -30_000_000 || max.getX() > 30_000_000 || min.getZ() < -30_000_000
            || max.getZ() > 30_000_000 || min.getY() < -4096 || max.getY() > 4096) throw failure("coordinate_bounds");
        List<Map<String, Object>> cells = new ArrayList<>(count);
        int unknown = 0, unsupported = 0;
        for (int x = min.getX(); x <= max.getX(); x++) for (int y = min.getY(); y <= max.getY(); y++) for (int z = min.getZ(); z <= max.getZ(); z++) {
            elapsed(clock, started);
            if (!view.onServerThread()) throw failure("wrong_thread");
            BlockPos position = new BlockPos(x, y, z);
            boolean observed = view.inBuildHeight(position) && view.loaded(position);
            Map<String, Object> facts = observed ? Map.copyOf(view.measure(position))
                : FabricNavigationCollisionFacts.facts(false, false, false, false, false, false, false);
            if (!observed) unknown++;
            if ("unsupported".equals(facts.get("collision"))) unsupported++;
            cells.add(Map.of("position", coordinates(position), "facts", facts));
        }
        verifyCurrent(view, initial);
        Map<String, Object> replay = Map.of("schema", "minecraft.native_collision_replay.v1",
            "dimension", initial.dimension(), "subject_native_id", initial.player().toString(),
            "tick_start", initial.tick(), "tick_end", initial.tick(),
            "minimum", coordinates(min), "maximum", coordinates(max), "cells", List.copyOf(cells));
        int bytes = JSON.toJson(replay).getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_BYTES) throw failure("payload_budget_exceeded");
        verifyCurrent(view, initial);
        return new Capture(replay, count, unknown, unsupported, bytes, elapsed(clock, started));
    }

    private static List<Integer> coordinates(BlockPos p) { return List.of(p.getX(), p.getY(), p.getZ()); }
    private static void verifyCurrent(View view, Identity initial) {
        if (!view.onServerThread()) throw failure("wrong_thread");
        if (!initial.equals(view.identity())) throw failure("capture_identity_or_tick_changed");
    }
    private static long elapsed(LongSupplier clock, long started) {
        long elapsed = clock.getAsLong() - started;
        if (elapsed < 0 || elapsed > MAX_NANOS) throw failure("elapsed_budget_exceeded");
        return elapsed;
    }
    private static IllegalStateException failure(String reason) { return new IllegalStateException("navigation_capture:" + reason); }
}
