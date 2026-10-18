package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class NativeFabricNavigationStepTest {
    private static final BlockPos PLANNED_FOOTHOLD = new BlockPos(4, 95, -60);

    @Test
    void transientJumpAbovePlannedFootholdDoesNotInvalidateGroundRoute() {
        assertEquals(
            PLANNED_FOOTHOLD,
            NativeFabricWorkflowEngine.navigationStepValidationOrigin(
                new BlockPos(4, 96, -60), PLANNED_FOOTHOLD, false
            )
        );
    }

    @Test
    void groundedOrHorizontallyDeviatedPositionMustBeRevalidated() {
        BlockPos airborneElsewhere = new BlockPos(5, 96, -60);
        assertEquals(
            airborneElsewhere,
            NativeFabricWorkflowEngine.navigationStepValidationOrigin(
                airborneElsewhere, PLANNED_FOOTHOLD, false
            )
        );
        BlockPos groundedAbove = new BlockPos(4, 96, -60);
        assertEquals(
            groundedAbove,
            NativeFabricWorkflowEngine.navigationStepValidationOrigin(
                groundedAbove, PLANNED_FOOTHOLD, true
            )
        );
    }

    @Test
    void onlyAnEngineIssuedJumpOwnsTheAirborneArcUntilLanding() {
        assertEquals(true, NativeFabricWorkflowEngine.retainOwnedJumpArc(false, true, true));
        assertEquals(true, NativeFabricWorkflowEngine.retainOwnedJumpArc(true, false, false));
        assertEquals(false, NativeFabricWorkflowEngine.retainOwnedJumpArc(true, true, false));
        assertEquals(false, NativeFabricWorkflowEngine.retainOwnedJumpArc(false, false, false));
    }
}
