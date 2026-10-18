package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class ShortHorizonTrajectoryPredictorTest {
    @Test
    void projectsEveryTickWithoutAProviderRoundTrip() {
        ShortHorizonTrajectoryPredictor.Forecast forecast =
            ShortHorizonTrajectoryPredictor.predict(0, 80, 0, 0.2, -0.4, 0, 10);

        assertEquals(11, forecast.states().size());
        assertEquals(10, forecast.finalState().tick());
        assertTrue(forecast.finalState().y() < 76);
        assertTrue(forecast.finalState().velocityY() < -0.4);
        assertTrue(forecast.finalState().x() > 1);
    }

    @Test
    void predictsTheFirstTickAnExactPlacementFaceEntersReach() {
        ShortHorizonTrajectoryPredictor.Forecast forecast =
            ShortHorizonTrajectoryPredictor.predict(0, 75, 0, 0, -1, 0, 8);
        ShortHorizonTrajectoryPredictor.PlacementForecast placement =
            ShortHorizonTrajectoryPredictor.predictPlacementReach(
                forecast,
                1.62,
                0.5,
                70.5,
                0.5,
                4.5,
                1
            );

        assertTrue(placement.supportAvailable());
        assertTrue(placement.predictedReachable());
        assertTrue(placement.firstReachableTick() > 0);
        assertTrue(placement.minimumPredictedDistance() < placement.initialDistance());
    }

    @Test
    void refusesToCallUnsupportedGeometryReachableWithoutASupportFace() {
        ShortHorizonTrajectoryPredictor.Forecast forecast =
            ShortHorizonTrajectoryPredictor.predict(0, 64, 0, 0, 0, 0, 4);
        ShortHorizonTrajectoryPredictor.PlacementForecast placement =
            ShortHorizonTrajectoryPredictor.predictPlacementReach(
                forecast,
                1.62,
                0.5,
                64.5,
                0.5,
                4.5,
                0
            );

        assertFalse(placement.supportAvailable());
        assertFalse(placement.predictedReachable());
    }

    @Test
    void enforcesTheBoundedHorizon() {
        assertThrows(
            IllegalArgumentException.class,
            () -> ShortHorizonTrajectoryPredictor.predict(0, 0, 0, 0, 0, 0, 21)
        );
    }
}
