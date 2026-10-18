package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class EnvironmentCapacityTelemetryTest {
    @Test
    void separatesTransportFromClientPickupWithoutResettingTheResponse() {
        var telemetry = new EnvironmentCapacityTelemetry(1, 2, 0, 0, 20);
        telemetry.recordCheckpoint("checkpoint", 1_000_000);
        telemetry.recordTemporalDeliveryResponse("checkpoint", 2_000_000, 3_000_000);
        telemetry.recordCheckpointEvidenceReady("checkpoint", 3_000_000);
        telemetry.recordTemporalDeliveryResponse("wrong", 4_000_000, 5_000_000);
        telemetry.recordTemporalDeliveryResponse("checkpoint", 2_000_000, 5_000_000);
        telemetry.recordTemporalDeliveryResponse("checkpoint", 5_000_000, 4_000_000);
        telemetry.recordTemporalDeliveryResponse("checkpoint", 4_000_000, 8_000_000);
        telemetry.recordTemporalDeliveryResponse("checkpoint", 9_000_000, 10_000_000);
        telemetry.recordTemporalAcceptance("checkpoint", 7_000_000, Map.of());
        assertNull(telemetry.snapshot().get("last_temporal_acceptance_timing"));
        telemetry.recordTemporalAcceptance("checkpoint", 11_000_000, Map.of());
        var sample = (Map<?, ?>) telemetry.snapshot().get("last_temporal_acceptance_timing");
        assertEquals(1L, sample.get("evidence_fence_observed_to_delivery_poll_ms"));
        assertEquals(4L, sample.get("delivery_http_roundtrip_ms"));
        assertEquals(3L, sample.get("delivery_response_to_acceptance_ms"));
        assertEquals(10L, sample.get("checkpoint_to_acceptance_ms"));
        telemetry.interruptTemporalTiming();
        telemetry.recordCheckpoint("fresh", 12_000_000);
        telemetry.recordTemporalAcceptance("fresh", 13_000_000, Map.of());
        sample = (Map<?, ?>) telemetry.snapshot().get("last_temporal_acceptance_timing");
        assertNull(sample.get("delivery_http_roundtrip_ms"));
        assertNull(sample.get("delivery_response_to_acceptance_ms"));
    }
    @Test
    void evidenceFenceTimingIsFirstObservationOnlyAndCannotSurviveInterruption() {
        var telemetry = new EnvironmentCapacityTelemetry(1, 2, 0, 0, 20);
        telemetry.recordCheckpoint("checkpoint", 1_000_000);
        telemetry.recordCheckpointEvidenceReady("wrong", 2_000_000);
        telemetry.recordCheckpointEvidenceReady("checkpoint", 0);
        telemetry.recordCheckpointEvidenceReady("checkpoint", 3_000_000);
        telemetry.recordCheckpointEvidenceReady("checkpoint", 7_000_000);
        telemetry.recordTemporalAcceptance("checkpoint", 2_000_000, Map.of());
        assertNull(telemetry.snapshot().get("last_temporal_acceptance_timing"));
        telemetry.recordTemporalAcceptance("checkpoint", 9_000_000, Map.of());
        var sample = (Map<?, ?>) telemetry.snapshot().get("last_temporal_acceptance_timing");
        assertEquals(2L, sample.get("checkpoint_to_evidence_fence_observed_ms"));
        assertEquals(6L, sample.get("evidence_fence_observed_to_acceptance_ms"));
        assertEquals(8L, sample.get("checkpoint_to_acceptance_ms"));
        telemetry.recordCheckpoint("next", 10_000_000);
        telemetry.recordCheckpointEvidenceReady("next", 11_000_000);
        telemetry.interruptTemporalTiming();
        telemetry.recordCheckpoint("fresh", 12_000_000);
        telemetry.recordTemporalAcceptance("fresh", 14_000_000, Map.of());
        sample = (Map<?, ?>) telemetry.snapshot().get("last_temporal_acceptance_timing");
        assertNull(sample.get("checkpoint_to_evidence_fence_observed_ms"));
        assertNull(sample.get("evidence_fence_observed_to_acceptance_ms"));
    }
    @Test
    void separatesDeliveryLatencyFromScheduledWaiting() {
        var telemetry = new EnvironmentCapacityTelemetry(1, 2, 0, 0, 20);
        telemetry.recordCheckpoint("checkpoint", 1_000_000);
        telemetry.recordTemporalAcceptance("wrong", 2_000_000, Map.of());
        assertNull(telemetry.snapshot().get("last_temporal_acceptance_timing"));
        telemetry.recordTemporalAcceptance("checkpoint", 3_000_000, Map.of("stop_client_tick", 101));
        telemetry.recordTemporalAcceptance("checkpoint", 4_000_000, Map.of());
        assertEquals(2L, ((Map<?, ?>) telemetry.snapshot().get("last_temporal_acceptance_timing"))
            .get("checkpoint_to_acceptance_ms"));
        assertNull(telemetry.snapshot().get("last_temporal_activation_timing"));
        telemetry.recordTemporalActivation("checkpoint", 9_000_000, Map.of());
        var sample = (Map<?, ?>) telemetry.snapshot().get("last_temporal_activation_timing");
        assertEquals(8L, sample.get("checkpoint_to_activation_ms"));
        assertEquals(6L, sample.get("acceptance_to_activation_ms"));
    }
    @Test
    void activationTimingRequiresMatchingCheckpointAndDoesNotResetOnProgress() {
        var telemetry = new EnvironmentCapacityTelemetry(1, 2, 0, 0, 20);
        assertNull(telemetry.snapshot().get("last_temporal_activation_timing"));
        telemetry.recordCheckpoint("plan:checkpoint", 1_000_000);
        telemetry.recordCheckpoint("plan:checkpoint", 9_000_000);
        telemetry.recordTemporalActivation("other:checkpoint", 11_000_000, Map.of());
        assertNull(telemetry.snapshot().get("last_temporal_activation_timing"));
        telemetry.recordTemporalActivation("plan:checkpoint", 0, Map.of());
        assertNull(telemetry.snapshot().get("last_temporal_activation_timing"));
        telemetry.recordTemporalActivation("plan:checkpoint", 11_000_000, Map.of("successor_plan_id", "next"));
        var measured = (Map<?, ?>) telemetry.snapshot().get("last_temporal_activation_timing");
        assertEquals(10L, measured.get("checkpoint_to_activation_ms"));
        assertEquals("next", measured.get("successor_plan_id"));
        telemetry.recordTemporalActivation("plan:checkpoint", 21_000_000, Map.of());
        assertEquals(measured, telemetry.snapshot().get("last_temporal_activation_timing"));
        telemetry.recordCheckpoint("new:checkpoint", 22_000_000);
        telemetry.interruptTemporalTiming();
        // Resume/progress may retain the old settlement. It is not a new
        // checkpoint and must not shorten the interval by restarting a timer.
        telemetry.recordCheckpoint("new:checkpoint", 24_000_000);
        telemetry.recordTemporalActivation("new:checkpoint", 25_000_000, Map.of());
        assertEquals(measured, telemetry.snapshot().get("last_temporal_activation_timing"));
        telemetry.recordCheckpoint("fresh:checkpoint", 26_000_000);
        telemetry.recordTemporalActivation("fresh:checkpoint", 28_000_000, Map.of());
        assertEquals(2L, ((Map<?, ?>) telemetry.snapshot().get("last_temporal_activation_timing"))
            .get("checkpoint_to_activation_ms"));
    }
    @Test
    void separatesUnmeasuredPollsFromMeasuredTransportAndReconciliation() {
        EnvironmentCapacityTelemetry telemetry = new EnvironmentCapacityTelemetry(1, 2, 0, 0, 20);
        assertNull(telemetry.snapshot().get("temporal_poll_total_ms"));
        assertNull(telemetry.snapshot().get("temporal_reconciliation_max_ms"));
        telemetry.recordTemporalRequest(false, 1_500_000);
        telemetry.recordTemporalRequest(false, 12_000_000_000L);
        telemetry.recordTemporalRequest(true, 2_500_000);
        var measured = telemetry.snapshot();
        assertEquals(2L, measured.get("temporal_poll_count"));
        assertEquals(12_002L, measured.get("temporal_poll_total_ms"));
        assertEquals(12_000L, measured.get("temporal_poll_max_ms"));
        assertEquals(1L, measured.get("temporal_reconciliation_count"));
        assertEquals(3L, measured.get("temporal_reconciliation_total_ms"));
        assertThrows(IllegalArgumentException.class, () -> telemetry.recordTemporalRequest(false, -1));
    }

    @Test
    void reportsDispatchResidentControlStallAndMissedTickMeasurements() {
        EnvironmentCapacityTelemetry telemetry = new EnvironmentCapacityTelemetry(
            1_000_000_000L,
            1_002_000_000L,
            3L,
            125L,
            20L
        );

        Map<String, Object> beforeFirstTick = telemetry.snapshot();
        assertEquals(2L, beforeFirstTick.get("dispatch_to_client_accept_ms"));
        assertNull(beforeFirstTick.get("dispatch_to_first_tick_ms"));
        assertEquals(3L, beforeFirstTick.get("queue_depth_at_lease"));
        assertEquals(125L, beforeFirstTick.get("oldest_pending_age_ms"));
        assertEquals(20L, beforeFirstTick.get("planned_runway_ticks_at_accept"));
        assertEquals(20L, beforeFirstTick.get("runway_ticks_remaining"));

        telemetry.recordSchedulerTick(true, 1_010_000_000L);
        telemetry.recordResidentComputation(1_100_000L);
        telemetry.recordSchedulerTick(false, 1_160_000_000L);
        telemetry.recordResidentComputation(900_000L);
        telemetry.recordManualOrSafetyToRelease(1_100_000L);

        Map<String, Object> measured = telemetry.snapshot();
        assertEquals(10L, measured.get("dispatch_to_first_tick_ms"));
        assertEquals(2L, measured.get("resident_computation_ms"));
        assertEquals(2L, measured.get("scheduler_ticks"));
        assertEquals(1L, measured.get("active_control_ticks"));
        assertEquals(1L, measured.get("stalled_ticks"));
        assertEquals(2L, measured.get("missed_ticks"));
        assertEquals(2L, measured.get("manual_or_safety_to_release_ms"));
        assertEquals(18L, measured.get("runway_ticks_remaining"));
        assertFalse((Boolean) measured.get("measurement_authority"));
        assertFalse((Boolean) measured.get("answer_authority"));
        assertFalse((Boolean) measured.get("terminal_eligible"));
    }

    @Test
    void rejectsRegressingMonotonicMarks() {
        assertThrows(
            IllegalArgumentException.class,
            () -> new EnvironmentCapacityTelemetry(2L, 1L, 0L, 0L, 1L)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new EnvironmentCapacityTelemetry(1L, 2L, -1L, 0L, 1L)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> new EnvironmentCapacityTelemetry(1L, 2L, 0L, 0L, 0L)
        );
        EnvironmentCapacityTelemetry telemetry = new EnvironmentCapacityTelemetry(
            1L,
            2L,
            0L,
            0L,
            1L
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> telemetry.recordSchedulerTick(true, 1L)
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> telemetry.recordManualOrSafetyToRelease(-1L)
        );
    }
}
