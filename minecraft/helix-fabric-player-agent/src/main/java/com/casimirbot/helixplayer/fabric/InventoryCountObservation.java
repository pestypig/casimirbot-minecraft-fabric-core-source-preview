package com.casimirbot.helixplayer.fabric;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;

/** Count observations only: a delta does not identify its cause or grant effects. */
record InventoryCountObservation(boolean available, Map<String, Integer> counts) {
    InventoryCountObservation {
        counts = Map.copyOf(counts);
        if ((!available && !counts.isEmpty()) || counts.entrySet().stream().anyMatch(entry ->
            entry.getKey().isBlank() || entry.getValue() < 0))
            throw new IllegalArgumentException("inventory_count_observation_invalid");
    }

    static InventoryCountObservation unavailable() { return new InventoryCountObservation(false, Map.of()); }

    Map<String, Integer> deltaFrom(InventoryCountObservation previous) {
        if (!available || !previous.available) throw new IllegalStateException("inventory_count_observation_unavailable");
        var keys = new HashSet<>(previous.counts.keySet());
        keys.addAll(counts.keySet());
        var deltas = new LinkedHashMap<String, Integer>();
        keys.stream().sorted().forEach(key -> {
            int delta = Math.subtractExact(counts.getOrDefault(key, 0), previous.counts.getOrDefault(key, 0));
            if (delta != 0) deltas.put(key, delta);
        });
        return Map.copyOf(deltas);
    }
}
