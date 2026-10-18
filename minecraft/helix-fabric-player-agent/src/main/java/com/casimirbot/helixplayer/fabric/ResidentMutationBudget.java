package com.casimirbot.helixplayer.fabric;

import java.util.List;
import java.util.Map;

/** Reserves declared graph maxima across handoffs; not measured effect proof.
 * Reservations are not refunded after uncertain execution or cancellation. */
final class ResidentMutationBudget {
    private final Map<?, ?> root;
    private long worldReserved;
    private long inventoryReserved;

    ResidentMutationBudget(Map<String, Object> initial) {
        root = Map.copyOf(map(initial.get("mutation_scope")));
        long[] cost = cost(initial);
        worldReserved = cost[0]; inventoryReserved = cost[1];
    }

    boolean reserve(Map<String, Object> successor) {
        Map<?, ?> scope = map(successor.get("mutation_scope"));
        long[] cost = cost(successor);
        if (cost[0] > count(scope.get("max_block_mutations")) || cost[1] > count(scope.get("max_inventory_transfers")) ||
            cost[0] > count(root.get("max_block_mutations")) - worldReserved ||
            cost[1] > count(root.get("max_inventory_transfers")) - inventoryReserved ||
            (Boolean.TRUE.equals(scope.get("world_mutation_allowed")) && !Boolean.TRUE.equals(root.get("world_mutation_allowed"))) ||
            !list(root.get("allowed_block_ids")).containsAll(list(scope.get("allowed_block_ids")))) return false;
        List<?> rootRegions = list(root.get("allowed_regions"));
        List<?> regions = list(scope.get("allowed_regions"));
        if (!rootRegions.isEmpty() && (regions.isEmpty() || regions.stream().anyMatch(region ->
            rootRegions.stream().noneMatch(outer -> contains(map(outer), map(region)))))) return false;
        worldReserved += cost[0]; inventoryReserved += cost[1];
        return true;
    }

    boolean admitsObserved(long world, long inventory) {
        return world >= 0 && inventory >= 0 && world <= count(root.get("max_block_mutations")) &&
            inventory <= count(root.get("max_inventory_transfers"));
    }

    private static long[] cost(Map<String, Object> sequence) {
        long world = 0, inventory = 0;
        for (Object value : list(sequence.get("nodes"))) {
            Map<?, ?> node = map(value);
            if (!"workflow_action".equals(node.get("node_kind"))) continue;
            Map<?, ?> action = map(node.get("action"));
            String kind = String.valueOf(action.get("action_kind"));
            long amount = switch (kind) {
                case "mine", "collect", "craft", "consume", "inventory_transfer" -> count(action.get("count"));
                case "place" -> action.get("positions") instanceof List<?> positions ? positions.size() : 1;
                case "equip" -> 1;
                default -> 0;
            };
            if ("mine".equals(kind) || "place".equals(kind)) world = Math.addExact(world, amount);
            inventory = Math.addExact(inventory, amount);
        }
        return new long[]{world, inventory};
    }

    private static boolean contains(Map<?, ?> outer, Map<?, ?> inner) {
        Map<?, ?> low = map(outer.get("min")), high = map(outer.get("max"));
        Map<?, ?> innerLow = map(inner.get("min")), innerHigh = map(inner.get("max"));
        for (String axis : List.of("x", "y", "z")) {
            double min = coordinate(low.get(axis)), max = coordinate(high.get(axis));
            double candidateMin = coordinate(innerLow.get(axis)), candidateMax = coordinate(innerHigh.get(axis));
            if (min > max || candidateMin > candidateMax || candidateMin < min || candidateMax > max) return false;
        }
        return true;
    }
    private static double coordinate(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) throw new IllegalArgumentException("resident_mutation_budget_invalid");
        return number.doubleValue();
    }
    private static long count(Object value) {
        double number = coordinate(value);
        if (number < 0 || number > 9_007_199_254_740_991D || number != Math.rint(number)) throw new IllegalArgumentException("resident_mutation_budget_invalid");
        return (long) number;
    }
    private static Map<?, ?> map(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("resident_mutation_budget_invalid");
        return map;
    }
    private static List<?> list(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("resident_mutation_budget_invalid");
        return list;
    }
}
