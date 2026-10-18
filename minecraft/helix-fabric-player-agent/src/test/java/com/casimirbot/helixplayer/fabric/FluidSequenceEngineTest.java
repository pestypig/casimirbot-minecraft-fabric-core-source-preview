package com.casimirbot.helixplayer.fabric;

import static com.casimirbot.helixplayer.fabric.PlayerActionWorkflow.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class FluidSequenceEngineTest {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "HELIX_NATIVE_COMPILED_HANDOFF", matches = "1")
    void continuousServerCompiledChainHasNoReleasedTickAcrossThreeHandoffs() throws Exception {
        Object raw = com.casimirbot.helixsensor.HelixJson.parse(java.nio.file.Files.readString(
            java.nio.file.Path.of("build", "server-compiled-continuous-chain.json")));
        List<Map<String, Object>> plans = ((List<?>) raw).stream()
            .map(com.casimirbot.helixsensor.HelixJson::asObject).toList();
        assertEquals(4, plans.size());
        List<Map<String, Object>> arguments = plans.stream().map(plan ->
            com.casimirbot.helixsensor.HelixJson.asObject(
                com.casimirbot.helixsensor.HelixJson.asObject(plan.get("artifact")).get("arguments"))).toList();
        FakeBridge bridge = new FakeBridge();
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        engine.begin(arguments.get(0));
        WorkflowStep result = null;
        int movingTicks = 0;
        for (int tick = 0; tick <= 84; tick++) {
            bridge.snapshot = new PlayerSnapshot(true, tick * 0.1, 64, 65.62, 0, 0, 0, 20, true, false, false, null);
            result = engine.step(tick + 1);
            if (tick < 84) {
                assertEquals(WorkflowStepStatus.RUNNING, result.status());
                assertTrue(bridge.movement.forward(), "Released movement at tick " + tick);
                movingTicks++;
            }
            if (tick == 0 || tick == 21 || tick == 42) {
                int predecessor = tick / 21;
                assertTrue(engine.queueAdmittedSuccessor(
                    String.valueOf(arguments.get(predecessor).get("sequence_id")),
                    "checkpoint:compiled-walk", arguments.get(predecessor + 1), tick + 21, tick, tick + 20, 0));
            }
        }
        assertEquals(84, movingTicks);
        assertEquals(WorkflowStepStatus.SUCCEEDED, result.status());
        assertEquals(3, result.measurements().get("resident_handoff_count"));
        assertTrue(bridge.released);
        assertFalse(bridge.movement.forward());
    }

    @Test
    void scheduledCompilerWalksSeparateLaunchWaitFromThreeMovingHandoffs() throws Exception {
        Object raw;
        try (var stream = getClass().getResourceAsStream("/compiled-scheduled-handoff.json")) {
            assertNotNull(stream);
            raw = com.casimirbot.helixsensor.HelixJson.parse(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        List<Map<String, Object>> plans = ((List<?>) raw).stream()
            .map(com.casimirbot.helixsensor.HelixJson::asObject).toList();
        List<Map<String, Object>> arguments = plans.stream().map(plan ->
            com.casimirbot.helixsensor.HelixJson.asObject(
                com.casimirbot.helixsensor.HelixJson.asObject(plan.get("artifact")).get("arguments"))).toList();
        for (int firstTick : List.of(0, 3, 8, 9)) {
            FakeBridge bridge = new FakeBridge();
            FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
            engine.begin(arguments.get(0));
            int movingTicks = 0;
            WorkflowStep result = null;
            for (int tick = firstTick; tick <= 49; tick++) {
                bridge.snapshot = new PlayerSnapshot(true, Math.max(0, tick - 8) * 0.1,
                    64, 65.62, 0, 0, 0, 20, true, false, false, null);
                result = engine.step(tick + 1);
                if (firstTick > 8) {
                    assertFalse(bridge.movement.forward());
                    if (result.status() != WorkflowStepStatus.RUNNING) break;
                    continue;
                }
                if (tick < 8) {
                    assertFalse(bridge.movement.forward(), "Launch waiting is not motion");
                } else if (tick < 48) {
                    assertEquals(WorkflowStepStatus.RUNNING, result.status());
                    assertTrue(bridge.movement.forward(), "No released tick within admitted moving runway");
                    movingTicks++;
                }
                if (tick == 8 || tick == 18 || tick == 28) {
                    int predecessor = (tick - 8) / 10;
                    assertTrue(engine.queueAdmittedSuccessor(
                        String.valueOf(arguments.get(predecessor).get("sequence_id")),
                        "checkpoint:compiled-walk", arguments.get(predecessor + 1),
                        tick + 10, tick, tick + 9, 0));
                }
                if (result.status() != WorkflowStepStatus.RUNNING) break;
            }
            assertNotNull(result);
            assertEquals(firstTick > 8 ? WorkflowStepStatus.FAILED : WorkflowStepStatus.SUCCEEDED, result.status());
            assertEquals(firstTick > 8 ? 0 : 40, movingTicks);
            if (firstTick <= 8) {
                assertEquals(3, result.measurements().get("resident_handoff_count"));
                assertTrue(bridge.released);
            }
            assertFalse(bridge.movement.forward());
        }
    }

    @Test
    void compilerDerivedWalkRejectsHandoffWhenDeliveryConsumesOneSourceTick() throws Exception {
        Map<String, Object> fixture;
        try (var stream = getClass().getResourceAsStream("/compiled-rolling-walk.json")) {
            assertNotNull(stream);
            fixture = com.casimirbot.helixsensor.HelixJson.asObject(
                com.casimirbot.helixsensor.HelixJson.parse(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
        }
        Map<String, Object> base = com.casimirbot.helixsensor.HelixJson.asObject(
            com.casimirbot.helixsensor.HelixJson.asObject(fixture.get("artifact")).get("arguments"));
        FakeBridge bridge = new FakeBridge();
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        engine.begin(base);
        // First execution is one tick later than the fixture's source clock.
        assertEquals(WorkflowStepStatus.RUNNING, engine.step(2).status());
        Map<String, Object> next = new LinkedHashMap<>(base);
        next.put("sequence_id", "must-not-start-after-delayed-root");
        assertTrue(engine.queueAdmittedSuccessor(String.valueOf(base.get("sequence_id")),
            "checkpoint:compiled-walk", next, 2, 1, 2, 2));
        WorkflowStep result = engine.step(3);
        assertEquals(WorkflowStepStatus.FAILED, result.status());
        assertEquals(base.get("sequence_id"), result.measurements().get("sequence_id"));
        assertFalse(bridge.movement.forward());
        assertTrue(bridge.released);
    }

    @Test
    void walkBoundaryRejectsUnfinishedMotionAndManualTakeover() {
        for (boolean manual : List.of(false, true)) {
            FakeBridge bridge = new FakeBridge();
            FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
            Map<String, Object> graph = new LinkedHashMap<>(sequence("cp", List.of("cp"), List.of(
                Map.of("node_id", "cp", "node_kind", "checkpoint", "earliest_tick", 0, "checkpoint_id", "cp",
                    "condition", Map.of("condition_kind", "player_grounded", "expected", true),
                    "wait_up_to_ticks", 1, "on_satisfied", "walk", "on_timeout", "failed"),
                Map.of("node_id", "walk", "node_kind", "workflow_action", "earliest_tick", 0,
                    "timeout_ticks", 20, "action", Map.of("action_kind", "walk", "direction", "forward", "duration_ms", 100, "sprint", false),
                    "on_success", "done", "on_failure", "failed"),
                terminal("done", "succeeded"), terminal("failed", "failed"))));
            graph.put("sequence_id", "predecessor");
            engine.begin(graph);
            engine.step(1);
            long boundary = manual ? 2 : 1;
            Map<String, Object> next = new LinkedHashMap<>(graph);
            next.put("sequence_id", "must-not-start");
            assertTrue(engine.queueAdmittedSuccessor("predecessor", "cp", next, boundary, 0, 1, boundary));
            if (manual) {
                bridge.snapshot = new PlayerSnapshot(true, 0.2, 64, 65.62, 0, 0, 0, 20, true, false, false, null);
                engine.step(2);
                bridge.snapshot = snapshot(20, true, true, "keyboard");
            }
            WorkflowStep result = engine.step(boundary + 1);
            if (result.status() == WorkflowStepStatus.RUNNING) result = engine.step(boundary + 2);
            assertEquals(WorkflowStepStatus.FAILED, result.status());
            assertEquals("predecessor", result.measurements().get("sequence_id"));
            assertFalse(bridge.movement.forward());
            assertTrue(bridge.released);
        }
    }

    @Test
    void compilerDerivedWalksCrossThreeResidentBoundariesWithoutAReleasedTick() throws Exception {
        Map<String, Object> fixture;
        try (var stream = getClass().getResourceAsStream("/compiled-rolling-walk.json")) {
            assertNotNull(stream);
            fixture = com.casimirbot.helixsensor.HelixJson.asObject(
                com.casimirbot.helixsensor.HelixJson.parse(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
        }
        Map<String, Object> base = com.casimirbot.helixsensor.HelixJson.asObject(
            com.casimirbot.helixsensor.HelixJson.asObject(fixture.get("artifact")).get("arguments"));
        Map<String, Object> source = com.casimirbot.helixsensor.HelixJson.asObject(fixture.get("source"));
        Map<String, Object> watermarks = com.casimirbot.helixsensor.HelixJson.asObject(source.get("watermarks"));
        long committed = ((Number) watermarks.get("committed_through_unit")).longValue();
        long decision = ((Number) watermarks.get("decision_unit")).longValue();
        long stop = ((Number) watermarks.get("stop_unit")).longValue();
        FakeBridge bridge = new FakeBridge();
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        engine.begin(base);
        engine.step(1);
        String previous = String.valueOf(base.get("sequence_id"));
        for (int cycle = 0; cycle < 3; cycle++) {
            long start = cycle * committed;
            Map<String, Object> next = new LinkedHashMap<>(base);
            String nextId = "compiled-successor:" + cycle;
            next.put("sequence_id", nextId);
            assertTrue(engine.queueAdmittedSuccessor(previous, "checkpoint:compiled-walk", next,
                start + committed, start + decision, start + stop, start + committed));
            for (long tick = start + 2; tick <= start + committed + 1; tick++) {
                bridge.snapshot = new PlayerSnapshot(true, tick * 0.1, 64, 65.62, 0, 0, 0, 20, true, false, false, null);
                assertEquals(WorkflowStepStatus.RUNNING, engine.step(tick).status());
                assertTrue(bridge.movement.forward());
                assertFalse(bridge.released, "Successor must apply checked motion within the boundary tick");
            }
            previous = nextId;
        }
        bridge.snapshot = new PlayerSnapshot(true, 0.8, 64, 65.62, 0, 0, 0, 20, true, false, false, null);
        engine.step(4 * committed);
        assertEquals(WorkflowStepStatus.SUCCEEDED, engine.step(4 * committed + 1).status());
        assertTrue(bridge.released);
    }

    @Test
    void unsuccessfulWalkNeverTakesTheSameTickSuccessEdge() {
        for (boolean manual : List.of(false, true)) {
            FakeBridge bridge = new FakeBridge();
            FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
            engine.begin(sequence("first", List.of(), List.of(
                Map.of("node_id", "first", "node_kind", "workflow_action", "earliest_tick", 0,
                    "timeout_ticks", 20, "action", Map.of("action_kind", "walk", "direction", "forward", "duration_ms", 50, "sprint", false),
                    "on_success", "second", "on_failure", "failed"),
                inputNode("second", "done", "failed", 2),
                terminal("done", "succeeded"), terminal("failed", "failed"))));
            engine.step(1);
            // With no measured displacement the action fails; manual input
            // independently cancels it. Neither may enter the success edge.
            bridge.snapshot = snapshot(20, true, manual, manual ? "keyboard" : null);
            engine.step(2);
            WorkflowStep result = engine.step(3);
            assertEquals(WorkflowStepStatus.FAILED, result.status());
            assertFalse(((List<?>) result.measurements().get("executed_node_ids")).contains("second"));
            assertFalse(bridge.movement.forward());
            assertTrue(bridge.released);
        }
    }

    @Test
    void successfulWalkAdvancesToNextAdmittedWalkWithinTheSameTick() {
        FakeBridge bridge = new FakeBridge();
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        engine.begin(sequence("first", List.of(), List.of(
            Map.of("node_id", "first", "node_kind", "workflow_action", "earliest_tick", 0,
                "timeout_ticks", 20, "action", Map.of("action_kind", "walk", "direction", "forward", "duration_ms", 50, "sprint", false),
                "on_success", "second", "on_failure", "failed"),
            Map.of("node_id", "second", "node_kind", "workflow_action", "earliest_tick", 0,
                "timeout_ticks", 20, "action", Map.of("action_kind", "walk", "direction", "forward", "duration_ms", 50, "sprint", false),
                "on_success", "done", "on_failure", "failed"),
            terminal("done", "succeeded"), terminal("failed", "failed"))));
        assertEquals(WorkflowStepStatus.RUNNING, engine.step(1).status());
        bridge.snapshot = new PlayerSnapshot(true, 0.2, 64, 65.62, 0, 0, 0, 20, true, false, false, null);
        assertEquals(WorkflowStepStatus.RUNNING, engine.step(2).status());
        assertTrue(bridge.movement.forward(), "Next admitted action reapplies checked movement before the tick ends");
        assertFalse(bridge.released);
        bridge.snapshot = new PlayerSnapshot(true, 0.4, 64, 65.62, 0, 0, 0, 20, true, false, false, null);
        assertEquals(WorkflowStepStatus.SUCCEEDED, engine.step(3).status());
        assertTrue(bridge.released);
    }

    @Test
    void productionCompiledWalkSettlesGraphWithoutAnExtraReleasedTick() throws Exception {
        Map<String, Object> fixture;
        try (var stream = getClass().getResourceAsStream("/compiled-walk-continuity.json")) {
            assertNotNull(stream);
            fixture = com.casimirbot.helixsensor.HelixJson.asObject(
                com.casimirbot.helixsensor.HelixJson.parse(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
        }
        Map<String, Object> artifact = com.casimirbot.helixsensor.HelixJson.asObject(fixture.get("artifact"));
        Map<String, Object> arguments = com.casimirbot.helixsensor.HelixJson.asObject(artifact.get("arguments"));
        FakeBridge bridge = new FakeBridge();
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        engine.begin(arguments);
        WorkflowStep result = null;
        for (int tick = 1; tick <= 11; tick++) {
            bridge.snapshot = new PlayerSnapshot(true, tick * 0.1, 64, 65.62, 0, 0, 0, 20, true, false, false, null);
            result = engine.step(tick);
            assertEquals(WorkflowStepStatus.RUNNING, result.status());
            assertTrue(bridge.movement.forward());
        }
        result = engine.step(12);
        assertEquals(WorkflowStepStatus.SUCCEEDED, result.status());
        assertTrue(bridge.released);
        assertFalse(bridge.movement.forward());
    }

    @Test
    void uncommandedInventoryGainStopsBeforeMovementWithZeroInventoryBudget() {
        FakeBridge bridge = new FakeBridge() {
            int samples;
            @Override public InventoryCountObservation inventoryCountObservation() {
                return new InventoryCountObservation(true, samples++ == 0 ? Map.of() : Map.of("minecraft:stone", 1));
            }
        };
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        engine.begin(sequence("move", List.of(), List.of(inputNode("move", "done", "failed", 3),
            terminal("done", "succeeded"), terminal("failed", "failed"))));
        WorkflowStep result = engine.step(1);
        assertEquals(WorkflowStepStatus.FAILED, result.status());
        assertEquals("sequence_observed_inventory_change_budget_exceeded", result.measurements().get("terminal_reason_code"));
        assertEquals(List.of(), result.measurements().get("executed_node_ids"));
        assertTrue(bridge.released);
        assertFalse(bridge.movement.forward());
        assertEquals("unestablished", ((Map<?, ?>) result.measurements().get("resident_inventory_observation")).get("action_attribution"));
    }

    @Test
    void measuredOverrunStopsBeforeTheNextNodeAndPreservesTheObservedCount() {
      for (boolean childSettled : List.of(false, true)) {
        FakeBridge bridge = new FakeBridge() {
            @Override public WorkflowStep runWorkflowStep(String kind, Map<String, Object> args, String engine, long ticks) {
                return childSettled ? WorkflowStep.succeeded("Observed pickup", Map.of("collected_count", 2)) :
                    WorkflowStep.running(0.2, "Observed pickup while running", Map.of("collected_count", 2));
            }
        };
        var graph = new LinkedHashMap<>(sequence("collect", List.of(), List.of(
            Map.of("node_id", "collect", "node_kind", "workflow_action", "earliest_tick", 0, "timeout_ticks", 20,
                "action", Map.of("action_kind", "collect", "item_id", "minecraft:stone", "count", 1, "search_radius", 3),
                "on_success", "next", "on_failure", "failed"),
            Map.of("node_id", "next", "node_kind", "workflow_action", "earliest_tick", 0, "timeout_ticks", 20,
                "action", Map.of("action_kind", "hotbar_select", "slot", 4), "on_success", "done", "on_failure", "failed"),
            terminal("done", "succeeded"), terminal("failed", "failed"))));
        graph.put("mutation_scope", Map.of("world_mutation_allowed", false, "max_block_mutations", 0,
            "max_inventory_transfers", 1, "allowed_block_ids", List.of(), "allowed_regions", List.of(), "combat_allowed", false));
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        engine.begin(graph);
        WorkflowStep result = engine.step(1);
        assertEquals(WorkflowStepStatus.FAILED, result.status());
        assertEquals("sequence_observed_mutation_budget_exceeded", result.measurements().get("terminal_reason_code"));
        assertEquals(2, result.measurements().get("inventory_mutations_performed"));
        assertEquals(List.of("collect"), result.measurements().get("executed_node_ids"));
        assertTrue(bridge.released);
        assertEquals(WorkflowStepStatus.FAILED, engine.step(2).status());
        assertNotEquals(4, bridge.selectedSlot);
      }
    }

    @Test
    void repeatedCumulativeChildMeasurementsAreCountedOnceThroughSettlement() {
        FakeBridge bridge = new FakeBridge() {
            @Override public WorkflowStep runWorkflowStep(String kind, Map<String, Object> args, String engine, long ticks) {
                return ticks < 3 ? WorkflowStep.running(0.5, "Still collecting", Map.of("collected_count", 1)) :
                    WorkflowStep.succeeded("Collected", Map.of("collected_count", 1));
            }
        };
        var graph = new LinkedHashMap<>(sequence("collect", List.of(), List.of(
            Map.of("node_id", "collect", "node_kind", "workflow_action", "earliest_tick", 0, "timeout_ticks", 20,
                "action", Map.of("action_kind", "collect", "item_id", "minecraft:stone", "count", 1, "search_radius", 3),
                "on_success", "done", "on_failure", "failed"), terminal("done", "succeeded"), terminal("failed", "failed"))));
        graph.put("mutation_scope", Map.of("world_mutation_allowed", false, "max_block_mutations", 0,
            "max_inventory_transfers", 1, "allowed_block_ids", List.of(), "allowed_regions", List.of(), "combat_allowed", false));
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        engine.begin(graph);
        for (int tick = 1; tick <= 3; tick++) {
            WorkflowStep progress = engine.step(tick);
            assertEquals(WorkflowStepStatus.RUNNING, progress.status());
            assertEquals(1, progress.measurements().get("inventory_mutations_performed"));
        }
        WorkflowStep result = engine.step(4);
        assertEquals(WorkflowStepStatus.SUCCEEDED, result.status());
        assertEquals(1, result.measurements().get("inventory_mutations_performed"));
    }

    @Test
    void successorAcceptanceIsNotRepeatedByImmediateOrReentrantCancellation() {
        for (boolean reentrant : List.of(false, true)) {
            SequenceBridge bridge = new SequenceBridge();
            List<WorkflowEvent> events = new java.util.ArrayList<>();
            PlayerActionController[] holder = new PlayerActionController[1];
            holder[0] = new PlayerActionController(bridge, event -> {
                events.add(event);
                if (reentrant && event.measurements().containsKey("temporal_successor_acceptance")) {
                    holder[0].cancel("workflow:cancel", "evidence backpressure");
                }
            });
            PlayerActionController controller = holder[0];
            controller.start(new ActionRequest("action:cancel", "workflow:cancel", "execute_sequence",
                residentSequence("first"), 100, ManualOverridePolicy.CANCEL, "native_fabric"));
            assertTrue(controller.attachTemporalWindow("action:cancel", new TemporalPlanWindow("origin", 100, 0, 1, 2, 20, 1000),
                () -> new PlayerActionController.TemporalClockSample("origin", 100, 0), null));
            controller.tick();
            assertTrue(controller.queueTemporalSuccessor("action:cancel", "first", "cp", residentSequence("second"),
                new TemporalPlanWindow("origin", 100, 0, 3, 4, 20, 1000), null));
            if (!reentrant) assertTrue(controller.cancel("workflow:cancel", "operator cancellation"));
            assertEquals(State.CANCELED, controller.state());
            WorkflowEvent terminalEvent = events.get(events.size() - 1);
            assertTrue(terminalEvent.controlsReleased());
            assertFalse(terminalEvent.measurements().containsKey("temporal_successor_acceptance"));
            assertEquals(1, events.stream().filter(event -> event.measurements().containsKey("temporal_successor_acceptance")).count());
            controller.tick();
            assertEquals(1, bridge.jumpPulses);
        }
    }

    @Test
    void temporalControllerCarriesThreeAcceptedWindowsAcrossTheNativeBoundary() {
        SequenceBridge bridge = new SequenceBridge();
        var telemetry = new EnvironmentCapacityTelemetry(1, 2, 0, 0, 20);
        List<WorkflowEvent> events = new java.util.ArrayList<>();
        PlayerActionController controller = new PlayerActionController(bridge, events::add);
        controller.start(new ActionRequest("action:rolling", "workflow:rolling", "execute_sequence",
            residentSequence("plan:0"), 100, ManualOverridePolicy.CANCEL, "native_fabric"));
        long[] tick = {100};
        assertTrue(controller.attachTemporalWindow("action:rolling", new TemporalPlanWindow("origin", 100, 0, 1, 2, 20, 1000),
            () -> new PlayerActionController.TemporalClockSample("origin", tick[0], (tick[0] - 100) * 50.0), null));
        controller.tick();
        for (int index = 0; index < 3; index++) {
            WorkflowEvent checkpointEvent = events.get(events.size() - 1);
            assertEquals("plan:" + index, checkpointEvent.measurements().get("sequence_id"));
            assertFalse(((List<?>) checkpointEvent.measurements().get("checkpoint_settlements")).isEmpty());
            String timingKey = "plan:" + index + ":cp";
            // Explicit simulated clock, not a measured network/provider delay.
            telemetry.recordCheckpoint(timingKey, tick[0] * 50_000_000L);
            TemporalPlanWindow next = new TemporalPlanWindow("origin", 100, 0, index == 2 ? 9 : 3 + index * 2L,
                index == 2 ? 10 : 4 + index * 2L, 20, 1000);
            assertFalse(controller.queueTemporalSuccessor("wrong-action", "plan:" + index, "cp", residentSequence("plan:" + (index + 1)), next, null));
            assertTrue(controller.queueTemporalSuccessor("action:rolling", "plan:" + index, "cp", residentSequence("plan:" + (index + 1)), next, null));
            WorkflowEvent acceptance = events.get(events.size() - 1);
            Map<?, ?> accepted = (Map<?, ?>) acceptance.measurements().get("temporal_successor_acceptance");
            assertEquals(State.RUNNING, acceptance.state());
            assertFalse(acceptance.controlsReleased());
            assertEquals("action:rolling", acceptance.actionRequestId());
            assertEquals("plan:" + (index + 1), accepted.get("sequence_id"));
            assertEquals("plan:" + index, accepted.get("predecessor_sequence_id"));
            assertEquals(tick[0], accepted.get("accepted_client_tick"));
            assertEquals(2L, accepted.get("lead_ticks"));
            assertEquals(1, accepted.get("queue_depth"));
            assertEquals(false, accepted.get("execution_started"));
            assertEquals(false, accepted.get("terminal_eligible"));
            assertEquals(1L, ((Number) accepted.get("stop_client_tick")).longValue() - tick[0]);
            telemetry.recordTemporalAcceptance(timingKey, tick[0] * 50_000_000L,
                Map.of("successor_plan_id", accepted.get("sequence_id")));
            assertEquals(0L, ((Map<?, ?>) telemetry.snapshot().get("last_temporal_acceptance_timing"))
                .get("checkpoint_to_acceptance_ms"));
            int acceptedEventCount = events.size();
            assertFalse(controller.queueTemporalSuccessor("action:rolling", "plan:" + index, "cp", residentSequence("plan:" + (index + 1)), next, null));
            assertEquals(acceptedEventCount, events.size());
            tick[0]++;
            controller.tick();
            assertEquals(State.RUNNING, controller.state());
            tick[0]++;
            controller.tick();
            assertEquals(State.RUNNING, controller.state());
            assertEquals(index + 2, bridge.jumpPulses);
            assertEquals(0, bridge.globalReleaseCount);
            WorkflowEvent handoff = events.get(events.size() - 1);
            assertEquals("workflow.progress", handoff.eventType());
            assertEquals(State.RUNNING, handoff.state());
            assertFalse(handoff.controlsReleased());
            assertEquals("action:rolling", handoff.actionRequestId());
            assertEquals(index + 1, handoff.measurements().get("resident_handoff_count"));
            assertEquals("plan:" + (index + 1), handoff.measurements().get("sequence_id"));
            telemetry.recordTemporalActivation(timingKey, tick[0] * 50_000_000L,
                Map.of("successor_plan_id", handoff.measurements().get("sequence_id")));
            var timing = (Map<?, ?>) telemetry.snapshot().get("last_temporal_activation_timing");
            assertEquals(100L, timing.get("acceptance_to_activation_ms"));
            assertEquals(100L, timing.get("checkpoint_to_activation_ms"));
            assertEquals("plan:" + (index + 1), timing.get("successor_plan_id"));
            int beforeRepeatedTick = events.size();
            controller.tick();
            assertEquals(beforeRepeatedTick, events.size());
        }
        tick[0]++;
        controller.tick();
        tick[0]++;
        controller.tick();
        assertEquals(State.SUCCEEDED, controller.state());
        assertTrue(bridge.released);
        assertEquals(3, events.stream().filter(event -> event.summary().equals("The resident executor began the admitted successor sequence.")).count());
        assertEquals(1, events.stream().filter(event -> event.eventType().equals("workflow.succeeded")).count());
    }

    @Test
    void reservedWindowCannotOutrunDeadlineLateTickOrManualTakeover() {
        for (String mode : List.of("deadline", "late", "manual")) {
            SequenceBridge bridge = new SequenceBridge();
            PlayerActionController controller = new PlayerActionController(bridge, event -> {});
            controller.start(new ActionRequest("action:bounded", "workflow:bounded", "execute_sequence",
                residentSequence("first"), 100, ManualOverridePolicy.CANCEL, "native_fabric"));
            long[] tick = {100};
            assertTrue(controller.attachTemporalWindow("action:bounded", new TemporalPlanWindow("origin", 100, 0, 1, 2, 20,
                "deadline".equals(mode) ? 100 : 1000),
                () -> new PlayerActionController.TemporalClockSample("origin", tick[0], (tick[0] - 100) * 50.0), null));
            controller.tick();
            assertTrue(controller.queueTemporalSuccessor("action:bounded", "first", "cp", residentSequence("second"),
                new TemporalPlanWindow("origin", 100, 0, 3, 4, 20, 1000), null));
            tick[0] = "late".equals(mode) ? 103 : 102;
            if ("manual".equals(mode)) bridge.snapshot = snapshot(20, true, true, "keyboard");
            controller.tick();
            assertEquals(State.CANCELED, controller.state());
            assertTrue(bridge.released);
            controller.tick();
            assertEquals(1, bridge.jumpPulses);
        }
    }

    static Map<String, Object> residentSequence(String id) {
        Map<String, Object> result = new LinkedHashMap<>(sequence("checkpoint", List.of("cp"), List.of(
            Map.of("node_id", "checkpoint", "node_kind", "checkpoint", "earliest_tick", 0,
                "checkpoint_id", "cp", "condition", Map.of("condition_kind", "player_grounded", "expected", true),
                "wait_up_to_ticks", 1, "on_satisfied", "input", "on_timeout", "failed"),
            inputNode("input", "done", "failed", 2), terminal("done", "succeeded"), terminal("failed", "failed"))));
        result.put("sequence_id", id);
        return result;
    }

    @Test
    void threeResidentHandoffsPreserveMotionAndPlanQualifiedEvidence() {
        SequenceBridge bridge = new SequenceBridge();
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        engine.begin(residentSequence("plan:0"));
        engine.step(1);
        for (int index = 0; index < 3; index++) {
            Map<String, Object> next = residentSequence("plan:" + (index + 1));
            long currentTick = index * 2L;
            assertTrue(engine.queueAdmittedSuccessor("plan:" + index, "cp", next, currentTick + 2, currentTick, currentTick + 1));
            assertTrue(engine.queueAdmittedSuccessor("plan:" + index, "cp", next, currentTick + 2, currentTick, currentTick + 1));
            assertFalse(engine.queueAdmittedSuccessor("plan:" + index, "cp", residentSequence("fork"), currentTick + 2, currentTick, currentTick + 1));
            next.put("sequence_id", "changed-after-delivery");
            engine.step(currentTick + 2);
            WorkflowStep step = engine.step(currentTick + 3);
            assertEquals(WorkflowStepStatus.RUNNING, step.status());
            assertEquals("plan:" + (index + 1), step.measurements().get("sequence_id"));
            assertEquals(index + 1, step.measurements().get("resident_handoff_count"));
            assertEquals(index + 2, bridge.jumpPulses);
            assertEquals(0, bridge.globalReleaseCount);
            assertTrue(bridge.movement.forward());
            List<?> history = (List<?>) step.measurements().get("completed_sequence_measurements");
            assertEquals(index + 1, history.size());
            assertEquals("plan:0", ((Map<?, ?>) history.get(0)).get("sequence_id"));
            assertEquals(true, ((Map<?, ?>) history.get(0)).get("player_motion_performed"));
        }
        engine.step(8);
        assertEquals(WorkflowStepStatus.SUCCEEDED, engine.step(9).status());
        assertTrue(bridge.released);
        engine.step(10);
        assertEquals(4, bridge.jumpPulses);
    }

    @Test
    void successorUsesItsSourceClockRatherThanResettingAtHandoff() {
        SequenceBridge bridge = new SequenceBridge();
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        engine.begin(residentSequence("first"));
        engine.step(1);
        Map<String, Object> next = residentSequence("second");
        List<Map<String, Object>> addressed = new java.util.ArrayList<>();
        for (Object raw : (List<?>) next.get("nodes")) {
            Map<String, Object> node = new LinkedHashMap<>((Map<String, Object>) raw);
            if ("input".equals(node.get("node_id"))) node.put("earliest_tick", 2);
            addressed.add(node);
        }
        next.put("nodes", addressed);
        assertFalse(engine.queueAdmittedSuccessor("first", "cp", next, 2, 0, 1, -1));
        assertFalse(engine.queueAdmittedSuccessor("first", "cp", next, 2, 0, 1, 3));
        assertTrue(engine.queueAdmittedSuccessor("first", "cp", next, 2, 0, 1, 0));
        assertFalse(engine.queueAdmittedSuccessor("first", "cp", next, 2, 0, 1, 1));
        engine.step(2);
        WorkflowStep handoff = engine.step(3);
        assertEquals(WorkflowStepStatus.RUNNING, handoff.status());
        assertEquals(2, bridge.jumpPulses);
        assertTrue(bridge.movement.forward());
        List<?> checkpoints = (List<?>) handoff.measurements().get("checkpoint_settlements");
        assertEquals(2L, ((Map<?, ?>) checkpoints.get(0)).get("tick_index"));
        engine.step(4);
        assertEquals(WorkflowStepStatus.SUCCEEDED, engine.step(5).status());
    }

    @Test
    void successorCannotStartAtAnEarlyOrLateBoundaryOrAfterSettlement() {
        for (long boundary : List.of(1L, 3L)) {
            SequenceBridge bridge = new SequenceBridge();
            FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
            engine.begin(residentSequence("first"));
            engine.step(1);
            assertFalse(engine.queueAdmittedSuccessor("wrong", "cp", residentSequence("next"), boundary, 0, 1));
            assertFalse(engine.queueAdmittedSuccessor("first", "wrong", residentSequence("next"), boundary, 0, 1));
            assertFalse(engine.queueAdmittedSuccessor("first", "cp", residentSequence("next"), boundary, 1, 1));
            assertTrue(engine.queueAdmittedSuccessor("first", "cp", residentSequence("next"), boundary, 0, 1));
            WorkflowStep beforeEnd = engine.step(2);
            if (boundary == 1) {
                assertEquals(WorkflowStepStatus.FAILED, beforeEnd.status());
                assertEquals(MovementInput.released(), bridge.movement);
            }
            assertEquals(WorkflowStepStatus.FAILED, engine.step(3).status());
            assertTrue(bridge.released);
            assertFalse(engine.queueAdmittedSuccessor("first", "cp", residentSequence("next"), 4, 2, 3));
            engine.step(4);
            assertEquals(1, bridge.jumpPulses);
        }
    }

    @Test
    void handoffToTypedActionReleasesInheritedControlsWithoutGlobalDisarm() {
        SequenceBridge bridge = new SequenceBridge();
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        engine.begin(residentSequence("first"));
        engine.step(1);
        Map<String, Object> next = new LinkedHashMap<>(sequence("hotbar", List.of(), List.of(
            Map.of("node_id", "hotbar", "node_kind", "workflow_action", "earliest_tick", 0,
                "timeout_ticks", 20, "action", Map.of("action_kind", "hotbar_select", "slot", 4),
                "on_success", "done", "on_failure", "failed"),
            terminal("done", "succeeded"), terminal("failed", "failed"))));
        next.put("sequence_id", "second");
        assertTrue(engine.queueAdmittedSuccessor("first", "cp", next, 2, 0, 1));
        engine.step(2);
        assertEquals(WorkflowStepStatus.RUNNING, engine.step(3).status());
        assertEquals(4, bridge.selectedSlot);
        assertEquals(MovementInput.released(), bridge.movement);
        assertEquals(2, bridge.scopedReleaseCount);
        assertEquals(0, bridge.globalReleaseCount);
    }

    @Test
    void parentCancellationWinsTheQueuedHandoffTick() {
        for (String interruption : List.of("manual", "emergency", "disconnect")) {
            SequenceBridge bridge = new SequenceBridge();
            PlayerActionController controller = new PlayerActionController(bridge, event -> {});
            controller.start(new ActionRequest("action:queued", "workflow:queued", "execute_sequence",
                residentSequence("first"), 100, ManualOverridePolicy.CANCEL, "native_fabric"));
            controller.tick();
            assertTrue(bridge.sequenceEngine.queueAdmittedSuccessor("first", "cp", residentSequence("next"), 2, 0, 1));
            controller.tick();
            if ("manual".equals(interruption)) bridge.snapshot = snapshot(20, true, true, "keyboard");
            else if ("emergency".equals(interruption)) controller.emergencyStop("operator stop");
            else bridge.snapshot = new PlayerSnapshot(false, 0, 64, 65.62, 0, 0, 0, 20, true, false, false, null);
            controller.tick();
            assertNotEquals(State.RUNNING, controller.state());
            assertTrue(bridge.released);
            controller.tick();
            assertEquals(1, bridge.jumpPulses);
        }
    }

    @Test
    void inputToTypedWorkflowDoesNotGloballyReleaseTheRunningParent() {
        SequenceBridge bridge = new SequenceBridge();
        PlayerActionController controller = new PlayerActionController(bridge, event -> {});
        controller.start(new ActionRequest("action:transition", "workflow:transition", "execute_sequence",
            sequence("input", List.of(), List.of(inputNode("input", "hotbar", "failed", 1),
                Map.of("node_id", "hotbar", "node_kind", "workflow_action", "earliest_tick", 0,
                    "timeout_ticks", 20, "action", Map.of("action_kind", "hotbar_select", "slot", 4),
                    "on_success", "done", "on_failure", "failed"),
                terminal("done", "succeeded"), terminal("failed", "failed"))),
            100, ManualOverridePolicy.CANCEL, "native_fabric"));
        controller.tick();
        controller.tick();
        assertEquals(State.RUNNING, controller.state());
        assertEquals(4, bridge.selectedSlot);
        assertEquals(0, bridge.globalReleaseCount);
        assertEquals(2, bridge.scopedReleaseCount); // input release and child settlement
        bridge.snapshot = snapshot(20, true, true, "keyboard");
        controller.tick();
        assertEquals(State.CANCELED, controller.state());
        assertTrue(bridge.released);
        assertTrue(bridge.globalReleaseCount > 0); // takeover and terminal cleanup may both release
    }

    @Test
    void delayedTickPastHorizonReportsHardStopWithoutEnteringStabilization() {
        SequenceBridge bridge = new SequenceBridge();
        java.util.List<WorkflowEvent> events = new java.util.ArrayList<>();
        PlayerActionController controller = new PlayerActionController(bridge, events::add);
        controller.start(new ActionRequest("action:late", "workflow:late", "execute_sequence",
            sequence("a", List.of(), List.of(inputNode("a", "done", "stop", 10),
                inputNode("stop", "done", "failed", 10), terminal("done", "succeeded"), terminal("failed", "failed"))),
            100, ManualOverridePolicy.CANCEL, "native_fabric"));
        long[] tick = {100};
        assertTrue(controller.attachTemporalWindow("action:late", new TemporalPlanWindow("origin", 100, 0, 1, 3, 4, 1000),
            () -> new PlayerActionController.TemporalClockSample("origin", tick[0], (tick[0] - 100) * 50.0), "stop"));
        controller.tick();
        tick[0] = 104;
        controller.tick();
        assertEquals(State.CANCELED, controller.state());
        assertEquals("temporal_horizon_exhausted", events.get(events.size() - 1).measurements().get("temporal_window_reason"));
        assertTrue(bridge.released);
        assertEquals(1, bridge.jumpPulses);
        assertEquals(0, bridge.scopedReleaseCount);
        controller.tick();
        assertEquals(1, bridge.jumpPulses);
    }

    @Test
    void manualAndEmergencyStopWinBeforeAndDuringStabilization() {
        for (boolean afterEntry : List.of(false, true)) {
            for (boolean emergency : List.of(false, true)) {
                SequenceBridge bridge = new SequenceBridge();
                PlayerActionController controller = new PlayerActionController(bridge, event -> {});
                controller.start(new ActionRequest("action:interrupt", "workflow:interrupt", "execute_sequence",
                    sequence("a", List.of(), List.of(inputNode("a", "done", "stop", 10),
                        inputNode("stop", "done", "failed", 10), terminal("done", "succeeded"), terminal("failed", "failed"))),
                    100, ManualOverridePolicy.CANCEL, "native_fabric"));
                long[] tick = {100};
                assertTrue(controller.attachTemporalWindow("action:interrupt", new TemporalPlanWindow("origin", 100, 0, 1, 3, 4, 1000),
                    () -> new PlayerActionController.TemporalClockSample("origin", tick[0], (tick[0] - 100) * 50.0), "stop"));
                controller.tick();
                tick[0]++;
                if (afterEntry) { controller.tick(); tick[0]++; }
                int pulses = bridge.jumpPulses;
                if (emergency) assertTrue(controller.emergencyStop("operator stop"));
                else bridge.snapshot = snapshot(20, true, true, "keyboard");
                controller.tick();
                assertEquals(emergency ? State.EMERGENCY_STOPPED : State.CANCELED, controller.state());
                assertTrue(bridge.released);
                controller.tick();
                assertEquals(pulses, bridge.jumpPulses);
            }
        }
    }

    @Test
    void successfulStabilizationIsNotOriginalWorkflowSuccess() {
        SequenceBridge bridge = new SequenceBridge();
        java.util.List<WorkflowEvent> events = new java.util.ArrayList<>();
        PlayerActionController controller = new PlayerActionController(bridge, events::add);
        controller.start(new ActionRequest("action:stop-success", "workflow:stop-success", "execute_sequence",
            sequence("a", List.of(), List.of(inputNode("a", "done", "done", 10), terminal("done", "succeeded"))),
            100, ManualOverridePolicy.CANCEL, "native_fabric"));
        long[] tick = {100};
        assertTrue(controller.attachTemporalWindow("action:stop-success", new TemporalPlanWindow("origin", 100, 0, 1, 3, 4, 1000),
            () -> new PlayerActionController.TemporalClockSample("origin", tick[0], (tick[0] - 100) * 50.0), "done"));
        controller.tick();
        tick[0]++;
        controller.tick();
        assertEquals(State.CANCELED, controller.state());
        WorkflowEvent last = events.get(events.size() - 1);
        assertEquals("action:stop-success", last.actionRequestId());
        assertEquals(false, last.measurements().get("sequence_completed"));
        assertEquals(true, last.measurements().get("stabilization_completed"));
        assertTrue(bridge.released);
        assertEquals(1, bridge.jumpPulses);
    }

    @Test
    void controllerEntersAdmittedStabilizationWithoutExtendingDeadline() {
        SequenceBridge bridge = new SequenceBridge();
        PlayerActionController controller = new PlayerActionController(bridge, event -> {});
        controller.start(new ActionRequest("action:stop", "workflow:stop", "execute_sequence",
            sequence("a", List.of(), List.of(inputNode("a", "done", "stop", 10),
                inputNode("stop", "done", "failed", 10), terminal("done", "succeeded"), terminal("failed", "failed"))),
            100, ManualOverridePolicy.CANCEL, "native_fabric"));
        long[] tick = {100};
        assertTrue(controller.attachTemporalWindow("action:stop", new TemporalPlanWindow("origin", 100, 0, 1, 3, 4, 100),
            () -> new PlayerActionController.TemporalClockSample("origin", tick[0], (tick[0] - 100) * 50.0), "stop"));
        controller.tick();
        assertEquals(1, bridge.jumpPulses);
        tick[0]++;
        controller.tick();
        assertEquals(State.RUNNING, controller.state());
        assertEquals(2, bridge.jumpPulses);
        assertEquals(0, bridge.globalReleaseCount);
        assertEquals(1, bridge.scopedReleaseCount);
        controller.tick();
        assertEquals(2, bridge.jumpPulses);
        tick[0]++;
        controller.tick();
        assertEquals(State.CANCELED, controller.state());
        assertTrue(bridge.released);
        assertEquals(2, bridge.jumpPulses);
    }

    @Test
    void stabilizationPreservesPerformedEvidenceAndCannotReplayOrRestart() {
        FakeBridge bridge = new FakeBridge();
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        engine.begin(sequence("a", List.of(), List.of(inputNode("a", "b", "failed", 10),
            inputNode("b", "done", "failed", 10), terminal("done", "succeeded"), terminal("failed", "failed"))));
        engine.step(1);
        assertTrue(bridge.movement.forward());
        assertFalse(engine.enterStabilization("a"));
        assertFalse(engine.enterStabilization("missing"));
        assertFalse(engine.enterStabilization(null));
        assertTrue(bridge.movement.forward()); // rejected target is inert
        assertTrue(engine.enterStabilization("failed"));
        assertTrue(bridge.released);
        assertFalse(engine.enterStabilization("b"));
        WorkflowStep stopped = engine.step(2);
        assertEquals(WorkflowStepStatus.FAILED, stopped.status());
        assertEquals(true, stopped.measurements().get("stabilization_entered"));
        assertEquals(List.of("a", "failed"), stopped.measurements().get("executed_node_ids"));
        assertEquals("canceled", ((Map<?, ?>) stopped.measurements().get("node_outcomes")).get("a"));
        assertEquals(1, bridge.jumpPulses);
        assertFalse(engine.enterStabilization("b"));
    }

    @Test
    void controllerUsesObservedPlanClockInsteadOfRestartingDeadlineOnDelivery() {
        for (long clientTick : List.of(102L, 103L)) {
            SequenceBridge bridge = new SequenceBridge();
            PlayerActionController controller = new PlayerActionController(bridge, event -> {});
            controller.start(new ActionRequest("action:clock", "workflow:clock", "execute_sequence",
                sequence("hotbar", List.of(), List.of(
                    Map.of("node_id", "hotbar", "node_kind", "workflow_action", "earliest_tick", 0,
                        "latest_start_tick", 2, "timeout_ticks", 20,
                        "action", Map.of("action_kind", "hotbar_select", "slot", 4),
                        "on_success", "done", "on_failure", "failed"),
                    terminal("done", "succeeded"), terminal("failed", "failed"))),
                100, ManualOverridePolicy.CANCEL, "native_fabric"));
            assertTrue(controller.attachTemporalWindow("action:clock", new TemporalPlanWindow("origin", 100, 3, 6, 8, 10, 1000),
                () -> new PlayerActionController.TemporalClockSample("origin", clientTick, (clientTick - 100) * 50.0)));
            controller.tick();
            assertEquals(clientTick == 102L ? 4 : -1, bridge.selectedSlot);
        }
    }

    @Test
    void controllerWindowStopsBeforeNextInputAndCannotBeReplaced() {
        SequenceBridge bridge = new SequenceBridge();
        PlayerActionController controller = new PlayerActionController(bridge, event -> {});
        controller.start(new ActionRequest("action:window", "workflow:window", "execute_sequence",
            sequence("a", List.of(), List.of(inputNode("a", "b", "failed", 1),
                inputNode("b", "done", "failed", 10), terminal("done", "succeeded"), terminal("failed", "failed"))),
            100, ManualOverridePolicy.CANCEL, "native_fabric"));
        long[] tick = {100};
        java.util.function.Supplier<PlayerActionController.TemporalClockSample> clock = () ->
            new PlayerActionController.TemporalClockSample("origin", tick[0], (tick[0] - 100) * 50.0);
        assertFalse(controller.attachTemporalWindow("other", new TemporalPlanWindow("origin", 100, 0, 1, 2, 3, 1000), clock));
        assertTrue(controller.attachTemporalWindow("action:window", new TemporalPlanWindow("origin", 100, 0, 1, 2, 3, 1000), clock));
        assertFalse(controller.attachTemporalWindow("action:window", new TemporalPlanWindow("origin", 100, 0, 2, 3, 4, 1000), clock));
        controller.tick();
        assertTrue(bridge.movement.forward());
        controller.tick(); // same native clock sample: do not replay segment entry
        assertEquals(1, bridge.jumpPulses);
        tick[0]++;
        controller.tick();
        assertEquals(State.CANCELED, controller.state());
        assertTrue(bridge.released);
        assertEquals(1, bridge.jumpPulses);
        controller.tick();
        assertEquals(1, bridge.jumpPulses);
    }

    @Test
    void controllerCancelsOnMissedSourceTickBeforeReplayingScheduledInput() {
        SequenceBridge bridge = new SequenceBridge();
        List<WorkflowEvent> events = new java.util.ArrayList<>();
        PlayerActionController controller = new PlayerActionController(bridge, events::add);
        controller.start(new ActionRequest("action:gap", "workflow:gap", "execute_sequence",
            sequence("a", List.of(), List.of(inputNode("a", "done", "failed", 5),
                terminal("done", "succeeded"), terminal("failed", "failed"))),
            100, ManualOverridePolicy.CANCEL, "native_fabric"));
        long[] tick = {100};
        assertTrue(controller.attachTemporalWindow("action:gap",
            new TemporalPlanWindow("origin", 100, 0, 6, 8, 10, 1000),
            () -> new PlayerActionController.TemporalClockSample("origin", tick[0], (tick[0] - 100) * 50.0)));
        controller.tick();
        assertTrue(bridge.movement.forward());
        assertEquals(1, bridge.jumpPulses);
        tick[0] = 102; // The source advanced, but the controller never processed tick 101.
        controller.tick();
        assertEquals(State.CANCELED, controller.state());
        assertTrue(bridge.released);
        assertEquals(1, bridge.jumpPulses);
        assertEquals(1L, events.get(events.size() - 1).measurements().get("temporal_missed_ticks"));
        controller.tick();
        assertEquals(1, bridge.jumpPulses);
    }

    @Test
    void controllerAddressesDelayedFirstInputBySourceTickNotCallCount() {
        SequenceBridge bridge = new SequenceBridge();
        PlayerActionController controller = new PlayerActionController(bridge, event -> {});
        Map<String, Object> scheduled = new LinkedHashMap<>(inputNode("a", "done", "failed", 2));
        scheduled.put("earliest_tick", 3);
        controller.start(new ActionRequest("action:offset", "workflow:offset", "execute_sequence",
            sequence("a", List.of(), List.of(scheduled, terminal("done", "succeeded"), terminal("failed", "failed"))),
            100, ManualOverridePolicy.CANCEL, "native_fabric"));
        long[] tick = {102};
        assertTrue(controller.attachTemporalWindow("action:offset",
            new TemporalPlanWindow("origin", 100, 0, 8, 9, 10, 1000),
            () -> new PlayerActionController.TemporalClockSample("origin", tick[0], (tick[0] - 100) * 50.0)));
        controller.tick();
        assertFalse(bridge.movement.forward());
        assertEquals(0, bridge.jumpPulses);
        tick[0] = 103;
        controller.tick();
        assertTrue(bridge.movement.forward());
        assertEquals(1, bridge.jumpPulses);
    }

    @Test
    void adjacentSegmentWaitsForItsOwnTickWithMovementReleased() {
        FakeBridge bridge = new FakeBridge();
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        Map<String, Object> second = new LinkedHashMap<>(inputNode("b", "done", "failed", 1));
        second.put("earliest_tick", 4);
        engine.begin(sequence("a", List.of(), List.of(inputNode("a", "b", "failed", 1), second,
            terminal("done", "succeeded"), terminal("failed", "failed"))));
        engine.step(1);
        assertTrue(bridge.movement.forward());
        for (long tick = 2; tick <= 4; tick++) {
            assertEquals(WorkflowStepStatus.RUNNING, engine.step(tick).status());
            assertFalse(bridge.movement.forward());
        }
        assertEquals(1, bridge.jumpPulses);
        engine.step(5);
        assertTrue(bridge.movement.forward());
        assertEquals(2, bridge.jumpPulses);
    }

    @Test
    void adjacentSegmentRechecksChangedSafetyBeforeApplyingInput() {
        FakeBridge bridge = new FakeBridge();
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        engine.begin(sequence("a", List.of(), List.of(inputNode("a", "b", "failed", 1),
            inputNode("b", "done", "failed", 1), terminal("done", "succeeded"), terminal("failed", "failed"))));
        engine.step(1);
        bridge.locomotionSafety = new LocomotionSafetyEnvelope.Check(
            LocomotionSafetyEnvelope.Decision.refuse("locomotion_predicted_drop_exceeded"),
            Map.of("reason_code", "locomotion_predicted_drop_exceeded", "effect_prevented", true));
        engine.step(2);
        assertFalse(bridge.movement.forward());
        assertTrue(bridge.released);
        assertEquals(1, bridge.jumpPulses);
        assertEquals(WorkflowStepStatus.FAILED, engine.step(3).status());
    }

    @Test
    void adjacentAdmittedSegmentsHaveNoReleaseTickAndTerminalStillReleases() {
        FakeBridge bridge = new FakeBridge();
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        engine.begin(sequence("a", List.of(), List.of(
            inputNode("a", "b", "failed", 2), inputNode("b", "c", "failed", 2),
            inputNode("c", "done", "failed", 2),
            terminal("done", "succeeded"), terminal("failed", "failed"))));
        for (long tick = 1; tick <= 6; tick++) {
            assertEquals(WorkflowStepStatus.RUNNING, engine.step(tick).status());
            assertTrue(bridge.movement.forward());
            assertEquals(0, bridge.releaseCalls);
        }
        assertEquals(3, bridge.jumpPulses); // one entry pulse per segment, no replay
        assertEquals(WorkflowStepStatus.SUCCEEDED, engine.step(7).status());
        assertEquals(1, bridge.releaseCalls);
        assertFalse(bridge.movement.forward());
    }

    @Test
    void actionStartDeadlineIsInclusiveAndPreventsLateEffects() {
        for (long actionTick : List.of(3L, 4L)) {
            SequenceBridge bridge = new SequenceBridge();
            FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
            engine.begin(sequence("node:hotbar", List.of(), List.of(
                Map.of("node_id", "node:hotbar", "node_kind", "workflow_action",
                    "earliest_tick", 0, "latest_start_tick", 2, "timeout_ticks", 20,
                    "action", Map.of("action_kind", "hotbar_select", "slot", 4),
                    "on_success", "node:succeeded", "on_failure", "node:failed"),
                terminal("node:succeeded", "succeeded"), terminal("node:failed", "failed"))));
            engine.step(actionTick);
            assertEquals(actionTick == 3L ? 4 : -1, bridge.selectedSlot);
            assertEquals(actionTick == 3L ? WorkflowStepStatus.SUCCEEDED : WorkflowStepStatus.FAILED,
                engine.step(actionTick + 1).status());
        }
    }

    @Test
    void executesTickAddressedInputThenVerifiesCheckpoint() {
        FakeBridge bridge = new FakeBridge();
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        engine.begin(sequence(
            "node:input",
            List.of("checkpoint:landed"),
            List.of(
                inputNode("node:input", "node:checkpoint", "node:failed", 2),
                Map.of(
                    "node_id", "node:checkpoint",
                    "node_kind", "checkpoint",
                    "earliest_tick", 1,
                    "checkpoint_id", "checkpoint:landed",
                    "condition", Map.of(
                        "condition_kind", "player_grounded",
                        "expected", true
                    ),
                    "wait_up_to_ticks", 10,
                    "on_satisfied", "node:succeeded",
                    "on_timeout", "node:failed"
                ),
                terminal("node:succeeded", "succeeded"),
                terminal("node:failed", "failed")
            )
        ));

        WorkflowStep first = engine.step(1);
        assertEquals(WorkflowStepStatus.RUNNING, first.status());
        assertEquals(List.of(), first.measurements().get("checkpoint_settlements"));
        assertTrue(bridge.movement.forward());
        assertTrue(bridge.movement.sprint());
        assertEquals(1, bridge.jumpPulses);

        WorkflowStep second = engine.step(2);
        assertEquals(WorkflowStepStatus.RUNNING, second.status());
        assertTrue(bridge.movement.forward());

        WorkflowStep terminal = engine.step(3);
        assertEquals(WorkflowStepStatus.SUCCEEDED, terminal.status());
        assertEquals(true, terminal.measurements().get("sequence_completed"));
        assertEquals(1L, terminal.measurements().get("required_checkpoints_satisfied"));
        assertEquals(3, terminal.measurements().get("executed_node_count"));
        assertEquals(3L, terminal.measurements().get("scheduler_ticks_elapsed"));
        assertEquals(1, terminal.measurements().get("condition_observation_count"));
        List<?> settlements = (List<?>) terminal.measurements().get("checkpoint_settlements");
        assertEquals(1, settlements.size());
        Map<?, ?> settlement = (Map<?, ?>) settlements.get(0);
        assertEquals("checkpoint:landed", settlement.get("checkpoint_id"));
        assertEquals("node:checkpoint", settlement.get("node_id"));
        assertEquals(2L, settlement.get("tick_index"));
        assertEquals(3L, settlement.get("scheduler_ticks_elapsed"));
        assertTrue(((Long) settlement.get("monotonic_elapsed_ns")) >= 0L);
        assertThrows(UnsupportedOperationException.class, settlements::clear);
    }

    @Test
    void zeroInputAndZeroLookDeltaDoNotClaimPlayerMotion() {
        FakeBridge bridge = new FakeBridge();
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        engine.begin(sequence(
            "node:stationary",
            List.of(),
            List.of(
                Map.of(
                    "node_id", "node:stationary",
                    "node_kind", "input_segment",
                    "earliest_tick", 0,
                    "duration_ticks", 2,
                    "controls", Map.of(
                        "forward", 0,
                        "strafe", 0,
                        "sprint", false,
                        "sneak", false,
                        "jump", "idle",
                        "use", "idle",
                        "look_delta", Map.of(
                            "yaw_degrees", 0,
                            "pitch_degrees", 0,
                            "max_degrees_per_tick", 1
                        )
                    ),
                    "on_complete", "node:succeeded",
                    "on_failure", "node:failed"
                ),
                terminal("node:succeeded", "succeeded"),
                terminal("node:failed", "failed")
            )
        ));

        assertEquals(WorkflowStepStatus.RUNNING, engine.step(1).status());
        assertEquals(WorkflowStepStatus.RUNNING, engine.step(2).status());
        WorkflowStep terminal = engine.step(3);

        assertEquals(WorkflowStepStatus.SUCCEEDED, terminal.status());
        assertEquals(false, terminal.measurements().get("player_motion_performed"));
        assertFalse(bridge.movement.forward());
        assertFalse(bridge.movement.back());
        assertFalse(bridge.movement.left());
        assertFalse(bridge.movement.right());
        assertTrue(bridge.released);
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputSegmentRefusesUnsafeLandingWithoutClaimingMotion() {
        FakeBridge bridge = new FakeBridge();
        bridge.locomotionSafety = new LocomotionSafetyEnvelope.Check(
            LocomotionSafetyEnvelope.Decision.refuse("locomotion_predicted_drop_exceeded"),
            Map.of(
                "reason_code", "locomotion_predicted_drop_exceeded",
                "effect_prevented", true,
                "predicted_drop_blocks", 3.0,
                "controls_released", true
            )
        );
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        engine.begin(sequence(
            "node:input",
            List.of(),
            List.of(
                inputNode("node:input", "node:succeeded", "node:failed", 2),
                terminal("node:succeeded", "succeeded"),
                terminal("node:failed", "failed")
            )
        ));

        assertEquals(WorkflowStepStatus.RUNNING, engine.step(1).status());
        WorkflowStep terminal = engine.step(2);

        assertEquals(WorkflowStepStatus.FAILED, terminal.status());
        assertFalse(bridge.movement.forward());
        assertTrue(bridge.released);
        assertEquals(false, terminal.measurements().get("player_motion_performed"));
        Map<String, Object> failure = (Map<String, Object>) terminal
            .measurements()
            .get("first_failure_measurements");
        assertEquals("locomotion_predicted_drop_exceeded", failure.get("reason_code"));
        assertEquals(true, failure.get("effect_prevented"));
    }

    @Test
    void reusesTheProductionControllerForEmbeddedActions() {
        SequenceBridge bridge = new SequenceBridge();
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        engine.begin(sequence(
            "node:hotbar",
            List.of(),
            List.of(
                Map.of(
                    "node_id", "node:hotbar",
                    "node_kind", "workflow_action",
                    "earliest_tick", 0,
                    "timeout_ticks", 20,
                    "action", Map.of("action_kind", "hotbar_select", "slot", 4),
                    "on_success", "node:succeeded",
                    "on_failure", "node:failed"
                ),
                terminal("node:succeeded", "succeeded"),
                terminal("node:failed", "failed")
            )
        ));

        assertEquals(WorkflowStepStatus.RUNNING, engine.step(1).status());
        assertEquals(4, bridge.selectedSlot);
        WorkflowStep terminal = engine.step(2);
        assertEquals(WorkflowStepStatus.SUCCEEDED, terminal.status());
        assertEquals(true, terminal.measurements().get("inventory_mutation_performed"));
        assertEquals(1, bridge.scopedReleaseCount);
        assertEquals(0, bridge.globalReleaseCount);
    }

    @Test
    void removesNullOptionalFieldsBeforeStartingAnEmbeddedAction() {
        FakeBridge bridge = new FakeBridge();
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        engine.begin(sequence(
            "node:craft",
            List.of(),
            List.of(
                Map.ofEntries(
                    Map.entry("node_id", "node:craft"),
                    Map.entry("node_kind", "workflow_action"),
                    Map.entry("earliest_tick", 0),
                    Map.entry("timeout_ticks", 20),
                    Map.entry("action", nullableCraftAction()),
                    Map.entry("on_success", "node:succeeded"),
                    Map.entry("on_failure", "node:failed")
                ),
                terminal("node:succeeded", "succeeded"),
                terminal("node:failed", "failed")
            )
        ));

        assertDoesNotThrow(() -> engine.step(1));
        assertEquals("craft", bridge.startedArguments.get("action_kind"));
        assertFalse(bridge.startedArguments.containsKey("recipe_id"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsTheFirstEmbeddedFailureDiagnosisForCodexRepair() {
        FakeBridge bridge = new FakeBridge() {
            @Override
            public boolean interact(String target, String hand, String interaction) {
                return false;
            }
        };
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        engine.begin(sequence(
            "node:interact",
            List.of(),
            List.of(
                Map.of(
                    "node_id", "node:interact",
                    "node_kind", "workflow_action",
                    "earliest_tick", 0,
                    "timeout_ticks", 40,
                    "action", Map.of(
                        "action_kind", "interact",
                        "target", "current_focus",
                        "hand", "main_hand",
                        "interaction", "use"
                    ),
                    "on_success", "node:succeeded",
                    "on_failure", "node:failed"
                ),
                terminal("node:succeeded", "succeeded"),
                terminal("node:failed", "failed")
            )
        ));

        WorkflowStep terminal = null;
        for (int tick = 1; tick <= 30; tick++) {
            terminal = engine.step(tick);
            if (terminal.status() == WorkflowStepStatus.FAILED) break;
        }

        assertNotNull(terminal);
        assertEquals(WorkflowStepStatus.FAILED, terminal.status());
        assertEquals("node:interact", terminal.measurements().get("first_failure_node_id"));
        assertEquals("workflow_action", terminal.measurements().get("first_failure_node_kind"));
        assertEquals("interact", terminal.measurements().get("first_failure_action_kind"));
        assertEquals(
            "No compatible block or entity became available during the bounded focus acquisition window.",
            terminal.measurements().get("first_failure_summary")
        );
        Map<String, Object> measurements = (Map<String, Object>) terminal
            .measurements()
            .get("first_failure_measurements");
        assertEquals(true, measurements.get("focus_acquisition_pending"));
        assertEquals(10, measurements.get("interaction_attempt_count"));
    }

    @Test
    void acceptsTheSchemaAdmittedTrackingActionInsideASequence() {
        FakeBridge bridge = new FakeBridge();
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        Map<String, Object> trackingAction = Map.ofEntries(
            Map.entry("action_kind", "track_target"),
            Map.entry("target", Map.of("target_kind", "current_focus_entity")),
            Map.entry("aim_point", "center"),
            Map.entry("max_acquisition_distance", 6),
            Map.entry("max_duration_ms", 1_000),
            Map.entry("max_turn_degrees_per_tick", 30),
            Map.entry("max_angular_acceleration_degrees_per_tick_squared", 60),
            Map.entry("prediction_ticks", 0),
            Map.entry("deadband_degrees", 2),
            Map.entry("reacquire_ticks", 10),
            Map.entry("require_line_of_sight", true),
            Map.entry("stop_below_health", 1)
        );

        assertDoesNotThrow(() -> engine.begin(sequence(
            "node:track",
            List.of(),
            List.of(
                Map.of(
                    "node_id", "node:track",
                    "node_kind", "workflow_action",
                    "earliest_tick", 0,
                    "timeout_ticks", 40,
                    "action", trackingAction,
                    "on_success", "node:succeeded",
                    "on_failure", "node:failed"
                ),
                terminal("node:succeeded", "succeeded"),
                terminal("node:failed", "failed")
            )
        )));
    }

    private static Map<String, Object> nullableCraftAction() {
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("action_kind", "craft");
        action.put("output_item_id", "minecraft:oak_planks");
        action.put("count", 4);
        action.put("recipe_id", null);
        return action;
    }

    @Test
    @SuppressWarnings("unchecked")
    void passesSequenceMutationScopeToADynamicPlacementWorkflow() {
        FakeBridge bridge = new FakeBridge();
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        Map<String, Object> candidate = new LinkedHashMap<>(sequence(
            "node:place",
            List.of(),
            List.of(
                Map.of(
                    "node_id", "node:place",
                    "node_kind", "workflow_action",
                    "earliest_tick", 0,
                    "timeout_ticks", 20,
                    "action", Map.ofEntries(
                        Map.entry("action_kind", "place"),
                        Map.entry("block_id", "minecraft:water"),
                        Map.entry("position_binding", Map.of(
                            "binding_kind", "predicted_collision_cell",
                            "horizon_ticks", 5,
                            "max_distance_blocks", 6,
                            "require_replaceable", true
                        )),
                        Map.entry("placement_method", "item_use"),
                        Map.entry("source_item_id", "minecraft:water_bucket"),
                        Map.entry("hand", "main_hand")
                    ),
                    "on_success", "node:succeeded",
                    "on_failure", "node:failed"
                ),
                terminal("node:succeeded", "succeeded"),
                terminal("node:failed", "failed")
            )
        ));
        candidate.put("mutation_scope", Map.of(
            "world_mutation_allowed", true,
            "max_block_mutations", 1,
            "max_inventory_transfers", 1,
            "allowed_block_ids", List.of("minecraft:water"),
            "allowed_regions", List.of(Map.of(
                "min", Map.of("x", -82, "y", 80, "z", -40),
                "max", Map.of("x", -78, "y", 82, "z", -36)
            )),
            "combat_allowed", false
        ));
        engine.begin(Map.copyOf(candidate));

        assertEquals(WorkflowStepStatus.RUNNING, engine.step(1).status());
        Map<String, Object> scope = (Map<String, Object>) bridge.startedArguments.get(
            "_helix_admitted_mutation_scope"
        );
        assertEquals(true, scope.get("world_mutation_allowed"));
        assertEquals(List.of("minecraft:water"), scope.get("allowed_block_ids"));
    }

    @Test
    void branchFailureRemainsATypedTerminalInsteadOfInventingARepair() {
        FakeBridge bridge = new FakeBridge();
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        engine.begin(sequence(
            "node:branch",
            List.of(),
            List.of(
                Map.of(
                    "node_id", "node:branch",
                    "node_kind", "branch",
                    "earliest_tick", 0,
                    "condition", Map.of(
                        "condition_kind", "health_at_least",
                        "health", 20.0
                    ),
                    "on_true", "node:succeeded",
                    "on_false", "node:failed"
                ),
                terminal("node:succeeded", "succeeded"),
                terminal("node:failed", "failed")
            )
        ));
        bridge.snapshot = snapshot(10.0F, true, false, null);

        WorkflowStep terminal = engine.step(1);
        assertEquals(WorkflowStepStatus.FAILED, terminal.status());
        assertEquals(false, terminal.measurements().get("sequence_completed"));
        assertEquals("tas_terminal", terminal.measurements().get("terminal_reason_code"));
    }

    @Test
    void recordsConditionChangesAsCompactEventsInsteadOfPerTickNoise() {
        FakeBridge bridge = new FakeBridge();
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        engine.begin(sequence(
            "node:dimension",
            List.of("checkpoint:dimension"),
            List.of(
                Map.of(
                    "node_id", "node:dimension",
                    "node_kind", "checkpoint",
                    "earliest_tick", 0,
                    "checkpoint_id", "checkpoint:dimension",
                    "condition", Map.of(
                        "condition_kind", "dimension_is",
                        "dimension", "minecraft:overworld"
                    ),
                    "wait_up_to_ticks", 10,
                    "on_satisfied", "node:succeeded",
                    "on_timeout", "node:failed"
                ),
                terminal("node:succeeded", "succeeded"),
                terminal("node:failed", "failed")
            )
        ));

        assertEquals(WorkflowStepStatus.RUNNING, engine.step(1).status());
        assertEquals(WorkflowStepStatus.RUNNING, engine.step(2).status());
        bridge.worldCondition = true;
        WorkflowStep terminal = engine.step(3);

        assertEquals(WorkflowStepStatus.SUCCEEDED, terminal.status());
        Object raw = terminal.measurements().get("condition_observations");
        assertInstanceOf(List.class, raw);
        List<?> observations = (List<?>) raw;
        assertEquals(2, observations.size());
        assertEquals(2, terminal.measurements().get("condition_observation_count"));
        assertEquals(false, ((Map<?, ?>) observations.get(0)).get("satisfied"));
        assertEquals(true, ((Map<?, ?>) observations.get(1)).get("satisfied"));
    }

    @Test
    void rejectsCyclesBeforeAnyControlIsAsserted() {
        FakeBridge bridge = new FakeBridge();
        FluidSequenceEngine engine = new FluidSequenceEngine(bridge);
        Map<String, Object> cyclic = inputNode(
            "node:input",
            "node:input",
            "node:failed",
            1
        );
        assertThrows(IllegalArgumentException.class, () -> engine.begin(sequence(
            "node:input",
            List.of(),
            List.of(
                cyclic,
                terminal("node:succeeded", "succeeded"),
                terminal("node:failed", "failed")
            )
        )));
        assertEquals(MovementInput.released(), bridge.movement);
    }

    @Test
    void outerControllerManualOverrideCancelsSequenceAndReleasesControls() {
        SequenceBridge bridge = new SequenceBridge();
        PlayerActionController controller = new PlayerActionController(bridge, event -> {});
        Map<String, Object> arguments = sequence(
            "node:input",
            List.of(),
            List.of(
                inputNode("node:input", "node:next", "node:failed", 1),
                inputNode("node:next", "node:succeeded", "node:failed", 20),
                terminal("node:succeeded", "succeeded"),
                terminal("node:failed", "failed")
            )
        );
        assertTrue(controller.start(new ActionRequest(
            "action_request:sequence-override",
            "workflow:sequence-override",
            "execute_sequence",
            arguments,
            100,
            ManualOverridePolicy.CANCEL,
            "native_fabric"
        )));
        controller.tick();
        assertTrue(bridge.movement.forward());

        bridge.snapshot = snapshot(20.0F, true, true, "forward_key_pressed");
        controller.tick();

        assertEquals(State.CANCELED, controller.state());
        assertTrue(bridge.released);
        assertEquals(MovementInput.released(), bridge.movement);
        assertEquals(1, bridge.jumpPulses); // next segment never entered at the same-tick boundary
        controller.tick();
        assertEquals(1, bridge.jumpPulses);
    }

    @Test
    void outerControllerEmergencyStopReleasesSequenceControls() {
        SequenceBridge bridge = new SequenceBridge();
        PlayerActionController controller = new PlayerActionController(bridge, event -> {});
        Map<String, Object> arguments = sequence(
            "node:input",
            List.of(),
            List.of(
                inputNode("node:input", "node:next", "node:failed", 1),
                inputNode("node:next", "node:succeeded", "node:failed", 20),
                terminal("node:succeeded", "succeeded"),
                terminal("node:failed", "failed")
            )
        );
        controller.start(new ActionRequest(
            "action_request:sequence-stop",
            "workflow:sequence-stop",
            "execute_sequence",
            arguments,
            100,
            ManualOverridePolicy.CANCEL,
            "native_fabric"
        ));
        controller.tick();
        assertTrue(bridge.movement.forward());

        assertTrue(controller.emergencyStop("test stop"));
        assertEquals(State.EMERGENCY_STOPPED, controller.state());
        assertTrue(bridge.released);
        assertEquals(MovementInput.released(), bridge.movement);
        controller.tick();
        assertEquals(1, bridge.jumpPulses);
    }

    private static Map<String, Object> sequence(
        String start,
        List<String> checkpoints,
        List<Map<String, Object>> nodes
    ) {
        return Map.ofEntries(
            Map.entry("action_kind", "execute_sequence"),
            Map.entry("sequence_schema", "helix.minecraft.player_sequence.v1"),
            Map.entry("sequence_id", "sequence:test"),
            Map.entry("ruleset", "survival_tas"),
            Map.entry("execution_plane", "player_embodiment"),
            Map.entry("scheduler_engine", "native_fabric"),
            Map.entry("optimization", Map.of(
                "primary", "minimize_world_ticks",
                "record_wall_clock", true,
                "stop_on_first_verified_success", true
            )),
            Map.entry("start_node_id", start),
            Map.entry("max_total_ticks", 200),
            Map.entry("required_checkpoint_ids", checkpoints),
            Map.entry("mutation_scope", Map.of(
                "world_mutation_allowed", false,
                "max_block_mutations", 0,
                "max_inventory_transfers", 0,
                "allowed_block_ids", List.of(),
                "allowed_regions", List.of(),
                "combat_allowed", false
            )),
            Map.entry("nodes", nodes)
        );
    }

    private static Map<String, Object> inputNode(
        String id,
        String complete,
        String failure,
        int duration
    ) {
        return Map.of(
            "node_id", id,
            "node_kind", "input_segment",
            "earliest_tick", 0,
            "duration_ticks", duration,
            "controls", Map.of(
                "forward", 1,
                "strafe", 0,
                "sprint", true,
                "sneak", false,
                "jump", "pulse",
                "use", "idle"
            ),
            "on_complete", complete,
            "on_failure", failure
        );
    }

    private static Map<String, Object> terminal(String id, String outcome) {
        return Map.of(
            "node_id", id,
            "node_kind", "terminal",
            "terminal_outcome", outcome,
            "reason_code", "tas_terminal"
        );
    }

    private static PlayerSnapshot snapshot(
        float health,
        boolean grounded,
        boolean manual,
        String manualReason
    ) {
        return new PlayerSnapshot(
            true,
            0,
            64,
            65.62,
            0,
            0,
            0,
            health,
            grounded,
            false,
            manual,
            manualReason
        );
    }

    private static class FakeBridge implements ControlBridge {
        protected int releaseCalls;
        protected PlayerSnapshot snapshot = FluidSequenceEngineTest.snapshot(
            20.0F,
            true,
            false,
            null
        );
        protected MovementInput movement = MovementInput.released();
        protected boolean released;
        protected int jumpPulses;
        protected int selectedSlot = -1;
        protected boolean worldCondition;
        protected Map<String, Object> startedArguments = Map.of();
        protected LocomotionSafetyEnvelope.Check locomotionSafety =
            new LocomotionSafetyEnvelope.Check(
                LocomotionSafetyEnvelope.Decision.admit(),
                Map.of("reason_code", "locomotion_safety_admitted")
            );

        @Override
        public PlayerSnapshot snapshot() {
            return snapshot;
        }

        @Override
        public void applyMovement(MovementInput movement) {
            this.movement = movement;
            released = false;
        }

        @Override
        public LocomotionSafetyEnvelope.Check checkLocomotionSafety(
            double targetX,
            double targetZ,
            double minimumHealth,
            boolean controlledJumpArc
        ) {
            return locomotionSafety;
        }

        @Override
        public void lookAt(double x, double y, double z, float maxDegreesPerTick) {}

        @Override
        public void lookTo(float yaw, float pitch, float maxDegreesPerTick) {}

        @Override
        public void pulseJump() {
            jumpPulses++;
        }

        @Override
        public boolean interact(String target, String hand, String interaction) {
            return true;
        }

        @Override
        public boolean selectHotbar(int slot) {
            selectedSlot = slot;
            return slot >= 0 && slot <= 8;
        }

        @Override
        public boolean equip(String itemId, String destination) {
            return true;
        }

        @Override
        public boolean evaluateFluidWorldCondition(Map<String, Object> condition) {
            return worldCondition;
        }

        @Override
        public void beginWorkflow(
            String actionKind,
            Map<String, Object> arguments,
            String controlEngine
        ) {
            startedArguments = Map.copyOf(arguments);
        }

        @Override
        public WorkflowStep runWorkflowStep(
            String actionKind,
            Map<String, Object> arguments,
            String controlEngine,
            long actionTicks
        ) {
            return WorkflowStep.running(
                0.5,
                "test workflow is running",
                Map.of()
            );
        }

        @Override
        public void releaseAll() {
            releaseCalls++;
            movement = MovementInput.released();
            released = true;
        }
    }

    static final class SequenceBridge extends FakeBridge {
        @Override
        public boolean queueTemporalSequenceSuccessor(String predecessorSequenceId, String checkpointId,
            Map<String, Object> arguments, long boundaryTick, long receivedTick, long stopTick, long sourceOriginTick) {
            return sequenceEngine.queueAdmittedSuccessor(predecessorSequenceId, checkpointId, arguments,
                boundaryTick, receivedTick, stopTick, sourceOriginTick);
        }
        @Override
        public boolean enterWorkflowStabilization(String actionKind, String nodeId) {
            return "execute_sequence".equals(actionKind) && sequenceEngine.enterStabilization(nodeId);
        }
        private final FluidSequenceEngine sequenceEngine = new FluidSequenceEngine(this);
        private int globalReleaseCount;
        private int scopedReleaseCount;

        @Override
        public void beginWorkflow(
            String actionKind,
            Map<String, Object> arguments,
            String controlEngine
        ) {
            if ("execute_sequence".equals(actionKind)) sequenceEngine.begin(arguments);
        }

        @Override
        public WorkflowStep runWorkflowStep(
            String actionKind,
            Map<String, Object> arguments,
            String controlEngine,
            long actionTicks
        ) {
            return "execute_sequence".equals(actionKind)
                ? sequenceEngine.step(actionTicks)
                : WorkflowStep.failed("unsupported", Map.of());
        }

        @Override
        public void releaseResources(Set<String> resources) {
            scopedReleaseCount++;
            super.releaseAll();
        }

        @Override
        public void releaseAll() {
            globalReleaseCount++;
            super.releaseAll();
        }
    }
}
