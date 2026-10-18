package com.casimirbot.helixplayer.fabric;

import com.casimirbot.helixsensor.snapshot.SectionHasher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PlayerActionDeliveryOutboxTest {
    private static PlayerActionDeliveryOutbox.Delivery event(int sequence) {
        return new PlayerActionDeliveryOutbox.Delivery(PlayerActionDeliveryOutbox.Stage.WORKFLOW_EVENT,
            Map.of("event_id", "event:" + sequence, "workflow_id", "workflow:test",
                "action_request_id", "request:test", "sequence", sequence));
    }

    private static PlayerActionDeliveryOutbox.Delivery projection(int sequence, String epoch) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", "helix.environment_event_batch.v1");
        payload.put("batch_id", "batch:" + sequence);
        payload.put("room_id", "room:test");
        payload.put("source_id", "source:test");
        payload.put("world_id", "world:test");
        payload.put("producer_epoch_ref", epoch);
        payload.put("producer_plane", "player_embodiment");
        payload.put("first_sequence", sequence);
        payload.put("last_sequence", sequence);
        payload.put("events", List.of(Map.of("event_id", "environment_event:" + sequence)));
        payload.put("created_at", "2026-09-20T22:00:00Z");
        payload.put("batch_hash", SectionHasher.hashIncludingNulls(payload));
        return new PlayerActionDeliveryOutbox.Delivery(
            PlayerActionDeliveryOutbox.Stage.ENVIRONMENT_EVENT_BATCH, payload);
    }

    @Test
    void freezesAndAcknowledgesOneOrderedProjectionBatchWithoutDroppingTheFence() {
        var box = new PlayerActionDeliveryOutbox(12);
        var first = projection(0, "epoch:one");
        var second = projection(1, "epoch:one");
        var later = projection(2, "epoch:one");
        assertTrue(box.enqueueSequence(List.of(event(0), first, event(1), second), 0));
        long fence = box.watermark();
        assertTrue(box.acknowledge(box.peekCritical()));
        assertTrue(box.acknowledge(box.peekCritical()));
        var frozen = box.peekProjectionBatch();
        assertEquals(List.of(first, second), frozen);
        assertTrue(box.enqueueSequence(List.of(later), 0));
        assertSame(frozen, box.peekProjectionBatch());
        assertFalse(box.acknowledge(first));
        Map<String, Object> merged = PlayerActionRuntime.projectionBatchPayload(frozen);
        assertEquals("batch:0", merged.get("batch_id"));
        assertEquals(0, merged.get("first_sequence"));
        assertEquals(1, merged.get("last_sequence"));
        assertEquals(2, ((List<?>) merged.get("events")).size());
        var unhashed = new LinkedHashMap<>(merged);
        unhashed.remove("batch_hash");
        assertEquals(SectionHasher.hashIncludingNulls(unhashed), merged.get("batch_hash"));
        assertTrue(box.hasPendingThrough(fence));
        assertTrue(box.acknowledgeProjectionBatch(frozen));
        assertFalse(box.hasPendingThrough(fence));
        assertEquals(List.of(later), box.peekProjectionBatch());
    }

    @Test
    void projectionBatchStopsAtSequenceOrProducerEpochBoundary() {
        var gap = new PlayerActionDeliveryOutbox(10);
        gap.enqueueSequence(List.of(projection(0, "epoch:one"), projection(2, "epoch:one")), 0);
        assertEquals(1, gap.peekProjectionBatch().size());
        var epoch = new PlayerActionDeliveryOutbox(10);
        epoch.enqueueSequence(List.of(projection(0, "epoch:one"), projection(1, "epoch:two")), 0);
        assertEquals(1, epoch.peekProjectionBatch().size());
    }

    @Test
    void legacyServerUsesSingletonButCannotSplitAnUncertainBatch() {
        var box = new PlayerActionDeliveryOutbox(10);
        box.enqueueSequence(List.of(event(0), event(1)), 0);
        var legacy = box.peekCriticalBatch(false);
        assertEquals(1, legacy.size());
        assertTrue(box.acknowledge(legacy.get(0)));
        box.enqueueSequence(List.of(event(2)), 0);
        var batch = box.peekCriticalBatch(true);
        assertEquals(2, batch.size());
        assertSame(batch, box.peekCriticalBatch(false));
        assertTrue(box.acknowledgeCriticalBatch(batch, List.of("event:1", "event:2")));
        assertTrue(box.isEmpty());
    }

    @Test
    void freezesBatchAndRejectsPartialOrReorderedReceipts() {
        var box = new PlayerActionDeliveryOutbox(20);
        var first = event(0);
        var second = event(1);
        var projection = delivery(PlayerActionDeliveryOutbox.Stage.ENVIRONMENT_EVENT_BATCH, "projection");
        box.enqueueSequence(List.of(first, projection, second), 0);
        long fence = box.watermark();
        var batch = box.peekCriticalBatch();
        assertEquals(List.of(first, second), batch);
        box.enqueueSequence(List.of(event(2)), 0);
        assertSame(batch, box.peekCriticalBatch());
        assertFalse(box.acknowledge(first));
        assertFalse(box.acknowledgeCriticalBatch(batch, List.of("event:0")));
        assertFalse(box.acknowledgeCriticalBatch(batch, List.of("event:1", "event:0")));
        assertEquals(4, box.size());
        assertTrue(box.acknowledgeCriticalBatch(batch, List.of("event:0", "event:1")));
        assertTrue(box.hasPendingThrough(fence));
        assertSame(projection, box.peekProjection());
        assertTrue(box.acknowledge(projection));
        assertFalse(box.hasPendingThrough(fence));
        assertEquals(1, box.peekCriticalBatch().size());
    }

    @Test
    void neverBatchesPastAResultOrAcrossASequenceGap() {
        var box = new PlayerActionDeliveryOutbox(20);
        var result = delivery(PlayerActionDeliveryOutbox.Stage.ACTION_RESULT, "result");
        box.enqueueSequence(List.of(event(0), result, event(1)), 0);
        var first = box.peekCriticalBatch();
        assertEquals(1, first.size());
        assertTrue(box.acknowledge(first.get(0)));
        assertEquals(List.of(result), box.peekCriticalBatch());
        var gap = new PlayerActionDeliveryOutbox(20);
        gap.enqueueSequence(List.of(event(0), event(2)), 0);
        assertEquals(1, gap.peekCriticalBatch().size());
    }

    @Test
    void capsBatchCountAndSerializedBytes() {
        var box = new PlayerActionDeliveryOutbox(40);
        for (int i = 0; i < 35; i++) box.enqueueSequence(List.of(event(i)), 0);
        assertEquals(32, box.peekCriticalBatch().size());
        var large = new PlayerActionDeliveryOutbox(10);
        for (int i = 0; i < 3; i++) {
            var payload = new java.util.LinkedHashMap<>(event(i).payload());
            payload.put("summary", "x".repeat(300_000));
            large.enqueueSequence(List.of(new PlayerActionDeliveryOutbox.Delivery(
                PlayerActionDeliveryOutbox.Stage.WORKFLOW_EVENT, payload)), 0);
        }
        assertEquals(1, large.peekCriticalBatch().size());
        assertEquals(3, large.size());
    }

    @Test
    void byteBudgetIncludesNullFieldsExactlyAsHttpTransportDoes() {
        var box = new PlayerActionDeliveryOutbox(10);
        for (int i = 0; i < 2; i++) {
            var payload = new java.util.LinkedHashMap<>(event(i).payload());
            payload.put("x".repeat(270_000), null);
            assertTrue(box.enqueueSequence(List.of(new PlayerActionDeliveryOutbox.Delivery(
                PlayerActionDeliveryOutbox.Stage.WORKFLOW_EVENT, payload)), 0));
        }
        assertEquals(1, box.peekCriticalBatch().size());
        assertEquals(2, box.size());
    }

    @Test
    void duplicateIdentityCannotEraseAnUnacknowledgedFenceEntry() {
        var outbox = new PlayerActionDeliveryOutbox(9);
        var original = delivery(PlayerActionDeliveryOutbox.Stage.WORKFLOW_EVENT, "same");
        assertFalse(outbox.enqueueSequence(List.of(original, original), 0));
        assertEquals(0, outbox.size());
        assertEquals(0, outbox.watermark());
        assertTrue(outbox.enqueueSequence(List.of(original), 0));
        long fence = outbox.watermark();
        var equalButDistinct = delivery(PlayerActionDeliveryOutbox.Stage.WORKFLOW_EVENT, "same");
        assertFalse(outbox.enqueueSequence(List.of(equalButDistinct, original), 0));
        assertEquals(1, outbox.size());
        assertEquals(fence, outbox.watermark());
        assertTrue(outbox.hasPendingThrough(fence));
        assertTrue(outbox.enqueueSequence(List.of(equalButDistinct), 0));
        long secondFence = outbox.watermark();
        assertSame(original, outbox.peekCritical());
        assertTrue(outbox.acknowledge(original));
        assertFalse(outbox.hasPendingThrough(fence));
        assertTrue(outbox.hasPendingThrough(secondFence));
        assertSame(equalButDistinct, outbox.peekCritical());
        assertTrue(outbox.acknowledge(equalButDistinct));
        assertFalse(outbox.hasPendingThrough(secondFence));
        assertTrue(outbox.isEmpty());
    }

    @Test
    void checkpointFenceRequiresBothLanesButDoesNotChaseLaterProgress() {
        PlayerActionDeliveryOutbox outbox = new PlayerActionDeliveryOutbox(9);
        assertTrue(outbox.hasPendingThrough(0));
        var checkpoint = delivery(PlayerActionDeliveryOutbox.Stage.WORKFLOW_EVENT, "checkpoint");
        var projection = delivery(PlayerActionDeliveryOutbox.Stage.ENVIRONMENT_EVENT_BATCH, "checkpoint-projection");
        assertTrue(outbox.enqueueSequence(List.of(checkpoint, projection), 3));
        long fence = outbox.watermark();
        assertSame(checkpoint, outbox.peekCritical());
        assertTrue(outbox.acknowledge(checkpoint));
        assertTrue(outbox.hasPendingThrough(fence));
        var progress = delivery(PlayerActionDeliveryOutbox.Stage.WORKFLOW_EVENT, "later");
        var laterProjection = delivery(PlayerActionDeliveryOutbox.Stage.ENVIRONMENT_EVENT_BATCH, "later-projection");
        assertTrue(outbox.enqueueSequence(List.of(progress, laterProjection), 3));
        long nextFence = outbox.watermark();
        assertSame(projection, outbox.peekProjection());
        assertTrue(outbox.acknowledge(projection));
        assertFalse(outbox.hasPendingThrough(fence));
        assertTrue(outbox.hasPendingThrough(nextFence));
        assertFalse(outbox.isEmpty());
        // No pending evidence was dropped to clear the checkpoint fence.
        assertEquals(2, outbox.size());
        int opportunities = 0;
        for (int tick = 1; tick <= 200; tick++) {
            boolean pending = outbox.hasPendingThrough(fence);
            if (PlayerActionRuntime.actionPollDue(tick, false, pending) &&
                PlayerActionRuntime.temporalDeliveryReady(PlayerActionWorkflow.State.RUNNING, false, false, pending)) {
                opportunities++;
            }
        }
        assertEquals(10, opportunities);
    }

    @Test
    void settlesTheActionLaneBeforeSlowEnvironmentProjection() {
        PlayerActionDeliveryOutbox outbox = new PlayerActionDeliveryOutbox(9);
        PlayerActionDeliveryOutbox.Delivery started = delivery(
            PlayerActionDeliveryOutbox.Stage.WORKFLOW_EVENT,
            "started"
        );
        PlayerActionDeliveryOutbox.Delivery startedBatch = delivery(
            PlayerActionDeliveryOutbox.Stage.ENVIRONMENT_EVENT_BATCH,
            "started-batch"
        );
        PlayerActionDeliveryOutbox.Delivery progress = delivery(
            PlayerActionDeliveryOutbox.Stage.WORKFLOW_EVENT,
            "progress"
        );
        PlayerActionDeliveryOutbox.Delivery progressBatch = delivery(
            PlayerActionDeliveryOutbox.Stage.ENVIRONMENT_EVENT_BATCH,
            "progress-batch"
        );
        PlayerActionDeliveryOutbox.Delivery terminal = delivery(
            PlayerActionDeliveryOutbox.Stage.WORKFLOW_EVENT,
            "terminal"
        );
        PlayerActionDeliveryOutbox.Delivery result = delivery(
            PlayerActionDeliveryOutbox.Stage.ACTION_RESULT,
            "result"
        );
        PlayerActionDeliveryOutbox.Delivery terminalBatch = delivery(
            PlayerActionDeliveryOutbox.Stage.ENVIRONMENT_EVENT_BATCH,
            "terminal-batch"
        );

        assertTrue(outbox.enqueueSequence(List.of(started, startedBatch), 3));
        assertSame(started, outbox.peekCritical());
        assertTrue(outbox.acknowledge(started));
        assertSame(startedBatch, outbox.peekProjection());
        assertTrue(outbox.enqueueSequence(
            List.of(progress, progressBatch, terminal, terminalBatch, result),
            0
        ));

        // A selected slow projection remains pinned to its own lane while the
        // ordered workflow events and terminal result settle independently.
        assertSame(progress, outbox.peekCritical());
        assertTrue(outbox.acknowledge(progress));
        assertSame(terminal, outbox.peekCritical());
        assertTrue(outbox.acknowledge(terminal));
        assertSame(result, outbox.peekCritical());
        assertTrue(outbox.acknowledge(result));
        assertTrue(outbox.isCriticalEmpty());
        assertSame(startedBatch, outbox.peekProjection());
        assertTrue(outbox.acknowledge(startedBatch));
        assertSame(progressBatch, outbox.peekProjection());
        assertFalse(outbox.acknowledge(terminalBatch));
        assertTrue(outbox.acknowledge(progressBatch));
        assertSame(terminalBatch, outbox.peekProjection());
        assertTrue(outbox.acknowledge(terminalBatch));
        assertTrue(outbox.isEmpty());
    }

    @Test
    void reservesRoomForTheTerminalEvidenceAndResult() {
        PlayerActionDeliveryOutbox outbox = new PlayerActionDeliveryOutbox(5);
        assertTrue(outbox.enqueueSequence(List.of(
            delivery(PlayerActionDeliveryOutbox.Stage.WORKFLOW_EVENT, "progress"),
            delivery(PlayerActionDeliveryOutbox.Stage.ENVIRONMENT_EVENT_BATCH, "stream")
        ), 3));
        assertFalse(outbox.enqueueSequence(List.of(
            delivery(PlayerActionDeliveryOutbox.Stage.WORKFLOW_EVENT, "overflow")
        ), 3));
        assertEquals(2, outbox.size());
    }

    @Test
    void saturatedProjectionLanePreservesTerminalDeliveryAndCheckpointFence() {
        var outbox = new PlayerActionDeliveryOutbox(768);
        var checkpoint = delivery(PlayerActionDeliveryOutbox.Stage.WORKFLOW_EVENT, "checkpoint");
        var requiredProjection = delivery(PlayerActionDeliveryOutbox.Stage.ENVIRONMENT_EVENT_BATCH, "required");
        assertTrue(outbox.enqueueSequence(List.of(checkpoint, requiredProjection), 3));
        long fence = outbox.watermark();
        assertSame(checkpoint, outbox.peekCritical());
        assertTrue(outbox.acknowledge(checkpoint));
        assertSame(requiredProjection, outbox.peekProjection());
        // Keep the required projection unacknowledged while later evidence
        // fills every nonterminal slot. A retry must keep the identical head.
        for (int i = 0; i < 764; i++) {
            assertTrue(outbox.enqueueSequence(List.of(delivery(
                PlayerActionDeliveryOutbox.Stage.ENVIRONMENT_EVENT_BATCH, "later-" + i)), 3));
        }
        assertEquals(765, outbox.size());
        assertFalse(outbox.enqueueSequence(List.of(delivery(
            PlayerActionDeliveryOutbox.Stage.WORKFLOW_EVENT, "overflow")), 3));
        assertTrue(outbox.hasPendingThrough(fence));
        assertSame(requiredProjection, outbox.peekProjection());
        var terminal = delivery(PlayerActionDeliveryOutbox.Stage.WORKFLOW_EVENT, "terminal");
        var terminalProjection = delivery(PlayerActionDeliveryOutbox.Stage.ENVIRONMENT_EVENT_BATCH, "terminal-projection");
        var result = delivery(PlayerActionDeliveryOutbox.Stage.ACTION_RESULT, "result");
        assertTrue(outbox.enqueueSequence(List.of(terminal, terminalProjection, result), 0));
        assertEquals(768, outbox.size());
        assertSame(terminal, outbox.peekCritical());
        assertTrue(outbox.acknowledge(terminal));
        assertSame(result, outbox.peekCritical());
        assertTrue(outbox.acknowledge(result));
        assertTrue(outbox.hasPendingThrough(fence));
        assertTrue(outbox.acknowledge(requiredProjection));
        assertFalse(outbox.hasPendingThrough(fence));
        // Clearing the fixed checkpoint fence never drops the later backlog.
        assertEquals(765, outbox.size());
        int drained = 0;
        while (!outbox.isProjectionEmpty()) {
            assertTrue(outbox.acknowledge(outbox.peekProjection()));
            drained++;
        }
        assertEquals(765, drained);
        assertTrue(outbox.isEmpty());
    }

    @Test
    void keepsTransportDiagnosticsTypedAndSanitized() {
        assertEquals(
            "action_delivery_action_result_http_409_action_result_conflict",
            PlayerActionDeliveryOutbox.transportErrorCode(
                PlayerActionDeliveryOutbox.Stage.ACTION_RESULT,
                409,
                "action_result_conflict"
            )
        );
        assertEquals(
            "action_delivery_workflow_event_http_500_request_failed",
            PlayerActionDeliveryOutbox.transportErrorCode(
                PlayerActionDeliveryOutbox.Stage.WORKFLOW_EVENT,
                500,
                "request contained sensitive free-form details"
            )
        );
    }

    private static PlayerActionDeliveryOutbox.Delivery delivery(
        PlayerActionDeliveryOutbox.Stage stage,
        String id
    ) {
        return new PlayerActionDeliveryOutbox.Delivery(stage, Map.of("id", id));
    }
}
