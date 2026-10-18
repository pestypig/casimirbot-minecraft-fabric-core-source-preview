package com.casimirbot.helixplayer.fabric;

import java.util.Map;

/** Clock-only decoding. Not source-plan/hash/task admission and not dispatch. */
final class TemporalPlanClockBinding {
    record BoundWindow(TemporalPlanWindow window, long executionOffsetTicks) {}

    static BoundWindow decode(Map<String, Object> plan, String producerEpoch, String clockOrigin,
                              long currentClientTick, double currentElapsedMs) {
        Map<?, ?> identity = object(plan.get("identity"));
        Map<?, ?> clocks = object(plan.get("clocks"));
        Map<?, ?> environment = object(clocks.get("environment"));
        Map<?, ?> monotonic = object(clocks.get("monotonic"));
        Map<?, ?> watermarks = object(plan.get("watermarks"));
        if (!producerEpoch.equals(identity.get("producer_epoch")) ||
            !"tick".equals(environment.get("kind")) ||
            !"minecraft_tick".equals(environment.get("resolution_unit")) ||
            !clockOrigin.equals(monotonic.get("origin_id"))) throw invalid();
        long sourceTick = integer(environment.get("sequence"));
        double sourceElapsed = finite(monotonic.get("elapsed_ms"));
        double deadline = finite(plan.get("monotonic_deadline_elapsed_ms"));
        if (currentClientTick < sourceTick || !Double.isFinite(currentElapsedMs) ||
            sourceElapsed > currentElapsedMs || deadline <= currentElapsedMs) throw invalid();
        long stop = integer(watermarks.get("stop_unit"));
        long offset = currentClientTick - sourceTick;
        if (offset >= stop) throw invalid();
        return new BoundWindow(new TemporalPlanWindow(clockOrigin, sourceTick,
            integer(watermarks.get("decision_unit")), stop,
            integer(watermarks.get("committed_through_unit")), integer(plan.get("maximum_total_units")), deadline), offset);
    }

    private static Map<?, ?> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) throw invalid();
        return map;
    }
    private static double finite(Object value) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() < 0) throw invalid();
        return number.doubleValue();
    }
    private static long integer(Object value) {
        double number = finite(value);
        if (number > 9_007_199_254_740_991D || number != Math.rint(number)) throw invalid();
        return (long) number;
    }
    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("temporal_plan_clock_mismatch");
    }
}
