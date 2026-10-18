package com.casimirbot.helixsensor.fabric;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class FabricNavigationCollisionObservationTest {
    private FabricNavigationCollisionCapture.Capture measured(long start, long end) {
        return new FabricNavigationCollisionCapture.Capture(Map.of("tick_start", start, "tick_end", end), 125, 0, 0, 100, 0);
    }
    @Test void absentOptInDoesNotInvokeCapture() {
        assertNull(FabricNavigationCollisionObservation.capture(false, 100, () -> { fail("unexpected capture"); return null; }));
    }
    @Test void successRetainsReplayWithoutActionFields() {
        var value = measured(100, 100);
        assertEquals(Map.of("status", "captured", "replay", value.replay()), FabricNavigationCollisionObservation.capture(true, 100, () -> value));
    }
    @Test void rejectsMismatchWithTheBasePerceptionTick() {
        for (var value : new FabricNavigationCollisionCapture.Capture[] { measured(99, 100), measured(100, 101) }) {
            assertEquals(Map.of("status", "unavailable", "reason", "capture_identity_or_tick_changed"),
                FabricNavigationCollisionObservation.capture(true, 100, () -> value));
        }
    }
    @Test void expectedCaptureFailureIsTypedAndDoesNotExposeDetails() {
        for (String reason : new String[] { "radius_out_of_bounds", "cell_budget_exceeded", "wrong_thread",
            "selected_player_unavailable", "coordinate_bounds", "payload_budget_exceeded", "capture_identity_or_tick_changed", "elapsed_budget_exceeded" }) {
            assertEquals(Map.of("status", "unavailable", "reason", reason),
                FabricNavigationCollisionObservation.capture(true, 100, () -> { throw new IllegalStateException("navigation_capture:" + reason); }));
        }
    }
    @Test void unexpectedBugIsNotSilentlyReportedAsNormalUnavailability() {
        assertThrows(IllegalStateException.class, () -> FabricNavigationCollisionObservation.capture(true, 100,
            () -> { throw new IllegalStateException("unexpected bug"); }));
    }
}
