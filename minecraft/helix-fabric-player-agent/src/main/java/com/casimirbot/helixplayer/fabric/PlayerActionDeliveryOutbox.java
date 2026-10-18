package com.casimirbot.helixplayer.fabric;

import com.casimirbot.helixsensor.HelixJson;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bounded, ordered, in-memory delivery state for an accepted player workflow.
 * Entries remain present until the Helix endpoint acknowledges them. This is
 * deliberately not an action replay mechanism: it only completes delivery of
 * evidence and the terminal result for the already executed action.
 */
final class PlayerActionDeliveryOutbox {
    enum Stage {
        WORKFLOW_EVENT("/requests/event", "workflow_event"),
        ENVIRONMENT_EVENT_BATCH("/events/batch", "environment_event_batch"),
        ACTION_RESULT("/requests/result", "action_result");

        private final String endpointSuffix;
        private final String diagnosticName;

        Stage(String endpointSuffix, String diagnosticName) {
            this.endpointSuffix = endpointSuffix;
            this.diagnosticName = diagnosticName;
        }

        String endpointSuffix() {
            return endpointSuffix;
        }

        String diagnosticName() {
            return diagnosticName;
        }
    }

    record Delivery(Stage stage, Map<String, Object> payload) {
        Delivery {
            payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
        }
    }

    private final int capacity;
    private final Deque<Delivery> pending = new ArrayDeque<>();
    private Delivery criticalInFlight;
    private List<Delivery> criticalBatchInFlight;
    private Delivery projectionInFlight;
    private List<Delivery> projectionBatchInFlight;
    private final java.util.IdentityHashMap<Delivery, Long> ordinals = new java.util.IdentityHashMap<>();
    private long tailOrdinal;

    PlayerActionDeliveryOutbox(int capacity) {
        if (capacity < 3) throw new IllegalArgumentException("Delivery outbox capacity is too small.");
        this.capacity = capacity;
    }

    synchronized boolean enqueueSequence(List<Delivery> deliveries, int reservedTerminalSlots) {
        if (deliveries.isEmpty()) return true;
        int usableCapacity = capacity - Math.max(0, reservedTerminalSlots);
        if (pending.size() + deliveries.size() > usableCapacity) return false;
        var identities = new java.util.IdentityHashMap<Delivery, Boolean>();
        for (Delivery delivery : deliveries) {
            if (delivery == null || ordinals.containsKey(delivery) ||
                identities.put(delivery, Boolean.TRUE) != null) return false;
        }
        pending.addAll(new ArrayList<>(deliveries));
        for (Delivery delivery : deliveries) ordinals.put(delivery, ++tailOrdinal);
        return true;
    }

    synchronized long watermark() { return tailOrdinal; }

    // Both lanes through the required checkpoint must be acknowledged. Later
    // progress remains retained, but cannot indefinitely move this fence.
    synchronized boolean hasPendingThrough(long watermark) {
        return watermark <= 0 || ordinals.values().stream().anyMatch(value -> value <= watermark);
    }

    synchronized Delivery peekCritical() {
        if (criticalInFlight != null) return criticalInFlight;
        for (Delivery delivery : pending) {
            if (delivery.stage() == Stage.ENVIRONMENT_EVENT_BATCH) continue;
            criticalInFlight = delivery;
            return criticalInFlight;
        }
        return null;
    }

    /** Freeze a bounded prefix; later enqueues must not change an uncertain retry. */
    synchronized List<Delivery> peekCriticalBatch() {
        return peekCriticalBatch(true);
    }

    synchronized List<Delivery> peekCriticalBatch(boolean supported) {
        if (criticalBatchInFlight != null) return criticalBatchInFlight;
        if (!supported) {
            Delivery single = peekCritical();
            return single == null ? List.of() : List.of(single);
        }
        if (criticalInFlight != null) return List.of(criticalInFlight);
        List<Delivery> selected = new ArrayList<>();
        int bytes = 32;
        for (Delivery delivery : pending) {
            if (delivery.stage() == Stage.ENVIRONMENT_EVENT_BATCH) continue;
            if (!selected.isEmpty()) {
                Delivery prior = selected.get(selected.size() - 1);
                if (delivery.stage() != Stage.WORKFLOW_EVENT || prior.stage() != Stage.WORKFLOW_EVENT ||
                    !java.util.Objects.equals(delivery.payload().get("workflow_id"), prior.payload().get("workflow_id")) ||
                    !java.util.Objects.equals(delivery.payload().get("action_request_id"), prior.payload().get("action_request_id")) ||
                    !(delivery.payload().get("sequence") instanceof Number next) ||
                    !(prior.payload().get("sequence") instanceof Number previous) ||
                    next.longValue() != previous.longValue() + 1) break;
            }
            int size = HelixJson.stringifyIncludingNulls(delivery.payload()).getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
            if (!selected.isEmpty() && bytes + size > 512 * 1024) break;
            selected.add(delivery);
            bytes += size;
            if (selected.size() == 32 || delivery.stage() != Stage.WORKFLOW_EVENT) break;
        }
        if (selected.isEmpty()) return List.of();
        criticalInFlight = selected.get(0);
        criticalBatchInFlight = List.copyOf(selected);
        return criticalBatchInFlight;
    }

