package com.casimirbot.helixplayer.fabric;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Arithmetic over native plan-qualified observations, not effect authority. */
final class ResidentEffectMeasurements {
    /** A native total and its descriptive counters are alternate views, not
     * additive effects. Older workflows expose only the descriptive counters. */
    static int settledInventoryCount(Map<String, Object> measurements) {
        if (measurements.containsKey("inventory_mutations_performed"))
            return count(measurements.get("inventory_mutations_performed"));
        int total = 0;
        for (String key : List.of("collected_count", "produced_count", "transferred_count"))
            total = Math.addExact(total, count(measurements.getOrDefault(key, 0)));
        Object consumed = measurements.containsKey("consumed_item_count")
            ? measurements.get("consumed_item_count") : measurements.getOrDefault("consumed_count", 0);
        return Math.addExact(total, count(consumed));
    }

    private static int count(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) ||
            number.doubleValue() < 0 || number.doubleValue() != Math.rint(number.doubleValue()) ||
            number.doubleValue() > Integer.MAX_VALUE) throw new IllegalArgumentException("resident_effect_measurement_invalid");
        return number.intValue();
    }

    static Map<String, Object> totals(Map<String, Object> current, List<?> history) {
        if (history.size() > 31) throw new IllegalArgumentException("resident_effect_history_invalid");
        List<Object> samples = new ArrayList<>(history);
        samples.add(current);
        Map<String, Object> totals = new LinkedHashMap<>();
        for (String key : List.of("player_motion_performed", "player_interaction_performed", "inventory_mutation_performed")) {
            boolean any = false;
            for (Object sample : samples) {
                if (!(sample instanceof Map<?, ?> map) || !(map.get(key) instanceof Boolean value))
                    throw new IllegalArgumentException("resident_effect_measurement_invalid");
                any |= value;
            }
            totals.put(key, any);
        }
        for (String key : List.of("world_mutations_performed", "inventory_mutations_performed")) {
            long total = 0;
            for (Object sample : samples) {
                Object value = ((Map<?, ?>) sample).get(key);
                if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) ||
                    number.doubleValue() < 0 || number.doubleValue() != Math.rint(number.doubleValue()) ||
                    number.doubleValue() > 9_007_199_254_740_991D) throw new IllegalArgumentException("resident_effect_measurement_invalid");
                total = Math.addExact(total, number.longValue());
                if (total > 9_007_199_254_740_991L) throw new IllegalArgumentException("resident_effect_total_overflow");
            }
            totals.put(key, total);
        }
        totals.put("world_mutation_performed", (long) totals.get("world_mutations_performed") > 0);
        totals.put("inventory_mutation_performed", Boolean.TRUE.equals(totals.get("inventory_mutation_performed")) ||
            (long) totals.get("inventory_mutations_performed") > 0);
        totals.put("side_effects_performed", Boolean.TRUE.equals(totals.get("player_motion_performed")) ||
            Boolean.TRUE.equals(totals.get("player_interaction_performed")) ||
            Boolean.TRUE.equals(totals.get("inventory_mutation_performed")) ||
            Boolean.TRUE.equals(totals.get("world_mutation_performed")));
        return Map.copyOf(totals);
    }

    static Map<String, Object> resultView(Map<String, Object> measurements) {
        if (!measurements.containsKey("completed_sequence_measurements")) return measurements;
        if (!(measurements.get("completed_sequence_measurements") instanceof List<?> history))
            throw new IllegalArgumentException("resident_effect_history_invalid");
        Map<String, Object> result = new LinkedHashMap<>(measurements);
        result.putAll(totals(measurements, history));
        // Optional observation fields may be null; effect counters were validated above.
        return java.util.Collections.unmodifiableMap(result);
    }
}
