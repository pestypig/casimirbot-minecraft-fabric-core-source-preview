package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class TemporalPlanWindowTest {
    @Test void reservationDeadlineAndActivationBoundaryAreDifferentObligations() {
        var predecessor = new TemporalPlanWindow("origin", 100, 0, 1, 2, 20, 1000);
        var successor = new TemporalPlanWindow("origin", 100, 0, 3, 4, 20, 1000);
        assertTrue(predecessor.canReserve(successor, "origin", 100, 0));
        assertFalse(predecessor.canReserve(successor, "origin", 101, 50));
        predecessor.reserveHandoff();
        assertFalse(predecessor.handoffAllowed("origin", 101, 50));
        assertTrue(predecessor.handoffAllowed("origin", 102, 100));
        assertFalse(predecessor.handoffAllowed("origin", 103, 150));
        assertFalse(predecessor.handoffAllowed("origin", 102, 1000));
    }
    @Test void stabilizationHardStopsPreserveTheirFirstCause() {
        for (String failure : java.util.List.of("clock", "deadline", "horizon")) {
            TemporalPlanWindow window = window();
            window.observe("origin", 106, 300);
            assertFalse(window.observeStabilization("origin", 106, 300).mustStabilize());
            TemporalPlanWindow.Observation stopped = switch (failure) {
                case "clock" -> window.observeStabilization("other", 107, 350);
                case "deadline" -> window.observeStabilization("origin", 107, 1000);
                default -> window.observeStabilization("origin", 110, 500);
            };
            assertTrue(stopped.mustStabilize());
            assertEquals(stopped.reason(), window.observeStabilization("origin", 107, 350).reason());
        }
    }

    private TemporalPlanWindow window() { return new TemporalPlanWindow("origin", 100, 3, 6, 8, 10, 1000); }

    @Test void decisionIsEmittedOnceAndStopIsInclusiveAndSticky() {
        TemporalPlanWindow window = window();
        assertEquals(6, window.observe("origin", 100, 0).remainingTicks());
        assertTrue(window.observe("origin", 103, 150).decisionRequired());
        assertFalse(window.observe("origin", 104, 200).decisionRequired());
        assertTrue(window.observe("origin", 106, 300).mustStabilize());
        assertTrue(window.observe("origin", 100, 0).mustStabilize());
    }

    @Test void elapsedDeadlineStopsEvenIfMinecraftTicksArePaused() {
        TemporalPlanWindow window = window();
        assertFalse(window.observe("origin", 100, 999).mustStabilize());
        assertEquals("temporal_monotonic_deadline", window.observe("origin", 100, 1000).reason());
    }

    @Test void originChangeAndRegressionsFailClosed() {
        assertTrue(window().observe("other", 100, 0).mustStabilize());
        assertTrue(window().observe("origin", 99, 0).mustStabilize());
        assertTrue(window().observe("origin", 100, Double.NaN).mustStabilize());
        TemporalPlanWindow window = window();
        window.observe("origin", 101, 100);
        assertTrue(window.observe("origin", 102, 99).mustStabilize());
    }

    @Test void malformedWatermarksAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TemporalPlanWindow("origin", 0, 7, 6, 8, 10, 100));
        assertThrows(IllegalArgumentException.class, () -> new TemporalPlanWindow("origin", 0, 3, 6, 5, 10, 100));
    }
}
