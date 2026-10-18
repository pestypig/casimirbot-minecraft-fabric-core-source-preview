package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class InventoryCountObservationTest {
    @Test void distinguishesUnknownFromObservedEmpty() {
        var empty = new InventoryCountObservation(true, Map.of());
        assertTrue(empty.available());
        assertFalse(InventoryCountObservation.unavailable().available());
        assertThrows(IllegalStateException.class, () -> empty.deltaFrom(InventoryCountObservation.unavailable()));
    }
    @Test void recordsGainsAndLossesWithoutCallingEitherAPickup() {
        var before = new InventoryCountObservation(true, Map.of("stone", 4, "apple", 1));
        var after = new InventoryCountObservation(true, Map.of("stone", 6, "wood", 2));
        assertEquals(Map.of("stone", 2, "apple", -1, "wood", 2), after.deltaFrom(before));
        assertEquals(Map.of(), after.deltaFrom(after));
    }
    @Test void snapshotsAreImmutableAndInvalidCountsAreRejected() {
        var source = new LinkedHashMap<>(Map.of("stone", 1));
        var snapshot = new InventoryCountObservation(true, source);
        source.put("stone", 3);
        assertEquals(1, snapshot.counts().get("stone"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.counts().put("stone", 2));
        assertThrows(IllegalArgumentException.class, () -> new InventoryCountObservation(true, Map.of("stone", -1)));
        assertThrows(IllegalArgumentException.class, () -> new InventoryCountObservation(false, Map.of("stone", 1)));
    }
}
