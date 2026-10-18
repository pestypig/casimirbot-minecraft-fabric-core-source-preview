package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.*;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ResidentEffectMeasurementsTest {
    @Test
    void nativeInventoryTotalsIncludePlacementAndConsumptionWithoutAliasDuplication() {
        assertEquals(2, ResidentEffectMeasurements.settledInventoryCount(Map.of("inventory_mutations_performed", 2)));
        assertEquals(2, ResidentEffectMeasurements.settledInventoryCount(Map.of(
            "inventory_mutations_performed", 2, "consumed_item_count", 2, "consumed_count", 2)));
        assertEquals(7, ResidentEffectMeasurements.settledInventoryCount(Map.of(
            "collected_count", 1, "produced_count", 2, "transferred_count", 3, "consumed_count", 1)));
        assertEquals(1, ResidentEffectMeasurements.settledInventoryCount(Map.of("consumed_item_count", 1, "consumed_count", 1)));
        assertEquals(0, ResidentEffectMeasurements.settledInventoryCount(Map.of()));
    }

    @Test
    void invalidExplicitInventoryTotalCannotFallBackToAnApparentlyValidAlias() {
        for (Object invalid : List.of(-1, 1.5, Double.NaN, "2", 2147483648L)) {
            assertThrows(IllegalArgumentException.class, () -> ResidentEffectMeasurements.settledInventoryCount(
                Map.of("inventory_mutations_performed", invalid, "collected_count", 2)));
        }
        assertThrows(ArithmeticException.class, () -> ResidentEffectMeasurements.settledInventoryCount(
            Map.of("produced_count", Integer.MAX_VALUE, "collected_count", 1)));
    }

    private static Map<String, Object> sample(boolean motion, long blocks, long inventory) {
        return Map.of("player_motion_performed", motion, "player_interaction_performed", false,
            "inventory_mutation_performed", inventory > 0, "world_mutations_performed", blocks,
            "inventory_mutations_performed", inventory);
    }

    @Test
    void quietFinalPlanPreservesEarlierEffectsWithoutMutatingPlanEvidence() {
        Map<String, Object> current = new LinkedHashMap<>(sample(false, 0, 0));
        current.put("completed_sequence_measurements", List.of(sample(true, 2, 3), sample(false, 1, 4)));
        Map<String, Object> result = ResidentEffectMeasurements.resultView(current);
        assertEquals(true, result.get("player_motion_performed"));
        assertEquals(3L, result.get("world_mutations_performed"));
        assertEquals(7L, result.get("inventory_mutations_performed"));
        assertEquals(false, current.get("player_motion_performed"));
        assertTrue(PlayerActionRuntime.effectExecutionPerformed(true, true, result));
        assertFalse(PlayerActionRuntime.effectExecutionPerformed(false, true, result));
        Map<String, Object> worldOnly = new LinkedHashMap<>(sample(false, 0, 0));
        worldOnly.put("completed_sequence_measurements", List.of(sample(false, 1, 0)));
        assertTrue(PlayerActionRuntime.effectExecutionPerformed(true, true, ResidentEffectMeasurements.resultView(worldOnly)));
    }

    @Test
    void nullableObservationsSurviveSettlementWithoutRelaxingEffectValidation() {
        Map<String, Object> current = new LinkedHashMap<>(sample(false, 0, 0));
        current.put("optional_observation", null);
        current.put("completed_sequence_measurements", List.of(sample(true, 0, 0)));
        Map<String, Object> result = ResidentEffectMeasurements.resultView(current);
        assertTrue(result.containsKey("optional_observation"));
        assertNull(result.get("optional_observation"));
        assertEquals(true, result.get("player_motion_performed"));
        assertThrows(UnsupportedOperationException.class, () -> result.put("extra", true));
        current.put("optional_observation", "later");
        assertNull(result.get("optional_observation"));
        current.put("world_mutations_performed", null);
        assertThrows(IllegalArgumentException.class, () -> ResidentEffectMeasurements.resultView(current));
    }

    @Test
    void malformedOrOverflowingCountersDoNotBecomeZero() {
        for (Object invalid : List.of(-1, 1.5, Double.NaN, "2")) {
            Map<String, Object> bad = new LinkedHashMap<>(sample(false, 0, 0));
            bad.put("world_mutations_performed", invalid);
            assertThrows(IllegalArgumentException.class, () -> ResidentEffectMeasurements.totals(bad, List.of()));
        }
        assertThrows(IllegalArgumentException.class, () -> ResidentEffectMeasurements.totals(sample(false, 1, 0),
            List.of(sample(false, 9_007_199_254_740_991L, 0))));
        assertThrows(IllegalArgumentException.class, () -> ResidentEffectMeasurements.totals(sample(false, 0, 0), List.of(Map.of())));
    }
}
