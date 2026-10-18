package com.casimirbot.helixplayer.fabric;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Observation-only timing counters for one admitted workflow. This class never
 * schedules work or owns controls; it only measures the native executor path.
 */
final class EnvironmentCapacityTelemetry {
    private static final long NOMINAL_TICK_NANOS = 50_000_000L;

    private final long dispatchReceivedNanos;
    private final long acceptedNanos;
    private final long queueDepthAtLease;
    private final long oldestPendingAgeMs;
    private final long plannedRunwayTicksAtAccept;
    private long firstExecutionTickNanos = -1L;
    private long lastSchedulerTickNanos = -1L;
    private long residentComputationNanos;
    private long schedulerTicks;
    private long activeControlTicks;
    private long stalledTicks;
    private long missedTicks;
    private Long manualOrSafetyToReleaseNanos;
    private final long[] temporalRequestCounts = new long[2];
    private final long[] temporalRequestTotalNanos = new long[2];
    private final long[] temporalRequestMaxNanos = new long[2];
    private String checkpointKey;
    private String lastObservedCheckpointKey;
    private long checkpointNanos;
    private Map<String, Object> lastTemporalActivation;
    private Map<String, Object> lastTemporalAcceptance;
    private Long successorAcceptedNanos;
    private Long checkpointEvidenceReadyNanos;
    private Long deliveryPollStartedNanos;
    private Long deliveryResponseNanos;

    synchronized void interruptTemporalTiming() {
        checkpointKey = null; successorAcceptedNanos = null; checkpointEvidenceReadyNanos = null;
        deliveryPollStartedNanos = null; deliveryResponseNanos = null;
    }

    synchronized void recordCheckpoint(String key, long now) {
        if (key == null || key.isBlank() || key.equals(lastObservedCheckpointKey)) return;
        lastObservedCheckpointKey = key;
        checkpointKey = key;
        checkpointNanos = now;
        successorAcceptedNanos = null;
        checkpointEvidenceReadyNanos = null;
        deliveryPollStartedNanos = null;
        deliveryResponseNanos = null;
    }

    synchronized void recordCheckpointEvidenceReady(String key, long now) {
        if (key == null || !key.equals(checkpointKey) || now < checkpointNanos ||
            checkpointEvidenceReadyNanos != null || successorAcceptedNanos != null) return;
        checkpointEvidenceReadyNanos = now;
    }

    synchronized void recordTemporalAcceptance(String key, long now, Map<String, Object> identity) {
        if (key == null || !key.equals(checkpointKey) || now < checkpointNanos || successorAcceptedNanos != null ||
            (checkpointEvidenceReadyNanos != null && now < checkpointEvidenceReadyNanos) ||
            (deliveryResponseNanos != null && now < deliveryResponseNanos)) return;
        successorAcceptedNanos = now;
        Map<String, Object> sample = new LinkedHashMap<>(identity);
        sample.put("checkpoint_to_acceptance_ms", nanosToCeilingMilliseconds(now - checkpointNanos));
        sample.put("checkpoint_to_evidence_fence_observed_ms", checkpointEvidenceReadyNanos == null ? null :
            nanosToCeilingMilliseconds(checkpointEvidenceReadyNanos - checkpointNanos));
        sample.put("evidence_fence_observed_to_acceptance_ms", checkpointEvidenceReadyNanos == null || now < checkpointEvidenceReadyNanos ? null :
            nanosToCeilingMilliseconds(now - checkpointEvidenceReadyNanos));
        sample.put("evidence_fence_observed_to_delivery_poll_ms", deliveryPollStartedNanos == null ? null :
            nanosToCeilingMilliseconds(deliveryPollStartedNanos - checkpointEvidenceReadyNanos));
        sample.put("delivery_http_roundtrip_ms", deliveryResponseNanos == null ? null :
            nanosToCeilingMilliseconds(deliveryResponseNanos - deliveryPollStartedNanos));
        sample.put("delivery_response_to_acceptance_ms", deliveryResponseNanos == null ? null :
            nanosToCeilingMilliseconds(now - deliveryResponseNanos));
        sample.put("clock_domain", "native_process_monotonic");
        sample.put("execution_authority", false);
        sample.put("live_acceptance", false);
        lastTemporalAcceptance = Collections.unmodifiableMap(sample);
    }

    synchronized void recordTemporalDeliveryResponse(String key, long started, long received) {
        if (key == null || !key.equals(checkpointKey) || checkpointEvidenceReadyNanos == null ||
            started < checkpointEvidenceReadyNanos || received < started ||
            successorAcceptedNanos != null || deliveryResponseNanos != null) return;
        deliveryPollStartedNanos = started;
        deliveryResponseNanos = received;
    }

    synchronized void recordTemporalActivation(String key, long now, Map<String, Object> identity) {
        if (key == null || !key.equals(checkpointKey) || now < checkpointNanos ||
            (successorAcceptedNanos != null && now < successorAcceptedNanos)) return;
        Map<String, Object> sample = new LinkedHashMap<>(identity);
        sample.put("checkpoint_to_activation_ms", nanosToCeilingMilliseconds(now - checkpointNanos));
        sample.put("acceptance_to_activation_ms", successorAcceptedNanos == null ? null :
            nanosToCeilingMilliseconds(now - successorAcceptedNanos));
        sample.put("clock_domain", "native_process_monotonic");
        sample.put("execution_authority", false);
        sample.put("live_acceptance", false);
        lastTemporalActivation = Collections.unmodifiableMap(sample);
        checkpointKey = null;
    }