    synchronized boolean acknowledgeCriticalBatch(List<Delivery> expected, Object receivedIds) {
        if (expected != criticalBatchInFlight || !(receivedIds instanceof List<?> ids) ||
            ids.size() != expected.size()) return false;
        for (int i = 0; i < expected.size(); i++) {
            Object id = expected.get(i).payload().get("event_id");
            if (!(id instanceof String) || !id.equals(ids.get(i))) return false;
        }
        // All identities are checked before removing any member or fence.
        for (Delivery delivery : expected) {
            pending.removeIf(candidate -> candidate == delivery);
            ordinals.remove(delivery);
        }
        criticalBatchInFlight = null;
        criticalInFlight = null;
        return true;
    }

    synchronized Delivery peekProjection() {
        if (projectionInFlight != null) return projectionInFlight;
        for (Delivery delivery : pending) {
            if (delivery.stage() != Stage.ENVIRONMENT_EVENT_BATCH) continue;
            projectionInFlight = delivery;
            return projectionInFlight;
        }
        return null;
    }

    /** Freeze one ordered projection prefix so an uncertain POST retries byte-identically. */
    synchronized List<Delivery> peekProjectionBatch() {
        if (projectionBatchInFlight != null) return projectionBatchInFlight;
        if (projectionInFlight != null) {
            projectionBatchInFlight = List.of(projectionInFlight);
            return projectionBatchInFlight;
        }
        List<Delivery> selected = new ArrayList<>();
        int bytes = 32;
        long expectedSequence = -1;
        Map<String, Object> first = null;
        for (Delivery delivery : pending) {
            if (delivery.stage() != Stage.ENVIRONMENT_EVENT_BATCH) continue;
            Map<String, Object> payload = delivery.payload();
            int size = HelixJson.stringifyIncludingNulls(payload)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8).length + 1;
            if (!selected.isEmpty()) {
                if (first == null || !(payload.get("first_sequence") instanceof Number sequence) ||
                    sequence.longValue() != expectedSequence ||
                    !java.util.Objects.equals(payload.get("room_id"), first.get("room_id")) ||
                    !java.util.Objects.equals(payload.get("source_id"), first.get("source_id")) ||
                    !java.util.Objects.equals(payload.get("world_id"), first.get("world_id")) ||
                    !java.util.Objects.equals(payload.get("producer_epoch_ref"), first.get("producer_epoch_ref")) ||
                    !java.util.Objects.equals(payload.get("producer_plane"), first.get("producer_plane")) ||
                    bytes + size > 512 * 1024) break;
            } else {
                first = payload;
            }
            selected.add(delivery);
            bytes += size;
            if (!(payload.get("last_sequence") instanceof Number last)) break;
            expectedSequence = last.longValue() + 1;
            if (selected.size() == 32) break;
        }
        if (selected.isEmpty()) return List.of();
        projectionInFlight = selected.get(0);
        projectionBatchInFlight = List.copyOf(selected);
        return projectionBatchInFlight;
    }

    synchronized boolean acknowledgeProjectionBatch(List<Delivery> expected) {
        if (expected == null || expected != projectionBatchInFlight || expected.isEmpty()) return false;
        for (Delivery delivery : expected) {
            if (!ordinals.containsKey(delivery)) return false;
        }
        for (Delivery delivery : expected) {
            pending.removeIf(candidate -> candidate == delivery);
            ordinals.remove(delivery);
        }
        projectionBatchInFlight = null;
        projectionInFlight = null;
        return true;
    }

    synchronized boolean acknowledge(Delivery expected) {
        if (expected == null) return false;
        if (expected.stage() != Stage.ENVIRONMENT_EVENT_BATCH &&
            criticalBatchInFlight != null && criticalBatchInFlight.size() > 1) return false;
        if (expected.stage() == Stage.ENVIRONMENT_EVENT_BATCH &&
            projectionBatchInFlight != null && projectionBatchInFlight.size() > 1) return false;
        Delivery selected = expected.stage() == Stage.ENVIRONMENT_EVENT_BATCH
            ? projectionInFlight
            : criticalInFlight;
        if (selected != expected) return false;
        Iterator<Delivery> iterator = pending.iterator();
        while (iterator.hasNext()) {
            if (iterator.next() != expected) continue;
            iterator.remove();
            ordinals.remove(expected);
            if (expected.stage() == Stage.ENVIRONMENT_EVENT_BATCH) {
                projectionInFlight = null;
                projectionBatchInFlight = null;
            } else {
                criticalInFlight = null;
                criticalBatchInFlight = null;
            }
            return true;
        }
        return false;
    }

    synchronized boolean isEmpty() {
        return pending.isEmpty();
    }

    synchronized boolean isCriticalEmpty() {
        return pending.stream().noneMatch(
            delivery -> delivery.stage() != Stage.ENVIRONMENT_EVENT_BATCH
        );
    }

    synchronized boolean isProjectionEmpty() {
        return pending.stream().noneMatch(
            delivery -> delivery.stage() == Stage.ENVIRONMENT_EVENT_BATCH
        );
    }

    synchronized int size() {
        return pending.size();
    }

    static String transportErrorCode(Stage stage, int statusCode, String serverError) {
        String candidate = serverError == null
            ? "request_failed"
            : serverError.trim().toLowerCase(Locale.ROOT);
        if (!candidate.matches("[a-z0-9_]{1,80}")) candidate = "request_failed";
        int boundedStatus = statusCode >= 100 && statusCode <= 599 ? statusCode : 0;
        return "action_delivery_" + stage.diagnosticName() + "_http_" +
            boundedStatus + "_" + candidate;
    }
}
