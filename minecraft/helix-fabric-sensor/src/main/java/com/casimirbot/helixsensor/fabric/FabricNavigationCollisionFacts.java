package com.casimirbot.helixsensor.fabric;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** NAV1-M internal measurement helper. Not registered in a probe or heartbeat.
 * The future capture caller must establish loaded coverage, same-tick reads,
 * selected-player collision context and authenticated observation provenance.
 * Never infer a full cube from a nonempty shape or its enclosing bounding box.
 */
final class FabricNavigationCollisionFacts {
    private FabricNavigationCollisionFacts() {}

    static Map<String, Object> measure(
        boolean loaded,
        BlockState state,
        BlockGetter world,
        BlockPos position,
        CollisionContext context
    ) {
        if (!loaded) return facts(false, false, false, false, false, false, false);
        boolean hazard = FabricPerceptionSnapshot.hazardType(state) != null;
        boolean fluid = !state.getFluidState().isEmpty();
        // Semantic allowlist supplements exact geometry; IDs never prove shape.
        // Everything else (moving, slippery, falling, modded, etc.) abstains.
        boolean reviewedSupport = state.is(Blocks.STONE) || state.is(Blocks.COBBLESTONE)
            || state.is(Blocks.DIRT) || state.is(Blocks.STONE_BRICKS);
        boolean reviewedAir = state.isAir() && (state.is(Blocks.AIR)
            || state.is(Blocks.CAVE_AIR) || state.is(Blocks.VOID_AIR));
        // Do not let unreviewed/contextual shape code query neighboring chunks.
        if (hazard || fluid || (!reviewedSupport && !reviewedAir)) {
            return facts(true, false, false, reviewedAir, reviewedSupport, hazard, fluid);
        }
        VoxelShape shape = state.getCollisionShape(world, position, context);
        return facts(true, shape.isEmpty(), isExactFullCube(shape), reviewedAir, reviewedSupport, hazard, fluid);
    }

    static boolean isExactFullCube(VoxelShape shape) {
        return !shape.isEmpty() && !Shapes.joinIsNotEmpty(shape, Shapes.block(), BooleanOp.NOT_SAME);
    }

    static Map<String, Object> facts(
        boolean loaded, boolean empty, boolean full, boolean air,
        boolean reviewedSupport, boolean hazard, boolean fluid
    ) {
        if (!loaded && (empty || full || air || reviewedSupport || hazard || fluid)) {
            throw new IllegalArgumentException("unloaded_cell_has_measured_facts");
        }
        if (empty && full) throw new IllegalArgumentException("contradictory_collision_shape");
        String collision = !loaded ? "unknown" : hazard ? "hazard" : fluid ? "fluid"
            : air && empty ? "empty" : reviewedSupport && full ? "full_cube" : "unsupported";
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("loaded", loaded);
        output.put("collision_empty", empty);
        output.put("collision_full_cube", full);
        output.put("air", air);
        output.put("reviewed_support", reviewedSupport);
        output.put("hazard", hazard);
        output.put("fluid", fluid);
        output.put("collision", collision);
        return Map.copyOf(output);
    }
}
