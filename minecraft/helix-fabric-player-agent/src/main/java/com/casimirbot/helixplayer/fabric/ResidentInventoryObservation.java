package com.casimirbot.helixplayer.fabric;

import java.util.Map;

/** Separate sensor lower bound, never added to overlapping action receipts.
 * Does not attribute changes to pickups or claim to see between-sample effects. */
final class ResidentInventoryObservation {
    private InventoryCountObservation previous;
    private long lowerBound;
    private boolean gap;

    ResidentInventoryObservation(InventoryCountObservation initial) { previous = initial; }

    void observe(InventoryCountObservation current) {
        if (previous.available() && !current.available()) gap = true;
        if (previous.available() && current.available()) {
            long gained = 0, lost = 0;
            for (int delta : current.deltaFrom(previous).values()) {
                if (delta > 0) gained = Math.addExact(gained, delta);
                else lost = Math.addExact(lost, -(long) delta);
            }
            lowerBound = Math.addExact(lowerBound, Math.max(gained, lost));
        }
        previous = current;
    }

    boolean gap() { return gap; }
    long lowerBound() { return lowerBound; }
    Map<String, Object> evidence() {
        return Map.of("available", previous.available(), "gap_detected", gap,
            "item_count_change_lower_bound", lowerBound, "action_attribution", "unestablished",
            "between_sample_effects_observed", false);
    }
}
