package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class PredictiveCameraTrackerTest {
    @Test
    void accelerationBoundsEachRenderFrameTurn() {
        PredictiveCameraTracker.Step first = PredictiveCameraTracker.stepTowardPosition(
            0,
            0,
            0,
            65,
            0,
            10,
            65,
            0,
            20,
            4,
            0.25F,
            1,
            PredictiveCameraTracker.State.initial()
        );
        assertTrue(first.moved());
        assertEquals(-4.0F, first.yaw(), 0.0001F);
        assertEquals(4.0F, first.state().angularRateDegreesPerTick(), 0.0001F);

        PredictiveCameraTracker.Step second = PredictiveCameraTracker.stepTowardPosition(
            first.yaw(),
            first.pitch(),
            0,
            65,
            0,
            10,
            65,
            0,
            20,
            4,
            0.25F,
            1,
            first.state()
        );
        assertEquals(-12.0F, second.yaw(), 0.0001F);
        assertEquals(8.0F, second.state().angularRateDegreesPerTick(), 0.0001F);
    }

    @Test
    void choosesTheShortestYawPathAcrossTheWrapBoundary() {
        PredictiveCameraTracker.Step step = PredictiveCameraTracker.stepTowardAngles(
            179,
            0,
            -179,
            0,
            10,
            10,
            0,
            1,
            PredictiveCameraTracker.State.initial()
        );

        assertEquals(181.0F, step.yaw(), 0.0001F);
        assertEquals(0.0F, PredictiveCameraTracker.wrapDegrees(-179 - step.yaw()), 0.0001F);
    }

    @Test
    void deadbandHoldsTheViewAndDeceleratesInternalRate() {
        PredictiveCameraTracker.Step step = PredictiveCameraTracker.stepTowardAngles(
            5,
            -2,
            5.2F,
            -1.9F,
            20,
            4,
            0.5F,
            0.5,
            new PredictiveCameraTracker.State(3)
        );

        assertFalse(step.moved());
        assertEquals(5.0F, step.yaw(), 0.0001F);
        assertEquals(-2.0F, step.pitch(), 0.0001F);
        assertEquals(1.0F, step.state().angularRateDegreesPerTick(), 0.0001F);
    }

    @Test
    void subTickFramesRemainAccelerationAndRateBounded() {
        PredictiveCameraTracker.Step step = PredictiveCameraTracker.stepTowardAngles(
            0,
            0,
            90,
            45,
            20,
            4,
            0,
            0.25,
            PredictiveCameraTracker.State.initial()
        );

        assertEquals(1.0F, step.state().angularRateDegreesPerTick(), 0.0001F);
        assertEquals(0.25F, step.yaw(), 0.0001F);
        assertEquals(0.25F, step.pitch(), 0.0001F);
    }

    @Test
    void rejectsNonFiniteTrackingGeometry() {
        assertThrows(
            IllegalArgumentException.class,
            () -> PredictiveCameraTracker.stepTowardPosition(
                0,
                0,
                0,
                65,
                0,
                Double.NaN,
                65,
                10,
                20,
                4,
                0,
                1,
                PredictiveCameraTracker.State.initial()
            )
        );
    }
}
