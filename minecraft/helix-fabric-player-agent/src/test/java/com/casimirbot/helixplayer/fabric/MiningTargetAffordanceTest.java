package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

final class MiningTargetAffordanceTest {
    @Test
    void derivesAStandableSurfacePoseInsteadOfWalkingIntoTheTargetCenter() {
        BlockPos target = new BlockPos(0, 64, 0);
        PlayerSensorFrame frame = frameAt(
            0.5, 65, 66.62, -4.5,
            PlayerSensorFrame.Focus.miss(0.5, 66.62, -4.5)
        );

        MiningTargetAffordance affordance = MiningTargetAffordance.derive(
            frame,
            target,
            4.5,
            position -> position.getY() >= 65,
            position -> position.getY() <= 64
        );

        assertNotNull(affordance.approach());
        assertNotEquals(0.5, affordance.approach().z());
        assertEquals(65.0, affordance.approach().y());
        assertNotNull(affordance.face());
        assertFalse(affordance.focused());
    }

    @Test
    void bindsFocusToTheSameSensorFrameAndExactTarget() {
        BlockPos target = new BlockPos(3, 63, -2);
        PlayerSensorFrame.Focus focus = new PlayerSensorFrame.Focus(
            PlayerSensorFrame.FocusKind.BLOCK,
            3, 63, -2, "north", 3.5, 63.5, -2.501, 2.0
        );
        MiningTargetAffordance affordance = MiningTargetAffordance.derive(
            frameAt(3.5, 63, 64.62, -4.5, focus),
            target,
            4.5,
            ignored -> true,
            ignored -> true
        );

        assertTrue(affordance.focused());
        assertTrue(affordance.inInteractionRange());
    }

    @Test
    void returnsNoApproachWhenTheBoundedNeighborhoodHasNoSupport() {
        MiningTargetAffordance affordance = MiningTargetAffordance.derive(
            frameAt(0.5, 64, 65.62, -5, PlayerSensorFrame.Focus.miss(0.5, 65.62, -5)),
            new BlockPos(0, 64, 0),
            4.5,
            ignored -> true,
            ignored -> false
        );

        assertNull(affordance.approach());
        assertNotNull(affordance.face());
    }

    @Test
    void approachDerivationHasAConstantQueryCeiling() {
        AtomicInteger passableQueries = new AtomicInteger();
        AtomicInteger solidQueries = new AtomicInteger();
        Predicate<BlockPos> neverPassable = ignored -> {
            passableQueries.incrementAndGet();
            return false;
        };
        Predicate<BlockPos> solid = ignored -> {
            solidQueries.incrementAndGet();
            return true;
        };

        assertTrue(MiningTargetAffordance.nearestApproach(
            new BlockPos(0, 64, 0),
            new Vec3(0.5, 65.62, -8),
            neverPassable,
            solid
        ).isEmpty());
        assertTrue(passableQueries.get() <= 144);
        assertEquals(0, solidQueries.get());
    }

    private static PlayerSensorFrame frameAt(
        double x,
        double y,
        double eyeY,
        double z,
        PlayerSensorFrame.Focus focus
    ) {
        return new PlayerSensorFrame(
            2, 42, 50_000, "minecraft:overworld", true,
            x, y, eyeY, z, 0, 0, 0,
            0, 0, 20, true, false, false, false,
            false, null, focus
        );
    }
}
