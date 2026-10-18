package com.casimirbot.helixsensor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class FabricProbeResultShapeTest {
    @Test
    void reachabilityUsesOnlyLegacyProbeEnvelopeFields() {
        Map<String, Object> result =
            FabricProbeExecutor.reachabilityResult(4.5d, true);

        assertEquals(
            Set.of(
                "feasible",
                "reachable",
                "distance_blocks",
                "confidence"
            ),
            result.keySet()
        );
        assertEquals(true, result.get("feasible"));
        assertEquals(true, result.get("reachable"));
        assertEquals(4.5d, result.get("distance_blocks"));
        assertFalse(result.containsKey("limitation"));
        assertTrue((double) result.get("confidence") <= 1.0d);
    }

    @Test
    void buildPlanningSummaryFailsClosedWhenNoStrictCandidateWasVerified() {
        String summary = FabricProbeExecutor.spatialRegionSummary(
            "structure_planning",
            Map.of("build_line_candidates", List.of())
        );

        assertTrue(summary.contains("No strictly air-filled"));
        assertTrue(summary.contains("Do not infer exact build endpoints"));
    }

    @Test
    void buildPlanningSummaryNamesVerifiedCandidateAuthority() {
        String summary = FabricProbeExecutor.spatialRegionSummary(
            "build_planning",
            Map.of(
                "build_line_candidates",
                List.of(Map.of("from", "a"), Map.of("from", "b"))
            )
        );

        assertTrue(summary.contains("verified 2"));
        assertTrue(summary.contains("use only build_line_candidates"));
    }

    @Test
    void generalSpatialSummaryRemainsNeutral() {
        assertEquals(
            "Bounded spatial-region read-only probe completed.",
            FabricProbeExecutor.spatialRegionSummary("general", Map.of())
        );
    }

    @Test
    void structureVerificationSummaryDistinguishesMatchMismatchAndMissingEvidence() {
        assertTrue(
            FabricProbeExecutor.spatialRegionSummary(
                "structure_verification",
                Map.of(
                    "target_geometry_verification",
                    Map.of(
                        "complete", true,
                        "all_match", true,
                        "total_cells", 15,
                        "mismatched_cells", 0,
                        "expected_block", "minecraft:stone_bricks"
                    )
                )
            ).contains("confirmed all 15")
        );
        assertTrue(
            FabricProbeExecutor.spatialRegionSummary(
                "structure_verification",
                Map.of(
                    "target_geometry_verification",
                    Map.of(
                        "complete", true,
                        "all_match", false,
                        "total_cells", 15,
                        "mismatched_cells", 2,
                        "expected_block", "minecraft:stone_bricks"
                    )
                )
            ).contains("2 mismatched")
        );
        assertTrue(
            FabricProbeExecutor.spatialRegionSummary(
                "structure_verification",
                Map.of()
            ).contains("Do not claim")
        );
    }
}
