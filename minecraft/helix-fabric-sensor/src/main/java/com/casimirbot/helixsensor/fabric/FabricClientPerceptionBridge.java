package com.casimirbot.helixsensor.fabric;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-side join point for authenticated player-bound client UI telemetry.
 * A stale or absent client sample is never promoted to a closed screen.
 */
final class FabricClientPerceptionBridge {
    record Snapshot(
        long gameTick,
        long receivedServerTick,
        String screenState,
        String screenKind,
        boolean inputActivity
    ) {}

    private static final ConcurrentHashMap<UUID, Snapshot> LATEST_BY_PLAYER =
        new ConcurrentHashMap<>();

    private FabricClientPerceptionBridge() {}

    static void publish(UUID playerId, Snapshot snapshot) {
        if (playerId == null || snapshot == null) return;
        LATEST_BY_PLAYER.compute(playerId, (ignored, previous) ->
            previous == null || snapshot.receivedServerTick() >= previous.receivedServerTick()
                ? snapshot
                : previous
        );
    }

    static void clear(UUID playerId) {
        if (playerId != null) LATEST_BY_PLAYER.remove(playerId);
    }

    static void clearAll() {
        LATEST_BY_PLAYER.clear();
    }

    static Snapshot fresh(UUID playerId, long serverTick, long maxAgeTicks) {
        Snapshot snapshot = playerId == null ? null : LATEST_BY_PLAYER.get(playerId);
        if (snapshot == null) return null;
        long age = serverTick - snapshot.receivedServerTick();
        return age >= 0L && age <= Math.max(0L, maxAgeTicks) ? snapshot : null;
    }
}
