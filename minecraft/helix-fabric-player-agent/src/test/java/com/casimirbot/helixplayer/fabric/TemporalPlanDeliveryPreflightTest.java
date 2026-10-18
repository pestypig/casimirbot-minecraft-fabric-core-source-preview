package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.*;
import com.casimirbot.helixsensor.HelixJson;
import com.casimirbot.helixsensor.snapshot.SectionHasher;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import org.junit.jupiter.api.Test;

final class TemporalPlanDeliveryPreflightTest {
    @Test void matchesPairedIdentityAndRejectsCrossBoundDelivery() {
        PlayerActionConfig config = new PlayerActionConfig("", "", "authority", "installation", "env", "room", "source", "world",
            "adapter", PlayerActionConfig.DOMAIN_ADAPTER, "participant", "subject", "player", 1, "2030-01-01T00:00:00Z");
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("schema", "environment.temporal_action_plan.v1");
        plan.put("plan_id", "plan");
        plan.put("identity", Map.of("environment_id", "env", "source_id", "source", "subject_id", "subject",
            "authority_id", "authority", "authority_revision", 1, "producer_epoch", "epoch", "goal_id", "goal", "goal_revision", 1));
        for (String field : new String[]{"automatic_replay", "adapter_strategy_authority", "answer_authority", "assistant_answer", "terminal_eligible"}) plan.put(field, false);
        plan.put("clocks", Map.of("environment", Map.of("kind", "tick", "resolution_unit", "minecraft_tick", "sequence", 100),
            "monotonic", Map.of("origin_id", "origin", "elapsed_ms", 100)));
        plan.put("watermarks", Map.of("decision_unit", 3, "stop_unit", 6, "committed_through_unit", 8));
        plan.put("maximum_total_units", 10);
        plan.put("monotonic_deadline_elapsed_ms", 1000);
        // Local semantic fixture, not evidence of a complete server-admitted graph.
        String canonical = HelixJson.stringifyIncludingNulls(plan);
        plan.put("plan_hash", SectionHasher.hashIncludingNulls(plan));
        Map<String, Object> wire = new LinkedHashMap<>(Map.of("environment_binding_id", "env", "source_id", "source",
            "subject_binding_id", "subject", "action_authority_id", "authority", "room_id", "room", "world_id", "world",
            "participant_id", "participant", "subject_native_id", "player", "temporal_plan", plan, "temporal_plan_canonical_json", canonical));
        Map<String, Object> arguments = Map.of("action_kind", "execute_sequence", "sequence_schema", "schema:test");
        Map<String, Object> compiled = new LinkedHashMap<>(Map.of("schema", "environment.minecraft_temporal_plan_compilation.v1",
            "source_plan_id", "plan", "source_plan_hash", plan.get("plan_hash"), "source_goal_id", "goal", "source_goal_revision", 1,
            "arguments", arguments, "target_schema", "schema:test"));
        for (String field : new String[]{"execution_authority", "answer_authority", "assistant_answer", "terminal_eligible"}) compiled.put(field, false);
        wire.put("action_kind", "execute_sequence");
        wire.put("arguments", arguments);
        wire.put("temporal_compilation_canonical_json", HelixJson.stringifyIncludingNulls(compiled));
        wire.put("temporal_compilation_hash", SectionHasher.hashIncludingNulls(compiled));
        assertEquals(2, TemporalPlanDeliveryPreflight.verify(wire, config, "epoch", "origin", 102, 200).executionOffsetTicks());
        for (String key : new String[]{"environment_binding_id", "source_id", "subject_binding_id", "action_authority_id",
                                      "room_id", "world_id", "participant_id", "subject_native_id"}) {
            Object prior = wire.put(key, "other");
            assertThrows(IllegalArgumentException.class, () -> TemporalPlanDeliveryPreflight.verify(wire, config, "epoch", "origin", 102, 200));
            wire.put(key, prior);
        }
        assertThrows(IllegalArgumentException.class, () -> TemporalPlanDeliveryPreflight.verify(wire, config, "other", "origin", 102, 200));
        wire.put("arguments", Map.of("action_kind", "execute_sequence", "sequence_schema", "other"));
        assertThrows(IllegalArgumentException.class, () -> TemporalPlanDeliveryPreflight.verify(wire, config, "epoch", "origin", 102, 200));
        Map<String, Object> serial = new LinkedHashMap<>(Map.of(
            "action_kind", "execute_sequence", "sequence_schema", "helix.minecraft.player_sequence.v1",
            "sequence_id", "plan", "ruleset", "survival_tas", "execution_plane", "player_embodiment",
            "scheduler_engine", "native_fabric", "start_node_id", "check", "max_total_ticks", 10,
            "required_checkpoint_ids", List.of(), "nodes", List.of(
                Map.of("node_id", "check", "node_kind", "checkpoint", "checkpoint_id", "checkpoint",
                    "condition", Map.of("condition_kind", "player_grounded", "expected", true),
                    "earliest_tick", 0, "wait_up_to_ticks", 1, "on_satisfied", "done", "on_timeout", "failed"),
                Map.of("node_id", "done", "node_kind", "terminal", "terminal_outcome", "succeeded", "reason_code", "done"),
                Map.of("node_id", "failed", "node_kind", "terminal", "terminal_outcome", "failed", "reason_code", "failed"))));
        serial.put("mutation_scope", Map.of("world_mutation_allowed", false, "max_block_mutations", 0,
            "max_inventory_transfers", 0, "allowed_block_ids", List.of(), "allowed_regions", List.of(), "combat_allowed", false));
        serial.put("optimization", Map.of("primary", "minimize_world_ticks", "record_wall_clock", true, "stop_on_first_verified_success", true));
        plan.put("previous_plan_id", null); plan.put("previous_plan_hash", null);
        plan.put("watermarks", Map.of("decision_unit", 3, "stop_unit", 6, "committed_through_unit", 8, "stabilization_node_id", "failed"));
        wire.put("arguments", serial); wire.put("requested_control_engine", "native_fabric");
        resign(wire, plan, compiled, serial);
        assertEquals("failed", TemporalPlanDeliveryPreflight.initialSerial(wire, config, "epoch", "origin", 102, 200).stabilizationNode());
        wire.put("run_id", "run"); wire.put("action_request_id", "root-action");
        Map<String, Object> current = new LinkedHashMap<>(wire);
        current.put("temporal_plan", new LinkedHashMap<>(plan));
        String priorHash = String.valueOf(plan.get("plan_hash"));
        plan.put("plan_id", "next"); plan.put("previous_plan_id", "plan"); plan.put("previous_plan_hash", priorHash);
        serial.put("sequence_id", "next"); wire.put("action_request_id", "next-action");
        resign(wire, plan, compiled, serial);
        assertEquals("failed", TemporalPlanDeliveryPreflight.successorSerial(wire, current, config, "epoch", "origin", 102, 200).stabilizationNode());
        wire.put("run_id", "other-run");
        assertThrows(IllegalArgumentException.class, () -> TemporalPlanDeliveryPreflight.successorSerial(wire, current, config, "epoch", "origin", 102, 200));
        wire.put("run_id", "run"); plan.put("previous_plan_hash", "wrong"); resign(wire, plan, compiled, serial);
        assertThrows(IllegalArgumentException.class, () -> TemporalPlanDeliveryPreflight.successorSerial(wire, current, config, "epoch", "origin", 102, 200));
        plan.put("plan_id", "plan"); serial.put("sequence_id", "plan");
        plan.put("previous_plan_id", "other"); plan.put("previous_plan_hash", "hash");
        resign(wire, plan, compiled, serial);
        assertThrows(IllegalArgumentException.class, () -> TemporalPlanDeliveryPreflight.initialSerial(wire, config, "epoch", "origin", 102, 200));
        plan.put("previous_plan_id", null); plan.put("previous_plan_hash", null);
        serial.put("sequence_id", "wrong"); resign(wire, plan, compiled, serial);
        assertThrows(IllegalArgumentException.class, () -> TemporalPlanDeliveryPreflight.initialSerial(wire, config, "epoch", "origin", 102, 200));
        serial.put("sequence_id", "plan"); serial.put("max_total_ticks", 9); resign(wire, plan, compiled, serial);
        assertThrows(IllegalArgumentException.class, () -> TemporalPlanDeliveryPreflight.initialSerial(wire, config, "epoch", "origin", 102, 200));
        serial.put("max_total_ticks", 10); serial.put("nodes", List.of()); resign(wire, plan, compiled, serial);
        assertThrows(IllegalArgumentException.class, () -> TemporalPlanDeliveryPreflight.initialSerial(wire, config, "epoch", "origin", 102, 200));
    }

    private static void resign(Map<String, Object> wire, Map<String, Object> plan, Map<String, Object> compiled, Map<String, Object> arguments) {
        plan.remove("plan_hash");
        wire.put("temporal_plan_canonical_json", HelixJson.stringifyIncludingNulls(plan));
        plan.put("plan_hash", SectionHasher.hashIncludingNulls(plan));
        compiled.put("source_plan_hash", plan.get("plan_hash"));
        compiled.put("source_plan_id", plan.get("plan_id"));
        compiled.put("arguments", arguments); compiled.put("target_schema", arguments.get("sequence_schema"));
        wire.put("temporal_compilation_canonical_json", HelixJson.stringifyIncludingNulls(compiled));
        wire.put("temporal_compilation_hash", SectionHasher.hashIncludingNulls(compiled));
    }
}
