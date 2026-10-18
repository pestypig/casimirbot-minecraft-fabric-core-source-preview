package com.casimirbot.helixplayer.fabric;
import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ResidentInventoryObservationTest {
    @Test void countsRepeatedSamplesOnceAndPreservesChangesAcrossIntervals() {
        var observation = new ResidentInventoryObservation(new InventoryCountObservation(true, Map.of()));
        var gained = new InventoryCountObservation(true, Map.of("stone", 2));
        observation.observe(gained);
        observation.observe(gained);
        assertEquals(2, observation.lowerBound());
        observation.observe(new InventoryCountObservation(true, Map.of()));
        assertEquals(4, observation.lowerBound());
        assertEquals("unestablished", observation.evidence().get("action_attribution"));
    }
    @Test void aSensorGapIsStickyAndDoesNotInventAnEmptyInventory() {
        var observation = new ResidentInventoryObservation(new InventoryCountObservation(true, Map.of("stone", 2)));
        observation.observe(InventoryCountObservation.unavailable());
        observation.observe(new InventoryCountObservation(true, Map.of("stone", 3)));
        assertTrue(observation.gap());
        assertEquals(0, observation.lowerBound());
    }
}
