package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.time.Instant;
import org.junit.jupiter.api.Test;

final class PlayerActionRuntimeLifecycleTest {
    @Test
    void expiredLocalLeaseStillAllowsAuthenticatedHeartbeatTransport() {
        var config = new PlayerActionConfig(
            "http://127.0.0.1:63124", "helix_env_action_" + "x".repeat(43),
            "authority", "installation", "environment", "room", "source", "world",
            "adapter", PlayerActionConfig.DOMAIN_ADAPTER, "participant", "subject",
            "player", 1, Instant.now().minusSeconds(60).toString());
        assertTrue(config.transportReady(), "The server must be reachable to report an extended lease.");
        assertFalse(config.ready(), "An expired cached lease must not authorize local actions.");
        assertDoesNotThrow(() -> {
            try (var client = new PlayerActionHttpClient(config)) {
                // Construction must not crash the Fabric client before it can
                // reach the server for a fresh heartbeat.
            }
        });
        assertThrows(IllegalArgumentException.class,
            () -> new PlayerActionHttpClient(PlayerActionConfig.disabled()));
    }

    @Test
    void delayedProjectionBlocksSuccessorEvenAfterCriticalCheckpointAcknowledgement() {
        // Temporal successor admission needs the committed projection as
        // checkpoint evidence even after its critical workflow event lands.
        var outbox = new PlayerActionDeliveryOutbox(9);
        var checkpoint = new PlayerActionDeliveryOutbox.Delivery(
            PlayerActionDeliveryOutbox.Stage.WORKFLOW_EVENT, Map.of("id", "checkpoint"));
        var projection = new PlayerActionDeliveryOutbox.Delivery(
            PlayerActionDeliveryOutbox.Stage.ENVIRONMENT_EVENT_BATCH, Map.of("id", "projection"));
        assertTrue(outbox.enqueueSequence(java.util.List.of(checkpoint, projection), 3));
        assertSameDeliveryCheckpoint(outbox, checkpoint);
        assertTrue(outbox.isCriticalEmpty());
        assertFalse(outbox.isProjectionEmpty());
        assertFalse(PlayerActionRuntime.actionDispatchBlocked(
            false, false, !outbox.isCriticalEmpty()));
        int acceptedPollOpportunities = 0;
        for (long tick = 1; tick <= 200; tick++) {
            boolean pending = !outbox.isEmpty();
            if (PlayerActionRuntime.actionPollDue(tick, true, pending) &&
                PlayerActionRuntime.temporalDeliveryReady(PlayerActionWorkflow.State.RUNNING, false, false, pending)) {
                acceptedPollOpportunities++;
            }
        }
        assertEquals(0, acceptedPollOpportunities);
        assertEquals(projection, outbox.peekProjection());
        assertTrue(outbox.acknowledge(projection));
        assertTrue(PlayerActionRuntime.actionPollDue(201, true, !outbox.isEmpty()));
        assertTrue(PlayerActionRuntime.temporalDeliveryReady(PlayerActionWorkflow.State.RUNNING, false, false, !outbox.isEmpty()));
    }

    @Test
    void criticalEventsStillFenceOneShotDispatchAndHeartbeatCanPreemptProjectionDrain() {
        assertFalse(PlayerActionRuntime.actionDispatchBlocked(false, false, false));
        assertTrue(PlayerActionRuntime.actionDispatchBlocked(false, false, true));
        assertTrue(PlayerActionRuntime.actionDispatchBlocked(true, false, false));
        assertTrue(PlayerActionRuntime.actionDispatchBlocked(false, true, false));

        assertFalse(PlayerActionRuntime.projectionFlushShouldYieldToHeartbeat(false));
        assertTrue(PlayerActionRuntime.projectionFlushShouldYieldToHeartbeat(true));
    }

