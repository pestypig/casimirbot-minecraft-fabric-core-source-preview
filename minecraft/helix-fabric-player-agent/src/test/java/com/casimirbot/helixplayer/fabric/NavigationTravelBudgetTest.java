package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class NavigationTravelBudgetTest {
    @Test
    void allowsShortProgressButStopsBeforeExhaustingThePathBudget() {
        assertTrue(NativeFabricWorkflowEngine.navigationTravelBudgetAllowsInput(0, 2.5, 2, 0.5));
        assertTrue(NativeFabricWorkflowEngine.navigationTravelBudgetAllowsInput(1.9, 2.5, 0.8, 0.5));
        assertFalse(NativeFabricWorkflowEngine.navigationTravelBudgetAllowsInput(2, 2.5, 0.6, 0.5));
        assertFalse(NativeFabricWorkflowEngine.navigationTravelBudgetAllowsInput(2.6, 2.5, 0.2, 0.5));
    }

    @Test
    void declinesUnreachableOrUnmeasurableBudgetBeforeMovement() {
        assertFalse(NativeFabricWorkflowEngine.navigationTravelBudgetAllowsInput(0, 2.5, 4, 1));
        assertFalse(NativeFabricWorkflowEngine.navigationTravelBudgetAllowsInput(
            Double.NaN, 2.5, 2, 1
        ));
        assertFalse(NativeFabricWorkflowEngine.navigationTravelBudgetAllowsInput(
            0, Double.POSITIVE_INFINITY, 2, 1
        ));
    }

    @Test
    void permitsSuccessfulArrivalWithinTheMeasuredBudget() {
        assertTrue(NativeFabricWorkflowEngine.navigationTravelBudgetAllowsInput(2.3, 2.5, 0.4, 0.5));
    }
}
