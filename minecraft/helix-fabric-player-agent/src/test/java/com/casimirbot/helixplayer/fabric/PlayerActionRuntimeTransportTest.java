package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.*;
import com.casimirbot.helixsensor.HelixJson;
import com.casimirbot.helixsensor.snapshot.SectionHasher;
import com.sun.net.httpserver.HttpServer;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class PlayerActionRuntimeTransportTest {
    @TempDir Path directory;

    @Test
    void evidenceRetryContinuesAfterActionPollingStops() {
        assertFalse(PlayerActionRuntime.deliveryRetryDue(0, true));
        assertFalse(PlayerActionRuntime.deliveryRetryDue(100, false));
        assertFalse(PlayerActionRuntime.deliveryRetryDue(99, true));
        assertTrue(PlayerActionRuntime.deliveryRetryDue(100, true));
        assertTrue(PlayerActionRuntime.deliveryRetryDue(200, true));
    }

    @Test
    void delayedProjectionDoesNotBlockOneShotActionPolling() throws Exception {
        var projectionEntered = new CountDownLatch(1);
        var releaseProjection = new CountDownLatch(1);
        var criticalEventDelivered = new CountDownLatch(1);
        var terminalResultDelivered = new CountDownLatch(1);
        var pendingActionPolls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService serverExecutor = Executors.newFixedThreadPool(4);
        server.setExecutor(serverExecutor);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            Map<String, Object> body = path.endsWith("/events/batch")
                ? HelixJson.asObject(HelixJson.parse(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)))
                : Map.of();
            Map<String, Object> response;
            if (path.endsWith("/controls/pending")) {
                response = Map.of("ok", true, "control_requests", List.of());
            } else if (path.endsWith("/requests/pending")) {
                pendingActionPolls.incrementAndGet();
                response = Map.of("ok", true, "action_requests", List.of());
            } else if (path.endsWith("/events/batch")) {
                projectionEntered.countDown();
                boolean released;
                try {
                    released = releaseProjection.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    released = false;
                }
                if (!released) {
                    exchange.sendResponseHeaders(504, -1);
                    exchange.close();
                    return;
                }
                response = Map.of("schema", "helix.environment_event_batch_receipt.v1",
                    "ok", true, "batch_id", body.get("batch_id"),
                    "latest_event_sequence", body.get("last_sequence"));
            } else if (path.endsWith("/requests/event")) {
                criticalEventDelivered.countDown();
                response = Map.of("ok", true);
            } else if (path.endsWith("/requests/result")) {
                terminalResultDelivered.countDown();
                response = Map.of("ok", true);
            } else {
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
                return;
            }
            byte[] bytes = HelixJson.stringifyIncludingNulls(response).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var config = new PlayerActionConfig("http://127.0.0.1:" + server.getAddress().getPort(),
            "helix_env_action_" + "x".repeat(43), "authority", "installation", "env", "room", "source", "world",
            "adapter", PlayerActionConfig.DOMAIN_ADAPTER, "participant", "subject", "player", 1,
            Instant.now().plusSeconds(120).toString());
        PlayerActionRuntime runtime = null;
        try {
            runtime = new PlayerActionRuntime(config, null, LoggerFactory.getLogger(getClass()), ignored -> {},
                Runnable::run, directory.resolve("unused-delayed-projection.json"));
            var outbox = (PlayerActionDeliveryOutbox) get(runtime, "deliveryOutbox");
            assertTrue(outbox.enqueueSequence(List.of(new PlayerActionDeliveryOutbox.Delivery(
                PlayerActionDeliveryOutbox.Stage.ENVIRONMENT_EVENT_BATCH,
                Map.of("schema", "helix.environment_event_batch.v1", "batch_id", "batch:delayed",
                    "first_sequence", 0, "last_sequence", 0, "events", List.of(Map.of()),
                    "batch_hash", "sha256:fixture"))), 0));

            Method poll = PlayerActionRuntime.class.getDeclaredMethod("pollControlsThenActions");
            poll.setAccessible(true);
            poll.invoke(runtime);

            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS),
                "Projection request must be held in flight; transport=" + get(runtime, "lastTransportError"));
            assertEquals(1, pendingActionPolls.get(), "Projection backpressure must not suppress the broker poll");
            assertFalse(outbox.isProjectionEmpty(), "Delayed projection remains retained until its receipt");

            assertTrue(outbox.enqueueSequence(List.of(
                new PlayerActionDeliveryOutbox.Delivery(
                    PlayerActionDeliveryOutbox.Stage.WORKFLOW_EVENT, Map.of("event_id", "event:terminal")),
                new PlayerActionDeliveryOutbox.Delivery(
                    PlayerActionDeliveryOutbox.Stage.ACTION_RESULT, Map.of("action_request_id", "request:terminal"))), 0));
            Method flush = PlayerActionRuntime.class.getDeclaredMethod("scheduleDeliveryFlush");
            flush.setAccessible(true);
            flush.invoke(runtime);
            assertTrue(criticalEventDelivered.await(2, TimeUnit.SECONDS),
                "Authoritative workflow event must bypass delayed projection delivery");
            assertTrue(terminalResultDelivered.await(2, TimeUnit.SECONDS),
                "Terminal broker observation must settle while the projection is still delayed");

            releaseProjection.countDown();
            ((ExecutorService) get(runtime, "projectionDeliveryNetwork")).submit(() -> {}).get(5, TimeUnit.SECONDS);
            ((ExecutorService) get(runtime, "criticalDeliveryNetwork")).submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertTrue(outbox.isEmpty(), "The acknowledged projection is removed exactly once");
            assertEquals(1, pendingActionPolls.get(), "The one-shot request endpoint is not replayed by delivery retry");
        } finally {
            releaseProjection.countDown();
            if (runtime != null) {
                for (String lane : List.of("network", "criticalDeliveryNetwork", "projectionDeliveryNetwork"))
                    ((ExecutorService) get(runtime, lane)).shutdownNow();
                ((TemporalDeliveryLane) get(runtime, "temporalDeliveryLane")).close();
                ((PlayerActionHttpClient) get(runtime, "http")).close();
            }
            server.stop(0);
            serverExecutor.shutdownNow();
        }
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "HELIX_NATIVE_COMPILED_HANDOFF", matches = "1")
    void continuousCompiledChainPollsThreeSuccessorsInOneResidentRuntime() throws Exception {
        exerciseContinuousChain("success");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "HELIX_NATIVE_COMPILED_HANDOFF", matches = "1")
    void continuousCompiledChainCancellationDiscardsThirdSuccessor() throws Exception {
        exerciseContinuousChain("cancel");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "HELIX_NATIVE_COMPILED_HANDOFF", matches = "1")
    void continuousCompiledChainEmergencyStopDiscardsThirdSuccessor() throws Exception {
        exerciseContinuousChain("emergency");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "HELIX_NATIVE_COMPILED_HANDOFF", matches = "1")
    void continuousCompiledChainManualObservationDiscardsThirdSuccessor() throws Exception {
        exerciseContinuousChain("manual");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "HELIX_NATIVE_COMPILED_HANDOFF", matches = "1")
    void continuousCompiledChainLostThirdResponseNeverReplaysDelivery() throws Exception {
        exerciseContinuousChain("lost-third");
    }

    private void exerciseContinuousChain(String outcome) throws Exception {
        boolean lostThird = "lost-third".equals(outcome);
        boolean interrupted = !"success".equals(outcome) && !lostThird;
        List<Map<String, Object>> compiled = ((List<?>) HelixJson.parse(java.nio.file.Files.readString(
            Path.of("build", "server-compiled-continuous-chain.json")))).stream().map(HelixJson::asObject).toList();
        assertEquals(4, compiled.size());
        List<Map<String, Object>> wires = new ArrayList<>();
        for (int index = 0; index < compiled.size(); index++) {
            Map<String, Object> fixture = compiled.get(index);
            Map<String, Object> artifact = HelixJson.asObject(fixture.get("artifact"));
            Map<String, Object> wire = new LinkedHashMap<>();
            wire.put("action_request_id", index == 0 ? "root" : "child:" + index);
            wire.put("run_id", "run"); wire.put("workflow_id", "workflow");
            wire.put("action_authority_id", "authority"); wire.put("environment_binding_id", "env");
            wire.put("source_id", "source"); wire.put("room_id", "room"); wire.put("world_id", "world");
            wire.put("participant_id", "participant"); wire.put("subject_binding_id", "subject"); wire.put("subject_native_id", "player");
            wire.put("capability_id", "com.casimirbot.minecraft.player.sequence.execute"); wire.put("capability_version", 1);
            wire.put("action_kind", "execute_sequence"); wire.put("requested_control_engine", "native_fabric");
            wire.put("postconditions", List.of(Map.of("condition_id", "fixture:checkpoint",
                "condition_kind", "checkpoint", "required", true, "parameters", Map.of())));
            wire.put("temporal_plan", fixture.get("source")); wire.put("arguments", artifact.get("arguments"));
            wire.put("temporal_plan_canonical_json", fixture.get("plan_canonical_json"));
            wire.put("temporal_compilation_canonical_json", fixture.get("compilation_canonical_json"));
            wire.put("temporal_compilation_hash", artifact.get("compilation_hash"));
            wires.add(wire);
        }
        List<Map<String, Object>> polls = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> publications = new CopyOnWriteArrayList<>();
        AtomicInteger reconciliations = new AtomicInteger();
        String brokerOrigin = System.getenv("HELIX_NATIVE_BROKER_FIXTURE_ORIGIN");
        if (lostThird && brokerOrigin != null && !"1".equals(System.getenv("HELIX_NATIVE_BROKER_CONTINUOUS_LOST_THIRD")))
            throw new IllegalArgumentException("Lost-third broker mode must be explicit");
        if (brokerOrigin != null && !brokerOrigin.matches("http://127\\.0\\.0\\.1:[0-9]+"))
            throw new IllegalArgumentException("Broker fixture must use an isolated loopback origin");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            Map<String, Object> body = HelixJson.asObject(HelixJson.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            if (brokerOrigin != null) {
                if (exchange.getRequestURI().getPath().equals("/requests/temporal-successor")) polls.add(body);
                else if (exchange.getRequestURI().getPath().equals("/requests/temporal-successor/status")) reconciliations.incrementAndGet();
                else publications.add(Map.of("path", exchange.getRequestURI().getPath(), "body", body));
                forwardToBroker(exchange, brokerOrigin + exchange.getRequestURI().getPath(), HelixJson.stringifyIncludingNulls(body));
                return;
            }
            Map<String, Object> response = new LinkedHashMap<>(Map.of("ok", true));
            if (exchange.getRequestURI().getPath().equals("/requests/temporal-successor")) {
                polls.add(body);
                if (lostThird && polls.size() == 3) {
                    exchange.sendResponseHeaders(503, -1); exchange.close(); return;
                }
                String predecessor = String.valueOf(body.get("predecessor_plan_id"));
                Map<String, Object> next = wires.stream().skip(1).filter(wire -> predecessor.equals(
                    HelixJson.asObject(wire.get("temporal_plan")).get("previous_plan_id"))).findFirst().orElse(null);
                response.put("action_request", next);
            } else if (exchange.getRequestURI().getPath().equals("/requests/temporal-successor/status")) {
                reconciliations.incrementAndGet();
                response.put("delivery_state", Map.of("recorded_status", "leased", "execution_authority", false));
                response.put("automatic_replay_allowed", false);
            } else {
                publications.add(Map.of("path", exchange.getRequestURI().getPath(), "body", body));
                if (exchange.getRequestURI().getPath().equals("/events/batch")) {
                    response.put("schema", "helix.environment_event_batch_receipt.v1");
                    response.put("batch_id", body.get("batch_id"));
                    response.put("latest_event_sequence", body.get("last_sequence"));
                } else if (exchange.getRequestURI().getPath().equals("/requests/events")) {
                    response.put("schema", "helix.environment_action.events_receipt.v1");
                    response.put("event_ids", HelixJson.asList(body.get("events")).stream()
                        .map(event -> HelixJson.asObject(event).get("event_id")).toList());
                }
            }
            byte[] bytes = HelixJson.stringifyIncludingNulls(response).getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        var config = new PlayerActionConfig("http://127.0.0.1:" + server.getAddress().getPort(),
            "helix_env_action_" + "x".repeat(43), "authority", "installation", "env", "room", "source", "world",
            "adapter", PlayerActionConfig.DOMAIN_ADAPTER, "participant", "subject", "player", 1,
            Instant.now().plusSeconds(120).toString());
        PlayerActionRuntime runtime = null;
        try {
            runtime = new PlayerActionRuntime(config, null, LoggerFactory.getLogger(getClass()), ignored -> {},
                Runnable::run, directory.resolve("unused-continuous.json"));
            set(runtime, "producerEpochRef", "fixture:producer_epoch");
            set(runtime, "monotonicClock", new EnvironmentMonotonicClock("fixture:clock", () -> 0L));
            var bridge = new FluidSequenceEngineTest.SequenceBridge();
            set(runtime, "evidenceSnapshot", (java.util.function.Supplier<PlayerActionWorkflow.PlayerSnapshot>) bridge::snapshot);
            set(runtime, "latestClockSnapshot", simulatedClock("fixture:clock", 0));
            List<PlayerActionWorkflow.WorkflowEvent> events = new ArrayList<>();
            Method onEvent = PlayerActionRuntime.class.getDeclaredMethod("onWorkflowEvent", PlayerActionWorkflow.WorkflowEvent.class);
            onEvent.setAccessible(true);
            PlayerActionRuntime target = runtime;
            var callbackFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var controller = new PlayerActionController(bridge, event -> {
                events.add(event);
                try { onEvent.invoke(target, event); } catch (Exception error) {
                    callbackFailure.compareAndSet(null, error.getCause() == null ? error : error.getCause());
                    throw new RuntimeException(error);
                }
            });
            Object state = construct("TemporalDeliveryState", wires.get(0));
            set(runtime, "temporalDeliveryState", state); set(runtime, "controller", controller);
            set(runtime, "activeEnvelope", construct("ActiveEnvelope", wires.get(0), "execution", Instant.now().toString(),
                simulatedClock("fixture:clock", 0), "native_fabric", new ArrayList<String>(), new EnvironmentCapacityTelemetry(0, 0, 0, 0, 200)));
            controller.start(new PlayerActionWorkflow.ActionRequest("root", "workflow", "execute_sequence",
                HelixJson.asObject(wires.get(0).get("arguments")), 200, PlayerActionWorkflow.ManualOverridePolicy.CANCEL, "native_fabric"));
            var tick = new java.util.concurrent.atomic.AtomicLong();
            assertTrue(controller.attachTemporalWindow("root", new TemporalPlanWindow("fixture:clock", 0, 0, 20, 21, 200, 60000),
                () -> new PlayerActionController.TemporalClockSample("fixture:clock", tick.get(), tick.get() * 50), null));
            Method poll = PlayerActionRuntime.class.getDeclaredMethod("pollTemporalSuccessor"); poll.setAccessible(true);
            int movingTicks = 0;
            int expectedStopTick = lostThird ? 62 : interrupted ? 43 : 84;
            for (int currentTick = 0; currentTick <= 84; currentTick++) {
                tick.set(currentTick); set(runtime, "latestClockSnapshot", simulatedClock("fixture:clock", currentTick));
                bridge.snapshot = new PlayerActionWorkflow.PlayerSnapshot(true, currentTick * 0.1, 64, 65.62, 0, 0, 0, 20, true, false, false, null);
                if (interrupted && currentTick == 43) {
                    assertNotNull(get(state, "queuedWire"), "Third successor must be queued before stopping");
                    if ("manual".equals(outcome)) {
                        bridge.snapshot = new PlayerActionWorkflow.PlayerSnapshot(true, currentTick * 0.1, 64, 65.62,
                            0, 0, 0, 20, true, false, true, "forward_key_pressed");
                    } else {
                        assertTrue("emergency".equals(outcome) ? controller.emergencyStop("fixture operator stop")
                            : controller.cancel("workflow", "fixture operator cancellation"));
                    }
                }
                controller.tick();
                if (currentTick < expectedStopTick) { assertTrue(bridge.movement.forward(), "Released tick " + currentTick); movingTicks++; }
                else assertFalse(bridge.movement.forward(), "Motion resumed after terminal tick " + currentTick);
                for (String lane : List.of("criticalDeliveryNetwork", "projectionDeliveryNetwork"))
                    ((ExecutorService) get(runtime, lane)).submit(() -> {}).get(5, TimeUnit.SECONDS);
                if (currentTick == 0 || currentTick == 21 || currentTick == 42) {
                    if (currentTick == 0 && "1".equals(System.getenv("HELIX_NATIVE_BROKER_CONTINUOUS_LOST_BATCH_ACK"))) {
                        var outbox = (PlayerActionDeliveryOutbox) get(runtime, "deliveryOutbox");
                        assertFalse(outbox.isProjectionEmpty(), "Unacknowledged committed batch must remain queued");
                        poll.invoke(runtime);
                        assertEquals(0, polls.size(), "Evidence backpressure must defer successor polling");
                        assertNull(get(state, "queuedWire"));
                        Method retryDelivery = PlayerActionRuntime.class.getDeclaredMethod("scheduleDeliveryFlush");
                        retryDelivery.setAccessible(true); retryDelivery.invoke(runtime);
                        for (String lane : List.of("criticalDeliveryNetwork", "projectionDeliveryNetwork"))
                            ((ExecutorService) get(runtime, lane)).submit(() -> {}).get(5, TimeUnit.SECONDS);
                        assertTrue(outbox.isProjectionEmpty(), "Exact batch replay must clear evidence backpressure");
                    }
                    int before = polls.size(); poll.invoke(runtime);
                    if (lostThird && currentTick == 42) {
                        assertNull(get(state, "queuedWire"));
                        assertNotNull(get(state, "uncertainPoll"));
                        for (int retry = 0; retry < 3; retry++) poll.invoke(runtime);
                        assertEquals(3, polls.size(), "Reconciliation must not redeliver the third successor");
                        assertEquals(3, reconciliations.get());
                        assertNull(get(state, "queuedWire"));
                        assertNotNull(get(state, "uncertainPoll"));
                        assertEquals(2, events.get(events.size() - 1).measurements().get("resident_handoff_count"));
                        assertTrue(events.stream().noneMatch(event -> "plan:continuous:3".equals(event.measurements().get("sequence_id"))));
                        assertEquals(43, movingTicks);
                    } else {
                        String deliveryDiagnostic = events +
                            " pending=" + ((PlayerActionDeliveryOutbox) get(target, "deliveryOutbox")).size() +
                            " transport=" + get(target, "lastTransportError") +
                            " watermark=" + get(state, "checkpointDeliveryWatermark");
                        assertNotNull(get(state, "queuedWire"), deliveryDiagnostic);
                        poll.invoke(runtime); assertEquals(before + 1, polls.size(), "Queued successor suppresses duplicate polling");
                    }
                }
                if ((interrupted || lostThird) && currentTick >= expectedStopTick) {
                    poll.invoke(runtime);
                    assertEquals(3, polls.size(), "Stopped runtime must not request further delivery");
                    assertNull(get(runtime, "activeEnvelope"));
                    assertNull(get(runtime, "temporalDeliveryState"));
                }
            }
            assertEquals(expectedStopTick, movingTicks); assertEquals(3, polls.size());
            if ("manual".equals(outcome)) {
                assertEquals("forward_key_pressed", events.get(events.size() - 1).measurements().get("manual_input_reason"));
            }
            if (lostThird) {
                assertEquals(3, reconciliations.get(), "Terminal state must also stop status polling");
                assertEquals("temporal_runway_exhausted", events.get(events.size() - 1).measurements().get("temporal_window_reason"));
            }
            assertNull(callbackFailure.get(), () -> Arrays.toString(callbackFailure.get().getStackTrace()));
            assertEquals(!interrupted && !lostThird ? PlayerActionWorkflow.State.SUCCEEDED : "emergency".equals(outcome)
                ? PlayerActionWorkflow.State.EMERGENCY_STOPPED : PlayerActionWorkflow.State.CANCELED, controller.state(),
                () -> events.get(events.size() - 1).summary() + " " + events.get(events.size() - 1).measurements());
            assertFalse(bridge.movement.forward()); assertTrue(bridge.released);
            assertEquals(interrupted || lostThird ? 2 : 3, events.get(events.size() - 1).measurements().get("resident_handoff_count"));
            if (interrupted || lostThird) assertTrue(events.stream().noneMatch(event ->
                "plan:continuous:3".equals(event.measurements().get("sequence_id"))), "Queued third successor must never activate");
            List<Map<String, Object>> results = publications.stream()
                .filter(item -> "/requests/result".equals(item.get("path")))
                .map(item -> HelixJson.asObject(item.get("body"))).toList();
            assertEquals(1, results.size(), "One resident execution publishes one terminal result");
            assertEquals("root", results.get(0).get("action_request_id"));
            assertEquals(!interrupted && !lostThird ? "succeeded" : "emergency".equals(outcome) ? "emergency_stopped" : "request_canceled", results.get(0).get("outcome"));
            assertEquals(true, results.get(0).get("player_motion_performed"));
            assertEquals(true, results.get(0).get("controls_released"));
            assertEquals(false, results.get(0).get("automatic_replay_performed"));
            for (int index = 0; index < 3; index++) {
                assertEquals("plan:continuous:" + index, polls.get(index).get("predecessor_plan_id"));
                assertEquals("root", polls.get(index).get("resident_action_request_id"));
            }
            java.nio.file.Files.writeString(Path.of("build", lostThird ? "native-continuous-lost-third-deadline.json" : interrupted ? "native-continuous-" + outcome + ".json" : "native-continuous-runtime.json"),
                HelixJson.stringifyIncludingNulls(Map.of("wires", wires, "polls", polls, "publications", publications,
                    "moving_ticks", movingTicks, "reconciliations", reconciliations.get(), "stop_tick", expectedStopTick, "live_acceptance", false)), StandardCharsets.UTF_8);
        } finally {
            try {
                if (runtime != null) {
                    for (String lane : List.of("network", "criticalDeliveryNetwork", "projectionDeliveryNetwork"))
                        ((ExecutorService) get(runtime, lane)).shutdownNow();
                    ((TemporalDeliveryLane) get(runtime, "temporalDeliveryLane")).close();
                    ((PlayerActionHttpClient) get(runtime, "http")).close();
                }
            } finally { server.stop(0); }
        }
    }

    @Test void workflowBatchRetriesExactPayloadAfterLostResponse() throws Exception {
        exerciseWorkflowBatch("lost");
    }

    @Test void workflowBatchRejectsReorderedReceiptWithoutDroppingEvidence() throws Exception {
        exerciseWorkflowBatch("reordered");
    }

    @Test void legacyWorkflowTransportRemainsSingleton() throws Exception {
        exerciseWorkflowBatch("legacy");
    }

    private void exerciseWorkflowBatch(String mode) throws Exception {
        List<String> bodies = new CopyOnWriteArrayList<>();
        List<String> paths = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/requests/event", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (bodies.size() == 1 && mode.equals("lost")) {
                exchange.close();
                return;
            }
            List<String> ids = bodies.size() == 1 && mode.equals("reordered")
                ? List.of("event:1", "event:0") : List.of("event:0", "event:1");
            byte[] receipt = HelixJson.stringifyIncludingNulls(Map.of("ok", true,
                "schema", "helix.environment_action.events_receipt.v1", "event_ids", ids))
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, receipt.length);
            exchange.getResponseBody().write(receipt);
            exchange.close();
        });
        server.start();
        var config = new PlayerActionConfig("http://127.0.0.1:" + server.getAddress().getPort(),
            "helix_env_action_" + "x".repeat(43), "authority", "installation", "env", "room", "source", "world",
            "adapter", PlayerActionConfig.DOMAIN_ADAPTER, "participant", "subject", "player", 1,
            Instant.now().plusSeconds(120).toString());
        PlayerActionRuntime runtime = null;
        try {
            runtime = new PlayerActionRuntime(config, null, LoggerFactory.getLogger(getClass()), ignored -> {},
                Runnable::run, directory.resolve("unused-batch.json"));
            set(runtime, "workflowEventBatchSupported", !mode.equals("legacy"));
            var box = (PlayerActionDeliveryOutbox) get(runtime, "deliveryOutbox");
            for (int sequence = 0; sequence < 2; sequence++) {
                assertTrue(box.enqueueSequence(List.of(new PlayerActionDeliveryOutbox.Delivery(
                    PlayerActionDeliveryOutbox.Stage.WORKFLOW_EVENT, Map.of("event_id", "event:" + sequence,
                        "workflow_id", "workflow", "action_request_id", "request", "sequence", sequence))), 0));
            }
            Method flush = PlayerActionRuntime.class.getDeclaredMethod("flushCriticalDeliveryOutbox");
            flush.setAccessible(true);
            if (!mode.equals("legacy")) {
                assertEquals(false, flush.invoke(runtime));
                assertEquals(2, box.size(), "Uncertain or mismatched receipt must retain every event");
                set(runtime, "workflowEventBatchSupported", false);
            }
            assertEquals(true, flush.invoke(runtime));
            assertTrue(box.isEmpty());
            assertEquals(2, bodies.size());
            assertEquals(List.of(mode.equals("legacy") ? "/requests/event" : "/requests/events",
                mode.equals("legacy") ? "/requests/event" : "/requests/events"), paths);
            if (!mode.equals("legacy")) assertEquals(bodies.get(0), bodies.get(1));
        } finally {
            if (runtime != null) {
                for (String lane : List.of("network", "criticalDeliveryNetwork", "projectionDeliveryNetwork"))
                    ((ExecutorService) get(runtime, lane)).shutdownNow();
                ((TemporalDeliveryLane) get(runtime, "temporalDeliveryLane")).close();
                ((PlayerActionHttpClient) get(runtime, "http")).close();
            }
            server.stop(0);
        }
    }

    @Test void validHttpSuccessorPassesNativePreflightAndActivatesAtCommittedTick() throws Exception {
        exerciseHandoff(false);
    }

    @Test void responseAfterReservationWindowDoesNotExecuteSuccessor() throws Exception {
        exerciseHandoff(true);
    }

    @Test void failedProjectionAcknowledgementRetriesEvidenceNotTheAction() throws Exception {
        exerciseHandoff(false, true);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "HELIX_NATIVE_COMPILED_HANDOFF", matches = "1")
    void serverCompiledWalkPassesRuntimeHandoffAndPublication() throws Exception {
        exerciseHandoff(false, false, true);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "HELIX_NATIVE_COMPILED_HANDOFF", matches = "1")
    void serverCompiledLateResponsePublishesCancellationResult() throws Exception {
        exerciseHandoff(true, false, true);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "HELIX_NATIVE_COMPILED_HANDOFF", matches = "1")
    void elapsedClockExpiresWhileSuccessorHttpIsInFlight() throws Exception {
        exerciseHandoff(true, false, true, true);
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "HELIX_NATIVE_COMPILED_HANDOFF", matches = "1")
    void elapsedClockAcceptsWithinDeclaredWideRunway() throws Exception {
        exerciseHandoff(false, false, true, true);
    }

    private void exerciseHandoff(boolean late) throws Exception {
        exerciseHandoff(late, false);
    }

    private void exerciseHandoff(boolean late, boolean rejectProjection) throws Exception {
        exerciseHandoff(late, rejectProjection, false);
    }

    private void exerciseHandoff(boolean late, boolean rejectProjection, boolean serverCompiled) throws Exception {
        exerciseHandoff(late, rejectProjection, serverCompiled, false);
    }

    private void exerciseHandoff(boolean late, boolean rejectProjection, boolean serverCompiled, boolean advancingClock) throws Exception {
        boolean wideRunway = advancingClock && !late;
        String brokerOrigin = serverCompiled ? System.getenv("HELIX_NATIVE_BROKER_FIXTURE_ORIGIN") : null;
        boolean lostBrokerResponse = brokerOrigin != null && "1".equals(System.getenv("HELIX_NATIVE_BROKER_LOST_RESPONSE"));
        if (brokerOrigin != null && !brokerOrigin.matches("http://127\\.0\\.0\\.1:[0-9]+"))
            throw new IllegalArgumentException("Fixture broker must be loopback");
        Map<String, Object> pair = serverCompiled ? HelixJson.asObject(HelixJson.parse(
            java.nio.file.Files.readString(Path.of("build", wideRunway ? "server-compiled-handoff-wide.json" : "server-compiled-handoff.json")))) : Map.of();
        var payload = new java.util.concurrent.atomic.AtomicReference<String>();
        var beforeResponse = new java.util.concurrent.atomic.AtomicReference<Runnable>(() -> {});
        var deliveries = new AtomicInteger();
        var reconciliations = new AtomicInteger();
        List<Map<String, Object>> publishedEvents = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> publishedBatches = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> publishedResults = new CopyOnWriteArrayList<>();
        var projectionRejected = new java.util.concurrent.atomic.AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        for (String path : List.of("/requests/event", "/events/batch", "/requests/result")) {
            server.createContext(path, exchange -> {
                Map<String, Object> body = HelixJson.asObject(HelixJson.parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                if (path.equals("/requests/event")) publishedEvents.add(body);
                if (path.equals("/events/batch")) publishedBatches.add(body);
                if (path.equals("/requests/result")) publishedResults.add(body);
                if (brokerOrigin != null) {
                    forwardToBroker(exchange, brokerOrigin + path, HelixJson.stringifyIncludingNulls(body));
                    return;
                }
                boolean rejectBatch = path.equals("/events/batch") && rejectProjection &&
                    projectionRejected.compareAndSet(false, true);
                byte[] receipt = (path.equals("/events/batch") && !rejectBatch
                    ? HelixJson.stringifyIncludingNulls(Map.of(
                        "ok", true,
                        "schema", "helix.environment_event_batch_receipt.v1",
                        "batch_id", body.get("batch_id"),
                        "latest_event_sequence", body.get("last_sequence")
                    ))
                    : "{\"ok\":true}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(rejectBatch ? 503 : 200, receipt.length);
                exchange.getResponseBody().write(receipt);
                exchange.close();
            });
        }
        server.createContext("/requests/temporal-successor", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean statusOnly = exchange.getRequestURI().getPath().endsWith("/status");
            if (statusOnly) reconciliations.incrementAndGet(); else deliveries.incrementAndGet();
            beforeResponse.get().run();
            if (brokerOrigin != null) {
                forwardToBroker(exchange, brokerOrigin + exchange.getRequestURI().getPath(), requestBody);
                return;
            }
            byte[] bytes = payload.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var config = new PlayerActionConfig("http://127.0.0.1:" + server.getAddress().getPort(),
            "helix_env_action_" + "x".repeat(43), "authority", "installation", "env", "room", "source", "world",
            "adapter", PlayerActionConfig.DOMAIN_ADAPTER, "participant", "subject", "player", 1,
            Instant.now().plusSeconds(120).toString());
        PlayerActionRuntime runtime = null;
        try {
            runtime = new PlayerActionRuntime(config, null, LoggerFactory.getLogger(getClass()), ignored -> {},
                Runnable::run, directory.resolve("unused-handoff.json"));
            if (serverCompiled) {
                set(runtime, "producerEpochRef", "fixture:producer_epoch");
                set(runtime, "monotonicClock", new EnvironmentMonotonicClock("fixture:clock", () -> 0L));
            }
            String epoch = (String) get(runtime, "producerEpochRef");
            String origin = (String) ((EnvironmentMonotonicClock) get(runtime, "monotonicClock")).snapshot().get("origin_id");
            Map<String, Object> current = new LinkedHashMap<>(Map.of("action_request_id", "root", "run_id", "run",
                "action_authority_id", "authority", "environment_binding_id", "env", "source_id", "source",
                "room_id", "room", "world_id", "world", "participant_id", "participant", "subject_binding_id", "subject",
                "subject_native_id", "player"));
            current.put("workflow_id", "workflow");
            current.put("capability_id", "com.casimirbot.minecraft.player.sequence.execute");
            current.put("capability_version", 1);
            current.put("action_kind", "execute_sequence");
            current.put("postconditions", List.of(Map.of("condition_id", "fixture:checkpoint",
                "condition_kind", "checkpoint", "required", true, "parameters", Map.of())));
            current.put("temporal_plan", Map.of("plan_id", "plan:0", "plan_hash", "previous-hash",
                "identity", Map.of("goal_id", "goal"), "nodes", List.of(Map.of("kind", "checkpoint", "checkpoint_id", "cp"))));
            Map<String, Object> args = FluidSequenceEngineTest.residentSequence("plan:1");
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("schema", "environment.temporal_action_plan.v1");
            plan.put("plan_id", "plan:1");
            plan.put("previous_plan_id", "plan:0");
            plan.put("previous_plan_hash", "previous-hash");
            plan.put("identity", Map.of("environment_id", "env", "source_id", "source", "subject_id", "subject",
                "authority_id", "authority", "authority_revision", 1, "producer_epoch", epoch, "goal_id", "goal", "goal_revision", 1));
            for (String key : List.of("automatic_replay", "adapter_strategy_authority", "answer_authority", "assistant_answer", "terminal_eligible")) plan.put(key, false);
            plan.put("clocks", Map.of("environment", Map.of("kind", "tick", "resolution_unit", "minecraft_tick", "sequence", 0),
                "monotonic", Map.of("origin_id", origin, "elapsed_ms", 0)));
            plan.put("watermarks", Map.of("decision_unit", 0, "stop_unit", 3, "committed_through_unit", 4, "stabilization_node_id", "failed"));
            plan.put("maximum_total_units", 200);
            plan.put("monotonic_deadline_elapsed_ms", 60000);
            String canonical = HelixJson.stringifyIncludingNulls(plan);
            plan.put("plan_hash", SectionHasher.hashIncludingNulls(plan));
            Map<String, Object> next = new LinkedHashMap<>(current);
            next.put("action_request_id", "child"); next.put("temporal_plan", plan);
            next.put("temporal_plan_canonical_json", canonical);
            next.put("arguments", args); next.put("action_kind", "execute_sequence"); next.put("requested_control_engine", "native_fabric");
            Map<String, Object> compiled = new LinkedHashMap<>(Map.of("schema", "environment.minecraft_temporal_plan_compilation.v1",
                "source_plan_id", "plan:1", "source_plan_hash", plan.get("plan_hash"), "source_goal_id", "goal", "source_goal_revision", 1,
                "arguments", args, "target_schema", args.get("sequence_schema")));
            for (String key : List.of("execution_authority", "answer_authority", "assistant_answer", "terminal_eligible")) compiled.put(key, false);
            next.put("temporal_compilation_canonical_json", HelixJson.stringifyIncludingNulls(compiled));
            next.put("temporal_compilation_hash", SectionHasher.hashIncludingNulls(compiled));
            Map<String, Object> rootArguments = FluidSequenceEngineTest.residentSequence("plan:0");
            if (serverCompiled) {
                Map<String, Object> rootFixture = HelixJson.asObject(pair.get("root"));
                Map<String, Object> childFixture = HelixJson.asObject(pair.get("child"));
                current.put("temporal_plan", rootFixture.get("source"));
                rootArguments = HelixJson.asObject(HelixJson.asObject(rootFixture.get("artifact")).get("arguments"));
                current.put("arguments", rootArguments);
                Map<String, Object> childSource = new LinkedHashMap<>(HelixJson.asObject(childFixture.get("source")));
                next.put("temporal_plan", childSource);
                childSource.remove("plan_hash");
                next.put("temporal_plan_canonical_json", childFixture.get("plan_canonical_json"));
                // Restore the hash-bearing source independently of canonical content.
                next.put("temporal_plan", childFixture.get("source"));
                Map<String, Object> childArtifact = new LinkedHashMap<>(HelixJson.asObject(childFixture.get("artifact")));
                next.put("arguments", childArtifact.get("arguments"));
                next.put("temporal_compilation_hash", childArtifact.remove("compilation_hash"));
                next.put("temporal_compilation_canonical_json", childFixture.get("compilation_canonical_json"));
            }
            payload.set(HelixJson.stringifyIncludingNulls(Map.of("ok", true, "action_request", next)));
            var bridge = new FluidSequenceEngineTest.SequenceBridge();
            set(runtime, "evidenceSnapshot", (java.util.function.Supplier<PlayerActionWorkflow.PlayerSnapshot>) bridge::snapshot);
            set(runtime, "latestClockSnapshot", simulatedClock(origin, 0));
            List<PlayerActionWorkflow.WorkflowEvent> events = new ArrayList<>();
            var flightStart = new java.util.concurrent.atomic.AtomicLong();
            Method onEvent = PlayerActionRuntime.class.getDeclaredMethod("onWorkflowEvent", PlayerActionWorkflow.WorkflowEvent.class);
            onEvent.setAccessible(true);
            PlayerActionRuntime target = runtime;
            var controller = new PlayerActionController(bridge, event -> {
                events.add(event);
                try {
                    if (wideRunway && flightStart.get() != 0) {
                        long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - flightStart.get());
                        Map<String, Object> advancingSnapshot = simulatedClock(origin, elapsed / 50);
                        advancingSnapshot.put("monotonic", Map.of("origin_id", origin, "elapsed_ms", elapsed));
                        set(target, "latestClockSnapshot", advancingSnapshot);
                    }
                    onEvent.invoke(target, event);
                }
                catch (Exception error) { throw new RuntimeException(error); }
            });
            Object state = construct("TemporalDeliveryState", current);
            set(runtime, "temporalDeliveryState", state);
            set(runtime, "controller", controller);
            var capacityTelemetry = new EnvironmentCapacityTelemetry(0, 0, 0, 0, 200);
            Map<String, Object> fixtureBudget = new LinkedHashMap<>();
            set(runtime, "activeEnvelope", construct("ActiveEnvelope", current, "execution", Instant.now().toString(), simulatedClock(origin, 0), "native_fabric",
                new ArrayList<String>(), capacityTelemetry));
            controller.start(new PlayerActionWorkflow.ActionRequest("root", "workflow", "execute_sequence",
                rootArguments, 200, PlayerActionWorkflow.ManualOverridePolicy.CANCEL, "native_fabric"));
            var tick = new java.util.concurrent.atomic.AtomicLong();
            if (advancingClock && late) beforeResponse.set(() -> {
                // Delay transport, not the client clock. No manual tick jump.
                long deadline = flightStart.get() + TimeUnit.MILLISECONDS.toNanos(100);
                while (System.nanoTime() < deadline)
                    java.util.concurrent.locks.LockSupport.parkNanos(Math.min(TimeUnit.MILLISECONDS.toNanos(5), deadline - System.nanoTime()));
            });
            else if (late) beforeResponse.set(() -> tick.set(1));
            long stopTick = wideRunway ? 20 : 1;
            long committedTick = wideRunway ? 21 : 2;
            assertTrue(controller.attachTemporalWindow("root", new TemporalPlanWindow(origin, 0, 0, stopTick, committedTick, 200, 60000),
                () -> {
                    long elapsed = advancingClock && flightStart.get() != 0
                        ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - flightStart.get()) : tick.get() * 50;
                    return new PlayerActionController.TemporalClockSample(origin, elapsed / 50, elapsed);
                }, null));
            if (wideRunway) flightStart.set(System.nanoTime());
            controller.tick();
            if (wideRunway) {
                long checkpoint = ((Number) get(capacityTelemetry, "checkpointNanos")).longValue();
                assertTrue(checkpoint >= flightStart.get());
                long window = TimeUnit.NANOSECONDS.toMillis(flightStart.get() + TimeUnit.SECONDS.toNanos(1) - checkpoint);
                assertTrue(window > 0 && window <= 1000);
                fixtureBudget.put("checkpoint_to_stop_window_ms", window);
                fixtureBudget.put("safety_margin_ms", 10);
                fixtureBudget.put("basis", "elapsed_native_fixture_clock_50ms_per_tick");
                fixtureBudget.put("live_capacity", false);
            }
            for (String lane : List.of("criticalDeliveryNetwork", "projectionDeliveryNetwork"))
                ((ExecutorService) get(runtime, lane)).submit(() -> {}).get(5, TimeUnit.SECONDS);
            if (rejectProjection) {
                Method flush = PlayerActionRuntime.class.getDeclaredMethod("flushProjectionDeliveryOutbox");
                flush.setAccessible(true);
                assertEquals(true, flush.invoke(runtime));
            }
            Method poll = PlayerActionRuntime.class.getDeclaredMethod("pollTemporalSuccessor"); poll.setAccessible(true);
            if (advancingClock && !wideRunway) flightStart.set(System.nanoTime());
            poll.invoke(runtime);
            if (lostBrokerResponse) {
                assertNull(get(state, "queuedWire"));
                assertNotNull(get(state, "uncertainPoll"));
                poll.invoke(runtime);
                assertEquals(1, deliveries.get());
                assertEquals(1, reconciliations.get());
                assertNull(get(state, "queuedWire"));
                assertNotNull(get(state, "uncertainPoll"));
                assertEquals("root", HelixJson.asObject(get(state, "currentWire")).get("action_request_id"));
                assertEquals(0, bridge.jumpPulses);
                assertTrue(events.stream().noneMatch(event -> "plan:1".equals(event.measurements().get("sequence_id"))));
                java.nio.file.Files.writeString(Path.of("build", "native-compiled-uncertain.json"),
                    HelixJson.stringifyIncludingNulls(Map.of("batches", publishedBatches, "events", publishedEvents,
                        "root", current, "child", next, "deliveries", deliveries.get(), "reconciliations", reconciliations.get())), StandardCharsets.UTF_8);
                return;
            }
            if (late) {
                if (advancingClock) {
                    assertEquals(0, tick.get(), "No scripted tick advance may supply expiry");
                    assertTrue(System.nanoTime() - flightStart.get() >= TimeUnit.MILLISECONDS.toNanos(100));
                }
                assertNull(get(state, "queuedWire"));
                assertEquals(PlayerActionWorkflow.State.CANCELED, controller.state());
                assertEquals(serverCompiled ? 0 : 1, bridge.jumpPulses);
                assertTrue(bridge.released);
                poll.invoke(runtime);
                assertEquals(1, deliveries.get());
                for (String lane : List.of("criticalDeliveryNetwork", "projectionDeliveryNetwork"))
                    ((ExecutorService) get(runtime, lane)).submit(() -> {}).get(5, TimeUnit.SECONDS);
                assertTrue(publishedEvents.stream().allMatch(event -> HelixJson.asObject(
                    HelixJson.asObject(event.get("measurements")).get("last_temporal_activation_timing")).isEmpty()),
                    "Rejected delivery must never acquire a completed activation timing");
                assertEquals(1, publishedResults.size());
                java.nio.file.Files.writeString(Path.of("build", serverCompiled ? "native-compiled-terminal.json" : "native-terminal-publication.json"),
                    HelixJson.stringifyIncludingNulls(Map.of("result", publishedResults.get(0), "root", current)), StandardCharsets.UTF_8);
                return;
            }
            assertNotNull(get(state, "queuedWire"), () -> events.toString());
            if (wideRunway) {
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - flightStart.get());
                assertTrue(elapsedMs < 1000, "Fixed 20-tick nominal runway must not be widened after execution");
                assertEquals(0, tick.get(), "Acceptance must use advancing elapsed clock, not scripted ticks");
                // Only the acceptance-budget phase is wall-clock driven here.
                // Committed activation below remains explicitly scripted.
                flightStart.set(0);
            }
            assertNull(get(state, "uncertainPoll"));
            assertEquals(serverCompiled ? 0 : 1, bridge.jumpPulses, "Acceptance must not execute the successor");
            poll.invoke(runtime); assertEquals(1, deliveries.get(), "Queued successor suppresses another delivery");
            tick.set(stopTick); set(runtime, "latestClockSnapshot", simulatedClock(origin, stopTick));
            controller.tick(); assertEquals(serverCompiled ? 0 : 1, bridge.jumpPulses);
            if (serverCompiled) bridge.snapshot = new PlayerActionWorkflow.PlayerSnapshot(true, 0.2, 64, 65.62, 0, 0, 0, 20, true, false, false, null);
            tick.set(committedTick); set(runtime, "latestClockSnapshot", simulatedClock(origin, committedTick));
            controller.tick(); assertEquals(serverCompiled ? 0 : 2, bridge.jumpPulses);
            if (serverCompiled) assertTrue(bridge.movement.forward());
            assertEquals("plan:1", events.get(events.size() - 1).measurements().get("sequence_id"));
            assertNull(get(state, "queuedWire"), "Runtime must advance ownership at activation");
            assertEquals("child", HelixJson.asObject(get(state, "currentWire")).get("action_request_id"));
            for (String lane : List.of("criticalDeliveryNetwork", "projectionDeliveryNetwork"))
                ((ExecutorService) get(runtime, lane)).submit(() -> {}).get(5, TimeUnit.SECONDS);
            if (rejectProjection) {
                // A later workflow event may already have scheduled a retry.
                // Explicitly drain any remaining evidence using the same runtime lane.
                Method flush = PlayerActionRuntime.class.getDeclaredMethod("flushProjectionDeliveryOutbox");
                flush.setAccessible(true);
                assertEquals(true, flush.invoke(runtime));
                assertTrue(projectionRejected.get());
                assertTrue(publishedBatches.size() > 1);
                assertEquals(publishedBatches.get(0), publishedBatches.get(1), "Retry keeps the exact hashed evidence identity");
                publishedBatches.remove(0);
                assertEquals(2, bridge.jumpPulses, "Evidence retry cannot repeat physical effects");
                assertEquals(1, deliveries.get(), "Evidence retry cannot request another action");
            }
            assertTrue(((PlayerActionDeliveryOutbox) get(runtime, "deliveryOutbox")).isEmpty());
            assertFalse(publishedEvents.isEmpty());
            assertEquals(0, ((Number) publishedEvents.get(0).get("sequence")).intValue());
            Map<String, Object> activationMeasurements = HelixJson.asObject(publishedEvents.get(publishedEvents.size() - 1).get("measurements"));
            Map<String, Object> activationTiming = HelixJson.asObject(activationMeasurements.get("last_temporal_activation_timing"));
            assertFalse(activationTiming.isEmpty(), "Checkpoint timing must begin before acceptance");
            assertNotNull(activationTiming.get("checkpoint_to_activation_ms"));
            assertNotNull(activationTiming.get("acceptance_to_activation_ms"));
            Map<String, Object> acceptanceTiming = HelixJson.asObject(activationMeasurements.get("last_temporal_acceptance_timing"));
            long fenceSplitMs = ((Number) acceptanceTiming.get("checkpoint_to_evidence_fence_observed_ms")).longValue() +
                ((Number) acceptanceTiming.get("evidence_fence_observed_to_acceptance_ms")).longValue();
            long acceptanceTotalMs = ((Number) acceptanceTiming.get("checkpoint_to_acceptance_ms")).longValue();
            if (wideRunway) assertTrue(acceptanceTotalMs + 10 < ((Number) fixtureBudget.get("checkpoint_to_stop_window_ms")).longValue(),
                "Checkpoint-through-acceptance plus fixed margin must fit the predeclared window");
            long transportSplitMs = ((Number) acceptanceTiming.get("evidence_fence_observed_to_delivery_poll_ms")).longValue() +
                ((Number) acceptanceTiming.get("delivery_http_roundtrip_ms")).longValue() +
                ((Number) acceptanceTiming.get("delivery_response_to_acceptance_ms")).longValue();
            long afterFenceMs = ((Number) acceptanceTiming.get("evidence_fence_observed_to_acceptance_ms")).longValue();
            assertTrue(transportSplitMs >= afterFenceMs && transportSplitMs <= afterFenceMs + 2,
                "HTTP and callback intervals cover the native remainder without cross-origin subtraction");
            assertTrue(fenceSplitMs >= acceptanceTotalMs && fenceSplitMs <= acceptanceTotalMs + 1,
                "Evidence-fence split must cover acceptance without clock mixing");
            assertEquals("run", activationTiming.get("run_id"));
            assertEquals("root", activationTiming.get("resident_action_request_id"));
            assertEquals("child", activationTiming.get("successor_action_request_id"));
            assertEquals(epoch, activationTiming.get("producer_epoch_ref"));
            assertEquals(acceptanceTiming.get("checkpoint_id"), activationTiming.get("checkpoint_id"));
            long reservationMargin = ((Number) acceptanceTiming.get("stop_client_tick")).longValue() -
                ((Number) acceptanceTiming.get("accepted_client_tick")).longValue();
            assertTrue(reservationMargin > 0, "Reservation margin uses stop, not committed lead ticks");
            if (!wideRunway) assertEquals(1, reservationMargin);
            assertEquals(reservationMargin + 1, ((Number) acceptanceTiming.get("lead_ticks")).longValue());
            long splitMs = ((Number) acceptanceTiming.get("checkpoint_to_acceptance_ms")).longValue() +
                ((Number) activationTiming.get("acceptance_to_activation_ms")).longValue();
            long totalMs = ((Number) activationTiming.get("checkpoint_to_activation_ms")).longValue();
            assertTrue(splitMs >= totalMs && splitMs <= totalMs + 1, "Same-origin split intervals differ only by ceiling rounding");
            int projectedEventCount = 0;
            for (Map<String, Object> batch : publishedBatches) {
                Map<String, Object> hashInput = new LinkedHashMap<>(batch);
                Object hash = hashInput.remove("batch_hash");
                assertEquals(hash, SectionHasher.hashIncludingNulls(hashInput));
                assertEquals(epoch, batch.get("producer_epoch_ref"));
                for (Object member : HelixJson.asList(batch.get("events"))) {
                    projectedEventCount++;
                    Map<String, Object> projection = HelixJson.asObject(member);
                    Map<String, Object> attributes = HelixJson.asObject(projection.get("attributes"));
                    Map<String, Object> event = publishedEvents.stream().filter(item -> item.get("event_id").equals(attributes.get("action_event_ref"))).findFirst().orElseThrow();
                    assertEquals("root", event.get("action_request_id"));
                    assertEquals("workflow", event.get("workflow_id"));
                    assertEquals(event.get("measurements"), attributes.get("workflow_measurements"));
                    assertEquals(false, event.get("assistant_answer"));
                }
            }
            assertEquals(publishedEvents.size(), projectedEventCount);
            if (!rejectProjection) {
                // Generated test artifact for the server's native-wire contract test.
                // No live credentials or account state are included.
                java.nio.file.Files.writeString(Path.of("build", wideRunway ? "native-compiled-wide-publication.json" : serverCompiled ? "native-compiled-publication.json" : "native-temporal-publication.json"),
                    HelixJson.stringifyIncludingNulls(Map.of("batches", publishedBatches, "events", publishedEvents,
                        "root", current, "child", next, "fixture_timing_budget", fixtureBudget)),
                    StandardCharsets.UTF_8);
            }
        } finally {
            if (runtime != null) {
                for (String lane : List.of("network", "criticalDeliveryNetwork", "projectionDeliveryNetwork")) ((ExecutorService) get(runtime, lane)).shutdownNow();
                ((TemporalDeliveryLane) get(runtime, "temporalDeliveryLane")).close();
                ((PlayerActionHttpClient) get(runtime, "http")).close();
            }
            server.stop(0);
        }
    }

    private static void forwardToBroker(com.sun.net.httpserver.HttpExchange exchange, String url, String body)
        throws java.io.IOException {
        try (var client = java.net.http.HttpClient.newHttpClient()) {
            var response = client.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofByteArray());
            exchange.sendResponseHeaders(response.statusCode(), response.body().length);
            exchange.getResponseBody().write(response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new java.io.IOException(error);
        } finally { exchange.close(); }
    }

    @Test void lostHttpThenCanceledClientCallbackRetainsStatusOnlyRecovery() throws Exception {
        exercise(null, true, false);
    }

    @Test void malformedDeliveryRemainsStatusOnlyWithoutCallbackFailure() throws Exception {
        exercise("{\"ok\":true,\"action_request\":false}", false, false);
    }

    @Test void explicitEmptyDeliveryPermitsTheNextPoll() throws Exception {
        exercise("{\"ok\":true,\"action_request\":null}", false, true);
    }

    @Test void emptyDeliveryWithCanceledReturnCallbackRemainsConservativelyUncertain() throws Exception {
        exercise("{\"ok\":true,\"action_request\":null}", true, false);
    }

    private void exercise(String response, boolean cancelReturn, boolean allowRepoll) throws Exception {
        var deliveries = new AtomicInteger();
        var inspections = new AtomicInteger();
        var callbacks = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/requests/temporal-successor", exchange -> {
            exchange.getRequestBody().readAllBytes();
            deliveries.incrementAndGet();
            byte[] bytes = response == null ? new byte[] {'{'} : response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response == null ? 100 : bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/requests/temporal-successor/status", exchange -> {
            exchange.getRequestBody().readAllBytes();
            inspections.incrementAndGet();
            byte[] body = "{\"ok\":true,\"delivery_state\":{\"recorded_status\":\"leased\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        var config = new PlayerActionConfig("http://127.0.0.1:" + server.getAddress().getPort(),
            "helix_env_action_" + "x".repeat(43), "authority:fixture", "installation:fixture",
            "environment:fixture", "room:fixture", "source:fixture", "world:fixture",
            "adapter:fixture", PlayerActionConfig.DOMAIN_ADAPTER, "participant:fixture",
            "subject:fixture", "player:fixture", 1, Instant.now().plusSeconds(120).toString());
        PlayerActionRuntime runtime = null;
        try {
            runtime = new PlayerActionRuntime(config, null, LoggerFactory.getLogger(getClass()), ignored -> {}, task -> {
                // Cancel precisely the post-failure callback before it starts.
                // Same cancellation branch as timeout, without a 12-second wait.
                if (callbacks.incrementAndGet() == 3 && cancelReturn) Thread.currentThread().interrupt();
                else task.run();
            }, directory.resolve("unused-status.json"));
            Map<String, Object> wire = Map.of("action_request_id", "root", "temporal_plan",
                Map.of("plan_id", "plan", "plan_hash", "hash", "nodes",
                    List.of(Map.of("kind", "checkpoint", "checkpoint_id", "cp"))));
            // Synthetic already-running resident: admission and game execution
            // are deliberately outside this runtime HTTP/callback fixture.
            Object envelope = construct("ActiveEnvelope", wire, "execution", "fixture", Map.of(),
                "native", new ArrayList<String>(), new EnvironmentCapacityTelemetry(0, 0, 0, 0, 20));
            Object state = construct("TemporalDeliveryState", wire);
            set(state, "checkpointDeliveryWatermark", 1L);
            set(state, "measurements", Map.of("checkpoint_settlements", List.of(Map.of("checkpoint_id", "cp"))));
            set(runtime, "activeEnvelope", envelope);
            set(runtime, "temporalDeliveryState", state);
            set(get(runtime, "controller"), "state", PlayerActionWorkflow.State.RUNNING);
            Method poll = PlayerActionRuntime.class.getDeclaredMethod("pollTemporalSuccessor");
            poll.setAccessible(true);
            PlayerActionRuntime target = runtime;
            if (cancelReturn) {
                InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> poll.invoke(target));
                assertInstanceOf(InterruptedException.class, failure.getCause());
            } else poll.invoke(runtime);
            Thread.interrupted();
            assertEquals(!allowRepoll, get(state, "uncertainPoll") != null);
            poll.invoke(runtime);
            assertEquals(allowRepoll ? 2 : 1, deliveries.get(), "Only confirmed empty delivery permits another delivery POST");
            assertEquals(allowRepoll ? 0 : 1, inspections.get());
            if (!allowRepoll)
                assertEquals("temporal_successor_delivery_recorded_leased", get(runtime, "lastTransportError"));
        } finally {
            Thread.interrupted();
            if (runtime != null) {
                for (String lane : List.of("network", "criticalDeliveryNetwork", "projectionDeliveryNetwork"))
                    ((ExecutorService) get(runtime, lane)).shutdownNow();
                ((TemporalDeliveryLane) get(runtime, "temporalDeliveryLane")).close();
                ((PlayerActionHttpClient) get(runtime, "http")).close();
            }
            server.stop(0);
        }
    }

    private static Map<String, Object> simulatedClock(String origin, long tick) {
        Map<String, Object> clock = new LinkedHashMap<>();
        clock.put("schema", "helix.environment_clock_snapshot.v1");
        clock.put("clock_id", "fixture:client-clock");
        clock.put("clock_kind", "minecraft_game_tick");
        clock.put("tick_rate_hz", 20);
        clock.put("tick_index", tick);
        // Explicit simulated world/client mapping for checkpoint admission.
        clock.put("world_tick_index", tick);
        clock.put("monotonic", Map.of("origin_id", origin, "elapsed_ms", tick * 50));
        clock.put("synchronization", "client_local");
        clock.put("observed_at", Instant.now().toString());
        return clock;
    }

    private static Object construct(String name, Object... arguments) throws Exception {
        Class<?> type = Class.forName(PlayerActionRuntime.class.getName() + "$" + name);
        Constructor<?> constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(arguments);
    }
    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
