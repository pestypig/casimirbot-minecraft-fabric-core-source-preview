package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

final class NativeFabricWorkflowEngineMiningGeometryTest {
    @Test
    void baritoneNavigationUsesTheReusableWorkflowLifecycle() {
        assertTrue(NativeFabricWorkflowEngine.usesReusableWorkflowEngine("navigate_to"));
        assertFalse(NativeFabricWorkflowEngine.usesReusableWorkflowEngine("look_at"));
    }

    @Test
    void miningNavigationFailsOnlyAfterItsBoundedNonProgressWindow() {
        assertFalse(NativeFabricWorkflowEngine.miningNavigationStalled(200));
        assertTrue(NativeFabricWorkflowEngine.miningNavigationStalled(201));
    }

    @Test
    void missingMiningFocusGetsABriefCameraWindowBeforeRepositioning() {
        assertFalse(NativeFabricWorkflowEngine.miningFocusNeedsApproach(5));
        assertTrue(NativeFabricWorkflowEngine.miningFocusNeedsApproach(6));
    }

    @Test
    void rejectsAFullyEnclosedMiningTarget() {
        BlockPos target = new BlockPos(10, 64, 10);

        Direction face = NativeFabricWorkflowEngine.nearestExposedMiningFace(
            target,
            new Vec3(10.5, 66.0, 7.0),
            ignored -> false
        );

        assertNull(face);
    }

    @Test
    void choosesTheNearestLegitimatelyExposedFace() {
        BlockPos target = new BlockPos(10, 64, 10);
        Set<BlockPos> open = Set.of(
            target.relative(Direction.NORTH),
            target.relative(Direction.UP)
        );

        Direction face = NativeFabricWorkflowEngine.nearestExposedMiningFace(
            target,
            new Vec3(10.5, 65.6, 7.0),
            open::contains
        );

        assertEquals(Direction.NORTH, face);
    }

    @Test
    void computesTheBoundedNearestRankP95WithoutMutatingSamples() {
        long[] samples = { 4, 1, 3, 2, 100, 9 };

        assertEquals(100, NativeFabricWorkflowEngine.p95DurationNanos(samples, 5));
        assertEquals(4, samples[0]);
        assertEquals(0, NativeFabricWorkflowEngine.p95DurationNanos(samples, 0));
    }
}