    @Test
    void terminalSettlementKeepsCriticalOrderAndDoesNotWaitForDelayedProjection() {
        var outbox = new PlayerActionDeliveryOutbox(9);
        var workflow = new PlayerActionDeliveryOutbox.Delivery(
            PlayerActionDeliveryOutbox.Stage.WORKFLOW_EVENT, Map.of("event_id", "terminal"));
        var projection = new PlayerActionDeliveryOutbox.Delivery(
            PlayerActionDeliveryOutbox.Stage.ENVIRONMENT_EVENT_BATCH, Map.of("batch_id", "terminal"));
        var result = new PlayerActionDeliveryOutbox.Delivery(
            PlayerActionDeliveryOutbox.Stage.ACTION_RESULT, Map.of("action_request_id", "request"));
        assertTrue(outbox.enqueueSequence(java.util.List.of(workflow, projection, result), 0));

        assertEquals(workflow, outbox.peekCritical());
        assertTrue(outbox.acknowledge(workflow));
        assertTrue(PlayerActionRuntime.actionDispatchBlocked(false, false, !outbox.isCriticalEmpty()));
        assertEquals(result, outbox.peekCritical());
        assertTrue(outbox.acknowledge(result));
        assertTrue(outbox.isCriticalEmpty());
        assertFalse(outbox.isProjectionEmpty());
        assertFalse(PlayerActionRuntime.actionDispatchBlocked(false, false, !outbox.isCriticalEmpty()));
        assertFalse(PlayerActionRuntime.projectionFlushShouldYieldToHeartbeat(false));
    }

    private static void assertSameDeliveryCheckpoint(PlayerActionDeliveryOutbox outbox,
        PlayerActionDeliveryOutbox.Delivery checkpoint) {
        assertEquals(checkpoint, outbox.peekCritical());
        assertTrue(outbox.acknowledge(checkpoint));
    }

    @Test
    void evidenceBlockedPollRetainsOneOpportunityAfterDrain() {
        assertTrue(PlayerActionRuntime.actionPollDue(20, false, true));
        assertFalse(PlayerActionRuntime.actionPollDue(21, true, true));
        assertTrue(PlayerActionRuntime.actionPollDue(22, true, false));
        assertFalse(PlayerActionRuntime.actionPollDue(23, false, false));
        assertFalse(PlayerActionRuntime.actionPollDue(0, true, false));
        assertFalse(PlayerActionRuntime.actionPollDue(-1, true, false));
        for (long tick = 1; tick <= 100; tick++) {
            assertEquals(tick % 20 == 0, PlayerActionRuntime.actionPollDue(tick, false, false));
        }
        // Fixed-phase evidence publication cannot postpone every opportunity
        // until the next periodic tick once each batch has been acknowledged.
        int afterDrain = 0;
        for (long cycle = 1; cycle <= 5; cycle++) {
            assertFalse(PlayerActionRuntime.actionPollDue(cycle * 20 + 1, true, true));
            if (PlayerActionRuntime.actionPollDue(cycle * 20 + 2, true, false)) afterDrain++;
        }
        assertEquals(5, afterDrain);
    }

    @Test
    void residentClockPublicationHasFiveBoundedOpportunitiesPerHundredTicks() {
        int publications = 0;
        for (long tick = 1; tick <= 100; tick++) {
            boolean due = PlayerActionRuntime.heartbeatPublicationDue(tick, true);
            assertEquals(tick % 20 == 0, due);
            if (due) publications++;
            assertFalse(PlayerActionRuntime.heartbeatPublicationDue(tick, false));
        }
        assertEquals(5, publications);
        assertFalse(PlayerActionRuntime.heartbeatPublicationDue(0, true));
        assertFalse(PlayerActionRuntime.heartbeatPublicationDue(-20, true));
        assertTrue(PlayerActionRuntime.heartbeatPublicationDue(3_000_000_000L, true));
    }

    @Test
    void receivedSuccessorWaitsForEvidenceWithoutReplayOrReplacement() {
        var slot = new PlayerActionRuntime.TemporalResponseSlot<String>();
        assertTrue(slot.offer("leased-response"));
        assertFalse(slot.offer("competing-response"));
        assertEquals(null, slot.take(true, true));
        assertTrue(slot.pending());
        assertEquals("leased-response", slot.take(true, false));
        assertFalse(slot.pending());
        assertEquals(null, slot.take(true, false));
        assertTrue(slot.offer("interrupted-response"));
        assertEquals(null, slot.take(false, true));
        assertFalse(slot.pending());
        assertEquals(null, slot.take(true, false));
    }

