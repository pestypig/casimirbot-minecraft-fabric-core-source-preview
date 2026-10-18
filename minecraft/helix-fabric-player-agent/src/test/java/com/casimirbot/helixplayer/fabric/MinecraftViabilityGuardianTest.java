package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class MinecraftViabilityGuardianTest {
    private static MinecraftViabilityGuardian.Profile profile() {
        return new MinecraftViabilityGuardian.Profile(1_000, 80, -0.72, 100, 1);
    }

    private static MinecraftViabilityGuardian.Observation observation(
        long revision,
        int air,
        boolean submerged,
        boolean onFire,
        boolean inLava,
        double velocityY,
        boolean safeLanding,
        boolean manualOverride,
        boolean emergencyStop
    ) {
        return new MinecraftViabilityGuardian.Observation(
            revision, revision, revision, true, 20, air, 300,
            submerged, submerged, submerged, onFire, inLava,
            !submerged && velocityY == 0, false, velocityY, safeLanding,
            false, false,
            manualOverride, emergencyStop
        );
    }

    @Test
    void holdsTheSurfaceUntilARealWaterExitThenReleases() {
        MinecraftViabilityGuardian guardian = new MinecraftViabilityGuardian();
        guardian.arm(profile());

        var rescue = guardian.step(observation(1, 70, true, false, false, 0, true, false, false));
        assertEquals(MinecraftViabilityGuardian.ProposalKind.SWIM_UP, rescue.proposal());
        assertFalse(rescue.controlsMustRelease());
        assertFalse(rescue.semanticEscalationRequired());

        var restored = guardian.step(new MinecraftViabilityGuardian.Observation(
            2, 2, 2, true, 20, 120, 300,
            false, false, true, false, false,
            false, false, 0.13, false, false, false, false, false
        ));
        assertEquals(MinecraftViabilityGuardian.ProposalKind.SWIM_UP, restored.proposal());
        assertEquals("breathing_restored_surface_hold", restored.reasonCode());
        assertFalse(restored.controlsMustRelease());
        assertTrue(restored.semanticEscalationRequired());

        var bobbedUnder = guardian.step(observation(3, 115, true, false, false, 0, true, false, false));
        assertEquals(MinecraftViabilityGuardian.ProposalKind.SWIM_UP, bobbedUnder.proposal());
        assertEquals("breathing_restored_surface_hold", bobbedUnder.reasonCode());

        var airborneAboveSurface = guardian.step(new MinecraftViabilityGuardian.Observation(
            4, 4, 4, true, 20, 140, 300,
            false, false, false, false, false,
            false, false, 0.05, false, false, false, false, false
        ));
        assertEquals(MinecraftViabilityGuardian.ProposalKind.SWIM_UP, airborneAboveSurface.proposal());
        assertEquals("breathing_restored_surface_hold", airborneAboveSurface.reasonCode());
        assertFalse(airborneAboveSurface.controlsMustRelease());

        var exited = guardian.step(observation(5, 160, false, false, false, 0, true, false, false));
        assertEquals(MinecraftViabilityGuardian.ProposalKind.RELEASE_AND_ESCALATE, exited.proposal());
        assertEquals("water_exit_verified", exited.reasonCode());
        assertTrue(exited.controlsMustRelease());
    }

    @Test
    void fireLavaAndUnsafeFallStopAndEscalateWithoutInventingARecovery() {
        MinecraftViabilityGuardian guardian = new MinecraftViabilityGuardian();
        guardian.arm(profile());
        assertEquals(
            "fire_pressure_requires_semantic_replan",
            guardian.step(observation(1, 300, false, true, false, 0, true, false, false)).reasonCode()
        );
        assertEquals(
            "lava_pressure_requires_semantic_replan",
            guardian.step(observation(2, 300, false, false, true, 0, true, false, false)).reasonCode()
        );
        assertEquals(
            "unsafe_landing_requires_admitted_recovery",
            guardian.step(observation(3, 300, false, false, false, -1.2, false, false, false)).reasonCode()
        );
    }

    @Test
    void manualOverrideAndEmergencyStopDisarmTheResidentController() {
        MinecraftViabilityGuardian guardian = new MinecraftViabilityGuardian();
        guardian.arm(profile());
        var manual = guardian.step(observation(1, 300, false, false, false, 0, true, true, false));
        assertEquals("manual_override", manual.reasonCode());
        assertTrue(manual.controlsMustRelease());
        assertFalse(guardian.armed());

        guardian.arm(profile());
        var stopped = guardian.step(observation(2, 300, false, false, false, 0, true, false, true));
        assertEquals("emergency_stop", stopped.reasonCode());
        assertTrue(stopped.controlsMustRelease());
        assertFalse(guardian.armed());
    }

    @Test
    void staleOrRegressedObservationsFailClosed() {
        MinecraftViabilityGuardian guardian = new MinecraftViabilityGuardian();
        guardian.arm(profile());
        guardian.step(observation(2, 300, false, false, false, 0, true, false, false));
        var regressed = guardian.step(observation(2, 300, false, false, false, 0, true, false, false));
        assertEquals(MinecraftViabilityGuardian.ProposalKind.ABSTAIN_AND_ESCALATE, regressed.proposal());
        assertEquals("observation_revision_not_monotonic", regressed.reasonCode());
        assertTrue(regressed.controlsMustRelease());
    }

    @Test
    void boundedSwimRepertoireStopsWhenItCannotRestoreAir() {
        MinecraftViabilityGuardian guardian = new MinecraftViabilityGuardian();
        guardian.arm(new MinecraftViabilityGuardian.Profile(1_000, 80, -0.72, 1, 1));
        assertEquals(
            MinecraftViabilityGuardian.ProposalKind.SWIM_UP,
            guardian.step(observation(1, 70, true, false, false, 0, true, false, false)).proposal()
        );
        var exhausted = guardian.step(observation(2, 60, true, false, false, 0, true, false, false));
        assertEquals("swim_repertoire_exhausted", exhausted.reasonCode());
        assertTrue(exhausted.controlsMustRelease());
    }

    @Test
    void leaseExpiryDuringWaterRecoveryIsExplicitAndReleasesControl() {
        MinecraftViabilityGuardian guardian = new MinecraftViabilityGuardian();
        guardian.arm(new MinecraftViabilityGuardian.Profile(2, 80, -0.72, 100, 1));
        guardian.step(observation(1, 70, true, false, false, 0, true, false, false));

        var expired = guardian.step(observation(3, 65, true, false, false, 0, true, false, false));
        assertEquals(
            "guardian_lease_expired_during_water_recovery",
            expired.reasonCode()
        );
        assertEquals(
            MinecraftViabilityGuardian.ProposalKind.RELEASE_AND_ESCALATE,
            expired.proposal()
        );
        assertTrue(expired.controlsMustRelease());
        assertTrue(expired.semanticEscalationRequired());
        assertFalse(guardian.armed());
    }

    @Test
    void persistentDryCollisionInterruptsBlockedMovement() {
        MinecraftViabilityGuardian guardian = new MinecraftViabilityGuardian();
        guardian.arm(profile());
        MinecraftViabilityGuardian.Decision decision = null;
        for (int revision = 1; revision <= 10; revision++) {
            decision = guardian.step(new MinecraftViabilityGuardian.Observation(
                revision, revision, revision, true, 20, 300, 300,
                false, false, false, false, false, true, true,
                0, true, false, false, false, false
            ));
        }

        assertNotNull(decision);
        assertEquals(
            MinecraftViabilityGuardian.ProposalKind.RELEASE_AND_ESCALATE,
            decision.proposal()
        );
        assertEquals("movement_blocked_requires_semantic_replan", decision.reasonCode());
        assertEquals(10, decision.measurements().get("collision_ticks"));
        assertTrue(decision.controlsMustRelease());
    }

    @Test
    void admittedFallRecoveryRemainsInControlUntilItsPostconditionIsVerified() {
        MinecraftViabilityGuardian guardian = new MinecraftViabilityGuardian();
        guardian.arm(profile());

        var delegated = guardian.step(new MinecraftViabilityGuardian.Observation(
            1, 1, 1, true, 20, 300, 300,
            false, false, false, false, false, false, false,
            -1.2, false, true, false, false, false
        ));
        assertEquals(
            MinecraftViabilityGuardian.ProposalKind.MONITOR_ADMITTED_RECOVERY,
            delegated.proposal()
        );
        assertEquals("unsafe_landing_recovery_active", delegated.reasonCode());
        assertFalse(delegated.controlsMustRelease());

        var settling = guardian.step(new MinecraftViabilityGuardian.Observation(
            2, 2, 2, true, 20, 300, 300,
            false, false, true, false, false, true, false,
            0, true, true, false, false, false
        ));
        assertEquals(
            MinecraftViabilityGuardian.ProposalKind.MONITOR_ADMITTED_RECOVERY,
            settling.proposal()
        );

        var verified = guardian.step(new MinecraftViabilityGuardian.Observation(
            3, 3, 3, true, 20, 300, 300,
            false, false, false, false, false, true, false,
            0, true, false, false, false, false
        ));
        assertEquals("fall_recovery_verified", verified.reasonCode());
        assertTrue(verified.controlsMustRelease());
    }

    @Test
    void admittedFireRecoveryMustMeasureExtinguishmentBeforeRelease() {
        MinecraftViabilityGuardian guardian = new MinecraftViabilityGuardian();
        guardian.arm(profile());

        var delegated = guardian.step(new MinecraftViabilityGuardian.Observation(
            1, 1, 1, true, 20, 300, 300,
            false, false, false, false, true, true, false,
            0, true, false, true, false, false
        ));
        assertEquals(
            MinecraftViabilityGuardian.ProposalKind.MONITOR_ADMITTED_RECOVERY,
            delegated.proposal()
        );
        assertEquals("fire_recovery_program_active", delegated.reasonCode());

        var postcondition = guardian.step(new MinecraftViabilityGuardian.Observation(
            2, 2, 2, true, 20, 300, 300,
            false, false, false, false, false, true, false,
            0, true, false, true, false, false
        ));
        assertEquals(
            "fire_recovery_postcondition_observed",
            postcondition.reasonCode()
        );
        assertFalse(postcondition.controlsMustRelease());

        var verified = guardian.step(new MinecraftViabilityGuardian.Observation(
            3, 3, 3, true, 20, 300, 300,
            false, false, false, false, false, true, false,
            0, true, false, false, false, false
        ));
        assertEquals("fire_recovery_verified", verified.reasonCode());
        assertTrue(verified.controlsMustRelease());
    }
}
