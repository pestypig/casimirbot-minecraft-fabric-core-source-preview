package com.casimirbot.helixsensor.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FabricFallRescueControllerTest {
    @Test
    void admitsOnlyAnUnprotectedSurvivalFall() {
        assertTrue(
            FabricFallRescueController.shouldAttempt(
                4.0d,
                -0.6d,
                false,
                false,
                false,
                false,
                false
            )
        );
        assertFalse(
            FabricFallRescueController.shouldAttempt(
                2.0d,
                -0.6d,
                false,
                false,
                false,
                false,
                false
            )
        );
        assertFalse(
            FabricFallRescueController.shouldAttempt(
                4.0d,
                -0.6d,
                true,
                false,
                false,
                false,
                false
            )
        );
        assertFalse(
            FabricFallRescueController.shouldAttempt(
                4.0d,
                -0.6d,
                false,
                true,
                false,
                false,
                false
            )
        );
        assertFalse(
            FabricFallRescueController.shouldAttempt(
                4.0d,
                -0.6d,
                false,
                false,
                false,
                false,
                true
            )
        );
        assertFalse(
            FabricFallRescueController.shouldAttempt(
                4.0d,
                -0.6d,
                false,
                false,
                true,
                false,
                false
            )
        );
        assertFalse(
            FabricFallRescueController.shouldAttempt(
                4.0d,
                -0.6d,
                false,
                false,
                false,
                true,
                false
            )
        );
        assertFalse(
            FabricFallRescueController.shouldAttempt(
                4.0d,
                -0.2d,
                false,
                false,
                false,
                false,
                false
            )
        );
    }

    @Test
    void cleansUpAndExpiresARescueLeaseWhileThePlayerIsOffline() {
        assertEquals(
            FabricFallRescueController.OfflineLeaseAction.RESTORE_WATER,
            FabricFallRescueController.offlineLeaseAction(
                true,
                110L,
                100L,
                200L
            )
        );
        assertEquals(
            FabricFallRescueController.OfflineLeaseAction.EXPIRE,
            FabricFallRescueController.offlineLeaseAction(
                true,
                200L,
                100L,
                200L
            )
        );
        assertEquals(
            FabricFallRescueController.OfflineLeaseAction.RETAIN,
            FabricFallRescueController.offlineLeaseAction(
                false,
                110L,
                100L,
                200L
            )
        );
        assertEquals(
            FabricFallRescueController.OfflineLeaseAction.RETAIN,
            FabricFallRescueController.offlineLeaseAction(
                true,
                90L,
                100L,
                200L
            )
        );
    }

    @Test
    void retainsASettledRescueResultLongEnoughForSlowAgentFollowUp() {
        assertTrue(FabricFallRescueController.recentResultAvailable(18_000L, 10_000L));
        assertTrue(FabricFallRescueController.recentResultAvailable(22_000L, 10_000L));
        assertFalse(FabricFallRescueController.recentResultAvailable(22_001L, 10_000L));
        assertFalse(FabricFallRescueController.recentResultAvailable(9_999L, 10_000L));
    }

    @Test
    void reportsAnInactiveResultWithoutErasingItsTriggerEvidence() {
        String status = FabricFallRescueController.inactiveStatusMessage(
            1,
            "rescue_completed_water_removed",
            "-46,68,-2",
            37L
        );

        assertTrue(status.contains("inactive"));
        assertTrue(status.contains("remaining_seconds=0"));
        assertTrue(status.contains("trigger_count=1"));
        assertTrue(status.contains("last_outcome=rescue_completed_water_removed"));
        assertTrue(status.contains("last_position=-46,68,-2"));
        assertTrue(status.contains("result_age_seconds=37"));
    }
}