    @Test
    void successorResponseCannotCrossResyncInterruptionOrEvidenceBackpressure() {
        for (PlayerActionWorkflow.State state : PlayerActionWorkflow.State.values()) {
            assertEquals(state == PlayerActionWorkflow.State.RUNNING,
                PlayerActionRuntime.temporalDeliveryReady(state, false, false, false));
            assertFalse(PlayerActionRuntime.temporalDeliveryReady(state, true, false, false));
            assertFalse(PlayerActionRuntime.temporalDeliveryReady(state, false, true, false));
            assertFalse(PlayerActionRuntime.temporalDeliveryReady(state, false, false, true));
        }
    }

    @Test
    void eventIdentityCannotBeRelabeledToTheCurrentEnvelope() {
        PlayerActionWorkflow.WorkflowEvent event = new PlayerActionWorkflow.WorkflowEvent("action:first", "workflow:resident",
            1, "workflow.progress", PlayerActionWorkflow.State.RUNNING, 0.1, "Measured progress", Map.of(), false, false);
        assertTrue(PlayerActionRuntime.eventMatchesEnvelope(event, Map.of("action_request_id", "action:first", "workflow_id", "workflow:resident")));
        assertFalse(PlayerActionRuntime.eventMatchesEnvelope(event, Map.of("action_request_id", "action:successor", "workflow_id", "workflow:resident")));
        assertFalse(PlayerActionRuntime.eventMatchesEnvelope(event, Map.of("action_request_id", "action:first", "workflow_id", "workflow:other")));
        assertFalse(PlayerActionRuntime.eventMatchesEnvelope(event, Map.of()));
        PlayerActionWorkflow.WorkflowEvent missing = new PlayerActionWorkflow.WorkflowEvent(null, "workflow:resident",
            1, "workflow.progress", PlayerActionWorkflow.State.RUNNING, 0.1, "Missing identity", Map.of(), false, false);
        assertFalse(PlayerActionRuntime.eventMatchesEnvelope(missing, Map.of("workflow_id", "workflow:resident")));
    }

    @Test
    void temporalWireMetadataCannotSilentlyFallBackToFiniteExecution() {
        assertTrue(PlayerActionRuntime.hasTemporalMetadata(Map.of("temporal_plan", Map.of())));
        assertTrue(PlayerActionRuntime.hasTemporalMetadata(Map.of("temporal_plan", "malformed")));
        Map<String, Object> nullPlan = new java.util.LinkedHashMap<>();
        nullPlan.put("temporal_plan", null);
        assertTrue(PlayerActionRuntime.hasTemporalMetadata(nullPlan));
        for (String field : java.util.List.of("temporal_plan_canonical_json",
                "temporal_compilation_canonical_json", "temporal_compilation_hash")) {
            assertTrue(PlayerActionRuntime.hasTemporalMetadata(Map.of(field, "orphaned")));
        }
        assertFalse(PlayerActionRuntime.hasTemporalMetadata(Map.of("action_kind", "execute_sequence")));
    }

    @Test
    void onlyImplementedGraphCapabilitiesAdvertiseStartDeadlines() {
        for (String kind : java.util.List.of("execute_sequence", "execute_reactive_program", "walk")) {
            Map<String, Object> capability = PlayerActionRuntime.capability("capability:test", kind,
                "continuous_control", java.util.List.of("long_running"), java.util.List.of("native_fabric"), false);
            assertEquals(!kind.equals("walk"), capability.containsKey("execution_features"));
            if (kind.equals("execute_sequence")) assertEquals(java.util.List.of("latest_start_tick_v1", "temporal_plan_v1"), capability.get("execution_features"));
            if (kind.equals("execute_reactive_program")) assertEquals(java.util.List.of("latest_start_tick_v1"), capability.get("execution_features"));
        }
    }

