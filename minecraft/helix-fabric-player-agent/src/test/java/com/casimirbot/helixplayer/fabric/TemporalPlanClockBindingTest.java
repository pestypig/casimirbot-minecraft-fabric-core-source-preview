package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.*;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class TemporalPlanClockBindingTest {
    private Map<String, Object> plan(String unit, Object tick) {
        return new LinkedHashMap<>(Map.of("identity", Map.of("producer_epoch", "epoch"),
            "clocks", Map.of("environment", Map.of("kind", "tick", "resolution_unit", unit, "sequence", tick),
                "monotonic", Map.of("origin_id", "origin", "elapsed_ms", 100)),
            "watermarks", Map.of("decision_unit", 3, "stop_unit", 6, "committed_through_unit", 8),
            "maximum_total_units", 10, "monotonic_deadline_elapsed_ms", 1000));
    }
    @Test void preservesDeliveryDelayAsExecutionOffset() {
        var bound = TemporalPlanClockBinding.decode(plan("minecraft_tick", 100), "epoch", "origin", 102, 200);
        assertEquals(2, bound.executionOffsetTicks());
        assertEquals(4, bound.window().observe("origin", 102, 200).remainingTicks());
    }
    @Test void refusesWorldClockSubstitutionAndWrongEpochOrOrigin() {
        assertThrows(IllegalArgumentException.class, () -> TemporalPlanClockBinding.decode(plan("minecraft_world_tick", 100), "epoch", "origin", 102, 200));
        assertThrows(IllegalArgumentException.class, () -> TemporalPlanClockBinding.decode(plan("minecraft_tick", 100), "other", "origin", 102, 200));
        assertThrows(IllegalArgumentException.class, () -> TemporalPlanClockBinding.decode(plan("minecraft_tick", 100), "epoch", "other", 102, 200));
    }
    @Test void refusesExpiredFutureAndMalformedClocks() {
        for (Object tick : new Object[]{103, 100.5, "100", Double.NaN}) {
            assertThrows(IllegalArgumentException.class, () -> TemporalPlanClockBinding.decode(plan("minecraft_tick", tick), "epoch", "origin", 102, 200));
        }
        assertThrows(IllegalArgumentException.class, () -> TemporalPlanClockBinding.decode(plan("minecraft_tick", 100), "epoch", "origin", 106, 300));
        assertThrows(IllegalArgumentException.class, () -> TemporalPlanClockBinding.decode(plan("minecraft_tick", 100), "epoch", "origin", 102, 1000));
    }
}
