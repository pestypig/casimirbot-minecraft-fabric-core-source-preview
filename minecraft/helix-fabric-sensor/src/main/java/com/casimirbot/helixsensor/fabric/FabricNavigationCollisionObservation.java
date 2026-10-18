package com.casimirbot.helixsensor.fabric;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/** Optional evidence only. No retry, scheduling, chunk loading or action authority. */
final class FabricNavigationCollisionObservation {
    static final int RADIUS = 2;
    private static final Set<String> FAILURES = Set.of(
        "radius_out_of_bounds", "cell_budget_exceeded", "wrong_thread",
        "selected_player_unavailable", "coordinate_bounds", "payload_budget_exceeded",
        "capture_identity_or_tick_changed", "elapsed_budget_exceeded"
    );

    private FabricNavigationCollisionObservation() {}

    static Map<String, Object> capture(boolean requested, long snapshotTick,
                                      Supplier<FabricNavigationCollisionCapture.Capture> capture) {
        if (!requested) return null;
        try {
            var measured = capture.get();
            var replay = measured.replay();
            if (!Long.valueOf(snapshotTick).equals(replay.get("tick_start"))
                || !Long.valueOf(snapshotTick).equals(replay.get("tick_end"))) {
                return unavailable("capture_identity_or_tick_changed");
            }
            return Map.of("status", "captured", "replay", replay);
        } catch (IllegalStateException error) {
            String message = error.getMessage();
            String reason = message != null && message.startsWith("navigation_capture:")
                ? message.substring("navigation_capture:".length()) : "";
            if (!FAILURES.contains(reason)) throw error;
            return unavailable(reason);
        }
    }

    private static Map<String, Object> unavailable(String reason) {
        return Map.of("status", "unavailable", "reason", reason);
    }
}
