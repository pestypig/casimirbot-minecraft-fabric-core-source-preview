package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ResidentMutationBudgetTest {
    @Test void observedTotalsCannotExceedTheRootEvenWhenReservationsFit() {
        var budget = new ResidentMutationBudget(graph("collect", 1, 3, 2, List.of()));
        assertTrue(budget.admitsObserved(3, 2));
        assertFalse(budget.admitsObserved(4, 2));
        assertFalse(budget.admitsObserved(3, 3));
        assertFalse(budget.admitsObserved(-1, 0));
        assertFalse(budget.admitsObserved(0, -1));
    }
    @Test void reservesAcrossPlansAndDoesNotConsumeRejectedReservations() {
        var budget = new ResidentMutationBudget(graph("mine", 1, 3, 3, List.of()));
        assertTrue(budget.reserve(graph("mine", 1, 3, 3, List.of())));
        assertFalse(budget.reserve(graph("mine", 2, 3, 3, List.of())));
        assertTrue(budget.reserve(graph("mine", 1, 3, 3, List.of())));
        assertFalse(budget.reserve(graph("mine", 1, 3, 3, List.of())));
    }
    @Test void separatesInventoryBudgetAndRequiresContainedRegions() {
        Map<String, Object> region = Map.of("min", Map.of("x", 0, "y", 0, "z", 0), "max", Map.of("x", 10, "y", 10, "z", 10));
        var budget = new ResidentMutationBudget(graph("collect", 1, 3, 2, List.of(region)));
        assertFalse(budget.reserve(graph("collect", 1, 3, 2, List.of())));
        assertTrue(budget.reserve(graph("collect", 1, 3, 2, List.of(region))));
        assertFalse(budget.reserve(graph("collect", 1, 3, 2, List.of(region))));
    }
    @Test void neverAcceptsNegativeOrFractionalDeclaredCounts() {
        assertThrows(IllegalArgumentException.class, () -> new ResidentMutationBudget(graph("mine", -1, 3, 3, List.of())));
        assertThrows(IllegalArgumentException.class, () -> new ResidentMutationBudget(graph("mine", 0.5, 3, 3, List.of())));
    }
    private static Map<String, Object> graph(String kind, Number count, int blocks, int items, List<?> regions) {
        return Map.of("mutation_scope", Map.of("world_mutation_allowed", true, "max_block_mutations", blocks,
            "max_inventory_transfers", items, "allowed_block_ids", List.of("minecraft:stone"), "allowed_regions", regions),
            "nodes", List.of(Map.of("node_kind", "workflow_action", "action", Map.of("action_kind", kind, "count", count))));
    }
}
