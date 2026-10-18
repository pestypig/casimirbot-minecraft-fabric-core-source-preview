package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

final class CraftResultBudgetTest {
    @Test void acceptsOnlyOneWholeBatchWithinRemainingCountAndCapacity() {
        assertTrue(NativeFabricWorkflowEngine.craftResultFits(8, 4, 4, 64, true));
        assertTrue(NativeFabricWorkflowEngine.craftResultFits(4, 0, 4, 4, true));
        assertFalse(NativeFabricWorkflowEngine.craftResultFits(3, 0, 4, 64, true));
        assertFalse(NativeFabricWorkflowEngine.craftResultFits(8, 5, 4, 64, true));
        assertFalse(NativeFabricWorkflowEngine.craftResultFits(8, 0, 4, 3, true));
        assertFalse(NativeFabricWorkflowEngine.craftResultFits(8, 0, 4, 64, false));
        assertFalse(NativeFabricWorkflowEngine.craftResultFits(Integer.MAX_VALUE, Integer.MAX_VALUE - 1, 4, 64, true));
        assertFalse(NativeFabricWorkflowEngine.craftResultFits(8, -1, 4, 64, true));
        assertFalse(NativeFabricWorkflowEngine.craftResultFits(8, 0, 0, 64, true));
    }
}