    EnvironmentCapacityTelemetry(
        long dispatchReceivedNanos,
        long acceptedNanos,
        long queueDepthAtLease,
        long oldestPendingAgeMs,
        long plannedRunwayTicksAtAccept
    ) {
        if (dispatchReceivedNanos < 0L || acceptedNanos < dispatchReceivedNanos) {
            throw new IllegalArgumentException("Capacity telemetry clocks cannot regress.");
        }
        if (
            queueDepthAtLease < 0L ||
            oldestPendingAgeMs < 0L ||
            plannedRunwayTicksAtAccept < 1L
        ) {
            throw new IllegalArgumentException("Capacity queue and runway measurements are invalid.");
        }
        this.dispatchReceivedNanos = dispatchReceivedNanos;
        this.acceptedNanos = acceptedNanos;
        this.queueDepthAtLease = queueDepthAtLease;
        this.oldestPendingAgeMs = oldestPendingAgeMs;
        this.plannedRunwayTicksAtAccept = plannedRunwayTicksAtAccept;
    }

    synchronized void recordSchedulerTick(
        boolean activeControl,
        long tickStartedNanos
    ) {
        if (tickStartedNanos < acceptedNanos) {
            throw new IllegalArgumentException("Capacity telemetry tick marks cannot regress.");
        }
        if (firstExecutionTickNanos < 0L) firstExecutionTickNanos = tickStartedNanos;
        if (lastSchedulerTickNanos >= 0L) {
            long intervalNanos = tickStartedNanos - lastSchedulerTickNanos;
            if (intervalNanos < 0L) {
                throw new IllegalArgumentException("Capacity telemetry tick marks cannot regress.");
            }
            long elapsedIntervals = intervalNanos / NOMINAL_TICK_NANOS;
            if (elapsedIntervals > 1L) missedTicks += elapsedIntervals - 1L;
        }
        lastSchedulerTickNanos = tickStartedNanos;
        schedulerTicks++;
        if (activeControl) activeControlTicks++;
        else stalledTicks++;
    }

    synchronized void recordResidentComputation(long computationNanos) {
        if (computationNanos < 0L) {
            throw new IllegalArgumentException("Resident computation cannot be negative.");
        }
        residentComputationNanos += computationNanos;
    }

    synchronized void recordManualOrSafetyToRelease(long latencyNanos) {
        if (latencyNanos < 0L) {
            throw new IllegalArgumentException("Release latency cannot be negative.");
        }
        manualOrSafetyToReleaseNanos = latencyNanos;
    }

    synchronized void recordTemporalRequest(boolean reconciliation, long elapsedNanos) {
        if (elapsedNanos < 0L) throw new IllegalArgumentException("Transport duration cannot regress.");
        int lane = reconciliation ? 1 : 0;
        temporalRequestCounts[lane]++;
        temporalRequestTotalNanos[lane] += elapsedNanos;
        temporalRequestMaxNanos[lane] = Math.max(temporalRequestMaxNanos[lane], elapsedNanos);
    }

    synchronized Map<String, Object> snapshot() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("last_temporal_activation_timing", lastTemporalActivation);
        values.put("last_temporal_acceptance_timing", lastTemporalAcceptance);
        for (int lane = 0; lane < 2; lane++) {
            String prefix = lane == 0 ? "temporal_poll" : "temporal_reconciliation";
            values.put(prefix + "_count", temporalRequestCounts[lane]);
            values.put(prefix + "_total_ms", temporalRequestCounts[lane] == 0 ? null :
                nanosToCeilingMilliseconds(temporalRequestTotalNanos[lane]));
            values.put(prefix + "_max_ms", temporalRequestCounts[lane] == 0 ? null :
                nanosToCeilingMilliseconds(temporalRequestMaxNanos[lane]));
        }
        values.put(
            "dispatch_to_client_accept_ms",
            nanosToCeilingMilliseconds(acceptedNanos - dispatchReceivedNanos)
        );
        values.put(
            "dispatch_to_first_tick_ms",
            firstExecutionTickNanos < 0L
                ? null
                : nanosToCeilingMilliseconds(firstExecutionTickNanos - dispatchReceivedNanos)
        );
        values.put(
            "resident_computation_ms",
            nanosToCeilingMilliseconds(residentComputationNanos)
        );
        values.put("queue_depth_at_lease", queueDepthAtLease);
        values.put("oldest_pending_age_ms", oldestPendingAgeMs);
        values.put("planned_runway_ticks_at_accept", plannedRunwayTicksAtAccept);
        values.put(
            "runway_ticks_remaining",
            Math.max(0L, plannedRunwayTicksAtAccept - schedulerTicks)
        );
        values.put("scheduler_ticks", schedulerTicks);
        values.put("active_control_ticks", activeControlTicks);
        values.put("stalled_ticks", stalledTicks);
        values.put("missed_ticks", missedTicks);
        values.put(
            "manual_or_safety_to_release_ms",
            manualOrSafetyToReleaseNanos == null
                ? null
                : nanosToCeilingMilliseconds(manualOrSafetyToReleaseNanos)
        );
        values.put("measurement_authority", false);
        values.put("answer_authority", false);
        values.put("terminal_eligible", false);
        return Collections.unmodifiableMap(values);
    }

    private static long nanosToCeilingMilliseconds(long nanos) {
        if (nanos <= 0L) return 0L;
        return (nanos + 999_999L) / 1_000_000L;
    }
}
