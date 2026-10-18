package com.casimirbot.helixsensor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

final class FabricSpatialSurveyTest {
    private static final Gson JSON = new Gson();

    @Test
    void keepsTheElevatedLandingSurveyInsideTheBrokerCellCeiling() {
        int diameter = FabricSpatialSurvey.MAX_HORIZONTAL_RADIUS * 2 + 1;
        int height = FabricSpatialSurvey.MAX_VERTICAL_RADIUS * 2 + 1;

        assertEquals(16, FabricSpatialSurvey.MAX_VERTICAL_RADIUS);
        assertTrue(diameter * diameter * height <= 10_000);
    }

    @Test
    void recordsTheExactSerializedWireSize() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("payload", "x".repeat(731));

        int reported = FabricSpatialSurvey.recordWireDetailsJsonBytes(details);

        assertEquals(reported, details.get("wire_details_json_bytes"));
        assertEquals(
            reported,
            JSON.toJson(details).getBytes(StandardCharsets.UTF_8).length
        );
    }

    @Test
    void trimsBoundedEvidenceWithExplicitCompletenessReceipts() {
        List<Map<String, Object>> columns = repeatedPayloads(20, 2_500);
        List<Map<String, Object>> anchors = repeatedPayloads(10, 1_000);
        List<Map<String, Object>> fireplaceCandidates = repeatedPayloads(10, 1_000);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("columns", columns);
        details.put("columns_complete", true);
        details.put("retained_column_count", columns.size());
        details.put("omitted_column_count", 0);
        details.put("anchors", anchors);
        details.put("anchors_complete", true);
        details.put("retained_anchor_count", anchors.size());
        details.put("omitted_anchor_count", 0);
        details.put("fireplace_candidates", fireplaceCandidates);
        details.put("fireplace_candidates_complete", true);
        details.put(
            "retained_fireplace_candidate_count",
            fireplaceCandidates.size()
        );
        details.put("omitted_fireplace_candidate_count", 0);

        FabricSpatialSurvey.enforceWireSize(
            details,
            columns,
            20,
            anchors,
            10,
            fireplaceCandidates,
            10
        );

        int actual = JSON.toJson(details).getBytes(StandardCharsets.UTF_8).length;
        assertTrue(actual <= FabricSpatialSurvey.MAX_DETAILS_JSON_BYTES);
        assertEquals(actual, details.get("wire_details_json_bytes"));
        assertFalse((Boolean) details.get("columns_complete"));
        assertEquals(20 - columns.size(), details.get("omitted_column_count"));
        assertTrue(
            !(Boolean) details.get("anchors_complete") ||
            !(Boolean) details.get("fireplace_candidates_complete")
        );
    }

    @Test
    void enumeratesNonAuthoritativeSafeBuildLinesByOrientationAndSide() {
        List<FabricSpatialSurvey.BuildSurface> surfaces = new ArrayList<>();
        for (int z = -2; z <= 2; z++) {
            surfaces.add(
                new FabricSpatialSurvey.BuildSurface(
                    new BlockPos(-3, 64, z),
                    4,
                    "minecraft:grass_block",
                    6
                )
            );
        }

        List<Map<String, Object>> candidates =
            FabricSpatialSurvey.enumerateBuildLineCandidates(
                surfaces,
                new BlockPos(0, 64, 0)
            );

        assertEquals(1, candidates.size());
        assertEquals("north_south", candidates.get(0).get("orientation"));
        assertEquals("west", candidates.get(0).get("relative_side"));
        assertEquals(5, candidates.get(0).get("length"));
        assertEquals(4, candidates.get(0).get("minimum_clear_height"));
        assertEquals(true, candidates.get(0).get("safe_candidate"));
        assertEquals(true, candidates.get(0).get("target_cells_air"));
        assertEquals(
            Map.of("x", -3, "y", 64, "z", -2),
            candidates.get(0).get("from")
        );
        assertEquals(
            Map.of("x", -3, "y", 64, "z", 2),
            candidates.get(0).get("to")
        );
    }

    @Test
    void doesNotAdvertiseReplaceableVegetationAsAirOnlyBuildClearance() {
        assertFalse(
            FabricSpatialSurvey.isStrictAirBuildCell(
                new FabricSpatialSurvey.Cell(
                    "minecraft:short_grass",
                    List.of("replaceable")
                )
            )
        );
        assertTrue(
            FabricSpatialSurvey.isStrictAirBuildCell(
                new FabricSpatialSurvey.Cell(
                    "minecraft:air",
                    List.of("air", "replaceable")
                )
            )
        );
    }

    @Test
    void rejectsFlammableStructureBlocksAsSafeBuildGround() {
        assertFalse(
            FabricSpatialSurvey.isSafeBuildGround(
                new FabricSpatialSurvey.Cell(
                    "minecraft:oak_planks",
                    List.of("solid", "flammable")
                )
            )
        );
        assertTrue(
            FabricSpatialSurvey.isSafeBuildGround(
                new FabricSpatialSurvey.Cell(
                    "minecraft:stone",
                    List.of("solid")
                )
            )
        );
    }

    @Test
    void enumeratesOnlyExactRequestedBuildWindows() {
        List<FabricSpatialSurvey.BuildSurface> surfaces = new ArrayList<>();
        for (int z = -4; z <= 4; z++) {
            surfaces.add(
                new FabricSpatialSurvey.BuildSurface(
                    new BlockPos(-3, 64, z),
                    4,
                    "minecraft:grass_block",
                    6
                )
            );
        }

        List<Map<String, Object>> candidates =
            FabricSpatialSurvey.enumerateBuildLineCandidates(
                surfaces,
                new BlockPos(0, 64, 0),
                5,
                "north_south",
                "west"
            );

        assertEquals(5, candidates.size());
        for (Map<String, Object> candidate : candidates) {
            assertEquals(5, candidate.get("length"));
            assertEquals("north_south", candidate.get("orientation"));
            assertEquals("west", candidate.get("relative_side"));
            Map<?, ?> from = (Map<?, ?>) candidate.get("from");
            Map<?, ?> to = (Map<?, ?>) candidate.get("to");
            assertEquals(
                4,
                ((Number) to.get("z")).intValue() -
                ((Number) from.get("z")).intValue()
            );
        }
    }

    @Test
    void verifiesAnExactInclusivePostActionFootprint() {
        Map<BlockPos, FabricSpatialSurvey.Cell> cells = new HashMap<>();
        for (int x = 1; x <= 5; x++) {
            for (int y = 64; y <= 66; y++) {
                cells.put(
                    new BlockPos(x, y, 1),
                    new FabricSpatialSurvey.Cell(
                        "minecraft:stone_bricks",
                        List.of("solid")
                    )
                );
            }
        }

        Map<String, Object> verification =
            FabricSpatialSurvey.verifyTargetGeometry(
                cells,
                new BlockPos(-7, 58, -7),
                new BlockPos(7, 72, 7),
                new BlockPos(1, 64, 1),
                new BlockPos(5, 66, 1),
                "stone_bricks"
            );

        assertEquals(15, verification.get("total_cells"));
        assertEquals(15, verification.get("matching_cells"));
        assertEquals(0, verification.get("mismatched_cells"));
        assertEquals(true, verification.get("complete"));
        assertEquals(true, verification.get("all_match"));
        assertEquals("minecraft:stone_bricks", verification.get("expected_block"));
    }

    @Test
    void exactFootprintVerificationFailsClosedForMismatchOrMissingCoverage() {
        Map<BlockPos, FabricSpatialSurvey.Cell> cells = new HashMap<>();
        cells.put(
            new BlockPos(1, 64, 1),
            new FabricSpatialSurvey.Cell("minecraft:dirt", List.of("solid"))
        );

        Map<String, Object> verification =
            FabricSpatialSurvey.verifyTargetGeometry(
                cells,
                new BlockPos(0, 60, 0),
                new BlockPos(1, 65, 1),
                new BlockPos(1, 64, 1),
                new BlockPos(2, 64, 1),
                "minecraft:stone_bricks"
            );

        assertEquals(2, verification.get("total_cells"));
        assertEquals(1, verification.get("mismatched_cells"));
        assertEquals(1, verification.get("unobserved_cells"));
        assertEquals(false, verification.get("within_survey_bounds"));
        assertEquals(false, verification.get("complete"));
        assertEquals(false, verification.get("all_match"));
        assertEquals(1, ((List<?>) verification.get("mismatch_samples")).size());
    }

    private static List<Map<String, Object>> repeatedPayloads(
        int count,
        int payloadLength
    ) {
        List<Map<String, Object>> values = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            values.add(
                Map.<String, Object>of(
                    "index",
                    index,
                    "payload",
                    "x".repeat(payloadLength)
                )
            );
        }
        return values;
    }
}
