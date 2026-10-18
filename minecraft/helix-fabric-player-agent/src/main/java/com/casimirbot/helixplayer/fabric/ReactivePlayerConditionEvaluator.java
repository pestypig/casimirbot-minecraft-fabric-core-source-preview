package com.casimirbot.helixplayer.fabric;

import java.util.Map;

/**
 * Pure evaluator for reactive predicates that depend only on the current player state.
 *
 * <p>The native bridge supplies the observation. Returning {@code null} means that the
 * condition belongs to a different evidence source (inventory, world, recipe, or the
 * scheduler itself) and must be evaluated there.</p>
 */
final class ReactivePlayerConditionEvaluator {
    record PlayerState(
        double health,
        int food,
        int air,
        boolean submerged,
        boolean swimming,
        boolean onFire,
        boolean inLava,
        boolean onGround,
        double x,
        double y,
        double z
    ) {}

    private ReactivePlayerConditionEvaluator() {}

    static Boolean evaluate(Map<String, Object> condition, PlayerState state) {
        Object kindValue = condition.get("condition_kind");
        if (!(kindValue instanceof String kind)) return false;
        return switch (kind) {
            case "health_at_least" ->
                state.health() >= number(condition.get("health"), Double.MAX_VALUE);
            case "air_at_least" ->
                state.air() >= integer(condition.get("air"), Integer.MAX_VALUE);
            case "submerged_is" ->
                state.submerged() == bool(condition.get("expected"));
            case "swimming_is" ->
                state.swimming() == bool(condition.get("expected"));
            case "on_fire_is" ->
                state.onFire() == bool(condition.get("expected"));
            case "in_lava_is" ->
                state.inLava() == bool(condition.get("expected"));
            case "food_at_least" ->
                state.food() >= integer(condition.get("food"), Integer.MAX_VALUE);
            case "player_grounded" ->
                state.onGround() == bool(condition.get("expected"));
            case "position_within" -> {
                Map<String, Object> position = object(condition.get("position"));
                double targetX = number(position.get("x"), Double.NaN);
                double targetY = number(position.get("y"), Double.NaN);
                double targetZ = number(position.get("z"), Double.NaN);
                double radius = number(condition.get("radius"), -1);
                if (!Double.isFinite(targetX) || !Double.isFinite(targetY) ||
                    !Double.isFinite(targetZ) || !Double.isFinite(radius) || radius < 0) {
                    yield false;
                }
                double dx = state.x() - targetX;
                double dy = state.y() - targetY;
                double dz = state.z() - targetZ;
                yield dx * dx + dy * dy + dz * dz <= radius * radius;
            }
            default -> null;
        };
    }

    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) return Map.of();
        java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() instanceof String key && entry.getValue() != null) {
                result.put(key, entry.getValue());
            }
        }
        return Map.copyOf(result);
    }

    private static double number(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    private static int integer(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean bool(Object value) {
        return value instanceof Boolean flag && flag;
    }
}