    @Test
    void actionPollingWaitsForTheFirstAdmittedHeartbeat() {
        assertFalse(PlayerActionRuntime.actionPollingReady(false, false, true));
        assertFalse(PlayerActionRuntime.actionPollingReady(true, false, true));
        assertFalse(PlayerActionRuntime.actionPollingReady(true, true, false));
        assertTrue(PlayerActionRuntime.actionPollingReady(true, true, true));
    }

    @Test
    void directDiagnosticAdmissionIncludesTheExactHostileAttackAction() {
        assertTrue(PlayerActionRuntime.directDiagnosticActionAllowed("attack"));
        assertFalse(PlayerActionRuntime.directDiagnosticActionAllowed("attack_nearest"));
    }

    @Test
    void activeActionFailsClosedOnAnyCurrentTransportFailure() {
        assertFalse(PlayerActionRuntime.activeControlPlaneFailureRequiresStop(
            true,
            null,
            "heartbeat_unreachable"
        ));
        assertFalse(PlayerActionRuntime.activeControlPlaneFailureRequiresStop(
            true,
            "workflow:active",
            ""
        ));
        assertTrue(PlayerActionRuntime.activeControlPlaneFailureRequiresStop(
            true,
            "workflow:active",
            "action_delivery_workflow_event_unreachable"
        ));
    }

    @Test
    void activeRemoteActionStopsAtKnownCredentialExpiryWithoutWaitingForHttpFailure() {
        assertTrue(PlayerActionRuntime.credentialExpiryRequiresWorkflowStop(
            true, "workflow:active", false
        ));
        assertFalse(PlayerActionRuntime.credentialExpiryRequiresWorkflowStop(
            true, "workflow:active", true
        ));
        assertFalse(PlayerActionRuntime.credentialExpiryRequiresWorkflowStop(
            true, null, false
        ));
        assertFalse(PlayerActionRuntime.credentialExpiryRequiresWorkflowStop(
            false, "direct_player_action_workflow:active", false
        ));
    }

    @Test
    void remotelyArmedResidentGuardianDisarmsBeforeTheNextTickWhenAuthorityIsUnusable() {
        assertTrue(PlayerActionRuntime.remoteGuardianMustDisarm(true, false, true, false, false));
        assertTrue(PlayerActionRuntime.remoteGuardianMustDisarm(true, true, false, false, false));
        assertTrue(PlayerActionRuntime.remoteGuardianMustDisarm(true, true, true, true, false));
        assertTrue(PlayerActionRuntime.remoteGuardianMustDisarm(true, true, true, false, true));
        assertFalse(PlayerActionRuntime.remoteGuardianMustDisarm(true, true, true, false, false));
        assertFalse(PlayerActionRuntime.remoteGuardianMustDisarm(false, false, false, true, true));
    }

    @Test
    void directDiagnosticIgnoresRemoteTransportFailure() {
        assertFalse(PlayerActionRuntime.activeControlPlaneFailureRequiresStop(
            false,
            "direct_player_action_workflow:active",
            "heartbeat_unreachable"
        ));
    }

    @Test
    void recoveryHeartbeatDoesNotSelfLatchAPreviousStalePoll() {
        assertEquals(
            "active",
            PlayerActionRuntime.connectorHeartbeatStatus(
                false,
                false,
                "action_connector_stale"
            )
        );
        assertEquals(
            "paused",
            PlayerActionRuntime.connectorHeartbeatStatus(
                true,
                false,
                "action_connector_stale"
            )
        );
    }

    @Test
    void evidenceStreamConflictFailsClosedUntilFreshPairing() {
        String conflict =
            "action_delivery_environment_event_batch_http_409_action_event_conflict";
        assertTrue(PlayerActionRuntime.requiresFreshProducerEpoch(conflict));
        assertTrue(PlayerActionRuntime.requiresFreshProducerEpoch(
            "action_event_stream_resync_required"
        ));
        assertEquals(
            "error",
            PlayerActionRuntime.connectorHeartbeatStatus(false, true, conflict)
        );
        assertEquals(
            "error",
            PlayerActionRuntime.connectorHeartbeatStatus(false, false, conflict)
        );
        assertTrue(PlayerActionRuntime.eventStreamResyncRequiresWorkflowStop(
            true,
            "workflow:active"
        ));
        assertFalse(PlayerActionRuntime.eventStreamResyncRequiresWorkflowStop(
            true,
            null
        ));
        assertFalse(PlayerActionRuntime.eventStreamResyncRequiresWorkflowStop(
            false,
            "workflow:active"
        ));
    }

