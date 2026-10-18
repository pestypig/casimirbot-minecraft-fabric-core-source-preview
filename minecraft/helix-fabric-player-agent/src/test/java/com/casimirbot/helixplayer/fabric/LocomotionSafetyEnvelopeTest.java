package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class LocomotionSafetyEnvelopeTest {
    private final LocomotionSafetyEnvelope envelope = new LocomotionSafetyEnvelope(6, 1);

    @Test
    void admitsSupportedLevelAndOneBlockDescent() {
        assertTrue(envelope.assess(observation(20, true, 0, 0, true, 0, false)).admitted());
        assertTrue(envelope.assess(observation(20, true, 0, 0, true, 1, false)).admitted());
    }

    @Test
    void refusesTheBroadMineFallGeometryBeforeMovement() {
        LocomotionSafetyEnvelope.Decision decision = envelope.assess(
            observation(20, true, 0, 0, true, 3, false)
        );
        assertFalse(decision.admitted());
        assertEquals("locomotion_predicted_drop_exceeded", decision.reasonCode());
    }

    @Test
    void refusesUnknownAndLavaLandingGeometry() {
        assertEquals(
            "locomotion_landing_geometry_unknown",
            envelope.assess(observation(20, true, 0, 0, false, 0, false)).reasonCode()
        );
        assertEquals(
            "locomotion_predicted_lava",
            envelope.assess(observation(20, true, 0, 0, true, 0, true)).reasonCode()
        );
    }

    @Test
    void refusesLowHealthUnlessASeparateRecoveryProgramOwnsMovement() {
        LocomotionSafetyEnvelope.Decision decision = envelope.assess(
            observation(5, true, 0, 0, true, 0, false)
        );
        assertFalse(decision.admitted());
        assertEquals("locomotion_health_floor_crossed", decision.reasonCode());
    }

    @Test
    void refusesAnAlreadyDevelopingFall() {
        LocomotionSafetyEnvelope.Decision decision = envelope.assess(
            observation(20, false, -0.6, 2.5, true, 0, false)
        );
        assertEquals("locomotion_active_fall", decision.reasonCode());
    }

    @Test
    void admitsAnOwnedJumpDescentOnlyWithSafeLandingGeometry() {
        assertTrue(envelope.assess(
            observation(20, false, -0.45, 1.2, true, 0, false, true)
        ).admitted());
        assertEquals(
            "locomotion_landing_geometry_unknown",
            envelope.assess(
                observation(20, false, -0.45, 1.2, false, 0, false, true)
            ).reasonCode()
        );
        assertEquals(
            "locomotion_predicted_lava",
            envelope.assess(
                observation(20, false, -0.45, 1.2, true, 0, true, true)
            ).reasonCode()
        );
        assertEquals(
            "locomotion_predicted_drop_exceeded",
            envelope.assess(
                observation(20, false, -0.45, 1.2, true, 3, false, true)
            ).reasonCode()
        );
    }

    private static LocomotionSafetyEnvelope.Observation observation(
        double health,
        boolean onGround,
        double verticalVelocity,
        double fallDistance,
        boolean geometryKnown,
        double dropBlocks,
        boolean landingLava
    ) {
        return observation(
            health,
            onGround,
            verticalVelocity,
            fallDistance,
            geometryKnown,
            dropBlocks,
            landingLava,
            false
        );
    }

    private static LocomotionSafetyEnvelope.Observation observation(
        double health,
        boolean onGround,
        double verticalVelocity,
        double fallDistance,
        boolean geometryKnown,
        double dropBlocks,
        boolean landingLava,
        boolean controlledJumpArc
    ) {
        return new LocomotionSafetyEnvelope.Observation(
            health, onGround, verticalVelocity, fallDistance,
            false, false, geometryKnown, dropBlocks, landingLava,
            controlledJumpArc
        );
    }
}
