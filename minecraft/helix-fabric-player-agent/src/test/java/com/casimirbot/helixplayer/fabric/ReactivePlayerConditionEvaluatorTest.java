package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class ReactivePlayerConditionEvaluatorTest {
    private static final ReactivePlayerConditionEvaluator.PlayerState PLAYER =
        new ReactivePlayerConditionEvaluator.PlayerState(
            20, 17, 250, true, true, false, false, true, 10, 64, -5
        );

    @Test
    void evaluatesHealthFoodAndGroundedFromTheSameObservedPlayerState() {
        assertEquals(true, ReactivePlayerConditionEvaluator.evaluate(
            Map.of("condition_kind", "health_at_least", "health", 6),
            PLAYER
        ));
        assertEquals(false, ReactivePlayerConditionEvaluator.evaluate(
            Map.of("condition_kind", "health_at_least", "health", 20.1),
            PLAYER
        ));
        assertEquals(true, ReactivePlayerConditionEvaluator.evaluate(
            Map.of("condition_kind", "food_at_least", "food", 17),
            PLAYER
        ));
        assertEquals(true, ReactivePlayerConditionEvaluator.evaluate(
            Map.of("condition_kind", "player_grounded", "expected", true),
            PLAYER
        ));
    }

    @Test
    void evaluatesAirSubmersionSwimmingFireAndLavaFromOneObservation() {
        assertEquals(true, ReactivePlayerConditionEvaluator.evaluate(
            Map.of("condition_kind", "air_at_least", "air", 200), PLAYER
        ));
        assertEquals(true, ReactivePlayerConditionEvaluator.evaluate(
            Map.of("condition_kind", "submerged_is", "expected", true), PLAYER
        ));
        assertEquals(true, ReactivePlayerConditionEvaluator.evaluate(
            Map.of("condition_kind", "swimming_is", "expected", true), PLAYER
        ));
        assertEquals(true, ReactivePlayerConditionEvaluator.evaluate(
            Map.of("condition_kind", "on_fire_is", "expected", false), PLAYER
        ));
        assertEquals(true, ReactivePlayerConditionEvaluator.evaluate(
            Map.of("condition_kind", "in_lava_is", "expected", false), PLAYER
        ));
    }

    @Test
    void evaluatesBoundedPositionAndRejectsMalformedThresholds() {
        assertEquals(true, ReactivePlayerConditionEvaluator.evaluate(
            Map.of(
                "condition_kind", "position_within",
                "position", Map.of("x", 11, "y", 64, "z", -5),
                "radius", 1
            ),
            PLAYER
        ));
        assertEquals(false, ReactivePlayerConditionEvaluator.evaluate(
            Map.of("condition_kind", "health_at_least"),
            PLAYER
        ));
        assertNull(ReactivePlayerConditionEvaluator.evaluate(
            Map.of("condition_kind", "inventory_count_at_least"),
            PLAYER
        ));
    }
}
