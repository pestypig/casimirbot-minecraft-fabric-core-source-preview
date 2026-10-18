package com.casimirbot.helixsensor.fabric;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.ArrayList;
import java.nio.file.Files;
import java.nio.file.Path;
import com.google.gson.GsonBuilder;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class FabricNavigationCollisionFactsTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private Map<String, Object> measure(BlockState state) {
        return FabricNavigationCollisionFacts.measure(true, state, EmptyBlockGetter.INSTANCE,
            BlockPos.ZERO, CollisionContext.empty());
    }

    @Test void exactShapeComparisonDoesNotConfuseBoundsWithCoverage() {
        assertTrue(FabricNavigationCollisionFacts.isExactFullCube(Shapes.block()));
        assertTrue(FabricNavigationCollisionFacts.isExactFullCube(Shapes.or(
            Shapes.box(0, 0, 0, 0.5, 1, 1), Shapes.box(0.5, 0, 0, 1, 1, 1))));
        assertFalse(FabricNavigationCollisionFacts.isExactFullCube(Shapes.empty()));
        assertFalse(FabricNavigationCollisionFacts.isExactFullCube(Shapes.box(0, 0, 0, 1, 0.5, 1)));
        assertFalse(FabricNavigationCollisionFacts.isExactFullCube(Shapes.box(0, 0, 0, 1, 1.5, 1)));
        assertFalse(FabricNavigationCollisionFacts.isExactFullCube(Shapes.or(
            Shapes.box(0, 0, 0, 0.25, 1, 1), Shapes.box(0.75, 0, 0, 1, 1, 1))));
    }

    @Test void measuresRealAirAndReviewedFullBlockStates() {
        assertEquals("empty", measure(Blocks.AIR.defaultBlockState()).get("collision"));
        for (var block : new net.minecraft.world.level.block.Block[] {
            Blocks.STONE, Blocks.COBBLESTONE, Blocks.DIRT, Blocks.STONE_BRICKS
        }) {
            Map<String, Object> result = measure(block.defaultBlockState());
            assertEquals(true, result.get("collision_full_cube"));
            assertEquals("full_cube", result.get("collision"));
        }
    }

    @Test void abstainsForPartialAndUnreviewedNativeStates() {
        for (var block : new net.minecraft.world.level.block.Block[] {
            Blocks.STONE_SLAB, Blocks.STONE_STAIRS, Blocks.OAK_FENCE, Blocks.SAND,
            Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK, Blocks.OAK_LEAVES, Blocks.SHORT_GRASS
        }) assertEquals("unsupported", measure(block.defaultBlockState()).get("collision"));
    }

    @Test void excludesHazardsAndFluidsBeforeSafeShapeClassification() {
        assertEquals("hazard", measure(Blocks.MAGMA_BLOCK.defaultBlockState()).get("collision"));
        assertEquals("hazard", measure(Blocks.LAVA.defaultBlockState()).get("collision"));
        assertEquals("fluid", measure(Blocks.WATER.defaultBlockState()).get("collision"));
    }

    @Test void unloadedEvidenceDoesNotReadStateOrWorld() {
        assertEquals("unknown", FabricNavigationCollisionFacts.measure(false, null, null, null, null).get("collision"));
    }

    @Test void unreviewedShapesDoNotConsultPotentiallyUnloadedNeighbors() {
        assertEquals("unsupported", FabricNavigationCollisionFacts.measure(true,
            Blocks.STONE_STAIRS.defaultBlockState(), null, null, null).get("collision"));
        assertEquals("unsupported", FabricNavigationCollisionFacts.measure(true,
            Blocks.OAK_FENCE.defaultBlockState(), null, null, null).get("collision"));
    }

    @Test void contradictoryFactsRejectAndUnknownNeverAcquiresGeometry() {
        assertThrows(IllegalArgumentException.class,
            () -> FabricNavigationCollisionFacts.facts(false, true, false, true, false, false, false));
        assertThrows(IllegalArgumentException.class,
            () -> FabricNavigationCollisionFacts.facts(true, true, true, false, false, false, false));
    }

    @Test void exportsNativeMeasuredStoneAndAirFixtureForNeutralReplay() throws Exception {
        var cells = new ArrayList<Map<String, Object>>();
        for (int x = -2; x <= 2; x++) for (int y = -1; y <= 2; y++) for (int z = -2; z <= 2; z++) {
            cells.add(Map.of("position", new int[] {x, y, z}, "facts",
                measure((y == -1 ? Blocks.STONE : Blocks.AIR).defaultBlockState())));
        }
        assertEquals(100, cells.size());
        var replay = Map.of("schema", "minecraft.native_collision_replay.v1",
            "dimension", "minecraft:overworld", "subject_native_id", "00000000-0000-4000-8000-000000000001",
            "tick_start", 100, "tick_end", 100, "minimum", new int[] {-2, -1, -2},
            "maximum", new int[] {2, 2, 2}, "cells", cells);
        Path output = Path.of("build/nav1m/native-collision-replay.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new GsonBuilder().setPrettyPrinting().create().toJson(replay));
    }
}
