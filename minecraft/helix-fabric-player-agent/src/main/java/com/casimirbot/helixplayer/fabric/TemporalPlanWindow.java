package com.casimirbot.helixplayer.fabric;

/** Tick-local boundary check for an admitted window. This class neither admits
 * plans nor applies controls. The runtime must stabilize on a stop result;
 * delivery and controller integration are required before advertising support. */
final class TemporalPlanWindow {
    record Observation(boolean decisionRequired, boolean mustStabilize, String reason, long remainingTicks) {}
    private final String clockOrigin;
    private final long startTick;
    private final long decisionUnit;
    private final long stopUnit;
    private final long committedThroughUnit;
    private boolean handoffReserved;
    private final double deadlineMs;
    private final long maximumUnits;
    private long lastTick;
    private double lastElapsedMs = -1;
    private boolean decisionEmitted;
    private String stoppedReason;

    TemporalPlanWindow(String clockOrigin, long startTick, long decisionUnit, long stopUnit,
                       long committedThroughUnit, long maximumUnits, double deadlineMs) {
        if (clockOrigin == null || clockOrigin.isBlank() || startTick < 0 || decisionUnit < 0 ||
            decisionUnit > stopUnit || stopUnit > committedThroughUnit || committedThroughUnit > maximumUnits ||
            maximumUnits <= 0 || maximumUnits > 36_000 || !Double.isFinite(deadlineMs) || deadlineMs < 0) {
            throw new IllegalArgumentException("temporal_window_invalid");
        }
        this.clockOrigin = clockOrigin;
        this.startTick = startTick;
        this.lastTick = startTick;
        this.decisionUnit = decisionUnit;
        this.stopUnit = stopUnit;
        this.committedThroughUnit = committedThroughUnit;
        this.deadlineMs = deadlineMs;
        this.maximumUnits = maximumUnits;
    }

    Observation observe(String origin, long tick, double elapsedMs) {
        if (stoppedReason != null) return new Observation(false, true, stoppedReason, 0);
        if (!clockOrigin.equals(origin) || tick < lastTick || !Double.isFinite(elapsedMs) ||
            elapsedMs < 0 || elapsedMs < lastElapsedMs) return stop("temporal_clock_invalid");
        lastTick = tick;
        lastElapsedMs = elapsedMs;
        long relative = tick - startTick;
        if (elapsedMs >= deadlineMs) return stop("temporal_monotonic_deadline");
        long effectiveStop = handoffReserved ? committedThroughUnit : stopUnit;
        if (relative >= effectiveStop) return stop("temporal_runway_exhausted");
        boolean decision = !decisionEmitted && relative >= decisionUnit;
        decisionEmitted |= decision;
        return new Observation(decision, false, decision ? "temporal_runway_low" : "temporal_window_current", effectiveStop - relative);
    }

    long sourceStartTick() { return startTick; }
    long stopTick() { return Math.addExact(startTick, stopUnit); }
    long committedTick() { return Math.addExact(startTick, committedThroughUnit); }

    boolean canReserve(TemporalPlanWindow next, String origin, long tick, double elapsedMs) {
        return !handoffReserved && stoppedReason == null && next != null && next != this &&
            validClock(origin, tick, elapsedMs) && tick < stopTick() &&
            clockOrigin.equals(next.clockOrigin) && next.stoppedReason == null && !next.handoffReserved &&
            next.startTick <= committedTick() && next.stopTick() > committedTick() && elapsedMs < next.deadlineMs;
    }

    void reserveHandoff() { handoffReserved = true; }

    boolean handoffDue(long tick) { return handoffReserved && tick >= committedTick(); }

    boolean handoffAllowed(String origin, long tick, double elapsedMs) {
        return handoffReserved && stoppedReason == null && tick == committedTick() && validClock(origin, tick, elapsedMs);
    }

    private boolean validClock(String origin, long tick, double elapsedMs) {
        return clockOrigin.equals(origin) && tick >= lastTick && Double.isFinite(elapsedMs) &&
            elapsedMs >= 0 && elapsedMs >= lastElapsedMs && elapsedMs < deadlineMs;
    }

    private Observation stop(String reason) {
        stoppedReason = reason;
        return new Observation(false, true, reason, 0);
    }

    long elapsedPlanTicks() { return lastTick - startTick; }

    Observation observeStabilization(String origin, long tick, double elapsedMs) {
        if (!"temporal_runway_exhausted".equals(stoppedReason)) {
            return stoppedReason == null ? stop("temporal_stabilization_not_admitted")
                : new Observation(false, true, stoppedReason, 0);
        }
        if (!clockOrigin.equals(origin) || tick < lastTick || !Double.isFinite(elapsedMs) ||
            elapsedMs < 0 || elapsedMs < lastElapsedMs) return stop("temporal_clock_invalid");
        lastTick = tick;
        lastElapsedMs = elapsedMs;
        if (elapsedMs >= deadlineMs) return stop("temporal_monotonic_deadline");
        if (tick - startTick >= maximumUnits) return stop("temporal_horizon_exhausted");
        return new Observation(false, false, "temporal_stabilizing", maximumUnits - (tick - startTick));
    }
}