    @Test
    void heartbeatCursorConflictWaitsForInFlightProjectionReceipt() {
        assertTrue(PlayerActionRuntime.heartbeatCursorConflictCanReconcile(
            "action_event_stream_resync_required", true, 0, 0
        ));
        assertTrue(PlayerActionRuntime.heartbeatCursorConflictCanReconcile(
            "action_event_stream_resync_required", false, 0, 1
        ));
        assertFalse(PlayerActionRuntime.heartbeatCursorConflictCanReconcile(
            "action_event_stream_resync_required", false, 1, 1
        ));
        assertFalse(PlayerActionRuntime.heartbeatCursorConflictCanReconcile(
            "action_delivery_environment_event_batch_http_409_action_event_conflict", true, 0, 0
        ));
    }

    @Test
    void evidenceEpochRotatesOnlyWhileIdleAndFullyDelivered() {
        assertTrue(PlayerActionRuntime.evidenceEpochRotationAllowed(false, false, false));
        assertFalse(PlayerActionRuntime.evidenceEpochRotationAllowed(true, false, false));
        assertFalse(PlayerActionRuntime.evidenceEpochRotationAllowed(false, true, false));
        assertFalse(PlayerActionRuntime.evidenceEpochRotationAllowed(false, false, true));
    }

    @Test
    void missingRestoredManifestReentersManifestAdmission() {
        assertTrue(PlayerActionRuntime.heartbeatFailureRequiresManifestRepublish(
            "action_heartbeat_invalid"
        ));
        assertTrue(PlayerActionRuntime.heartbeatFailureRequiresManifestRepublish(
            "action_manifest_required"
        ));
        assertFalse(PlayerActionRuntime.heartbeatFailureRequiresManifestRepublish(
            "action_event_stream_resync_required"
        ));
    }

    @Test
    void heartbeatCursorAdvancesOnlyFromAcknowledgedEnvironmentBatches() {
        long acknowledged = -1;

        // Producing workflow events does not change the server-visible cursor.
        assertEquals(-1, acknowledged);
        acknowledged = PlayerActionRuntime.acknowledgedEventSequence(
            acknowledged,
            Map.of("last_sequence", 2)
        );
        assertEquals(2, acknowledged);

        // A delayed replay cannot move the acknowledged cursor backwards.
        assertEquals(
            2,
            PlayerActionRuntime.acknowledgedEventSequence(
                acknowledged,
                Map.of("last_sequence", 1)
            )
        );
        assertEquals(
            2,
            PlayerActionRuntime.acknowledgedEventSequence(
                acknowledged,
                Map.of("schema", "helix.environment_action.workflow_event.v1")
            )
        );
    }

    @Test
    void preControlSafetyRefusalDoesNotClaimAnEnvironmentEffect() {
        assertFalse(PlayerActionRuntime.effectExecutionPerformed(
            true,
            true,
            Map.of(
                "effect_prevented", true,
                "reason_code", "locomotion_health_floor_crossed"
            )
        ));
        assertTrue(PlayerActionRuntime.effectExecutionPerformed(true, true, Map.of()));
    }

    @Test
    void laterSafetyRefusalPreservesMeasuredPartialMotion() {
        assertTrue(PlayerActionRuntime.effectExecutionPerformed(
            true,
            true,
            Map.of(
                "effect_prevented", true,
                "player_motion_performed", true,
                "distance_blocks", 3.0,
                "reason_code", "locomotion_predicted_drop_exceeded"
            )
        ));
    }

    @Test
    void plannerRefusalWithExplicitZeroMotionDoesNotClaimAnEffect() {
        assertFalse(PlayerActionRuntime.effectExecutionPerformed(
            true,
            true,
            Map.of(
                "player_motion_performed", false,
                "workflow_displacement_blocks", 0.0,
                "reason_code", "native_plan_unavailable"
            )
        ));
    }
}
