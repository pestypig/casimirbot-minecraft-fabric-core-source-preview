package com.casimirbot.helixplayer.fabric;

import java.util.Map;

/** Internal integrity/identity/clock preflight only. Does not validate compiled
 * graph equivalence, admit extensions, or authorize controller execution. */
final class TemporalPlanDeliveryPreflight {
    record SerialInitial(TemporalPlanClockBinding.BoundWindow clock, String stabilizationNode) {}

    @SuppressWarnings("unchecked")
    static SerialInitial initialSerial(Map<String, Object> wire, PlayerActionConfig config,
                                       String producerEpoch, String origin, long tick, double elapsedMs) {
        var prepared = serial(wire, config, producerEpoch, origin, tick, elapsedMs);
        Map<?, ?> plan = (Map<?, ?>) wire.get("temporal_plan");
        if (!plan.containsKey("previous_plan_id") || plan.get("previous_plan_id") != null ||
            !plan.containsKey("previous_plan_hash") || plan.get("previous_plan_hash") != null) throw invalid();
        return prepared;
    }

    static SerialInitial successorSerial(Map<String, Object> wire, Map<String, Object> current,
        PlayerActionConfig config, String producerEpoch, String origin, long tick, double elapsedMs) {
        var prepared = serial(wire, config, producerEpoch, origin, tick, elapsedMs);
        if (!(current.get("temporal_plan") instanceof Map<?, ?> previous) ||
            !(wire.get("temporal_plan") instanceof Map<?, ?> plan) ||
            !(previous.get("identity") instanceof Map<?, ?> previousIdentity) ||
            !(plan.get("identity") instanceof Map<?, ?> identity) ||
            !java.util.Objects.equals(previous.get("plan_id"), plan.get("previous_plan_id")) ||
            !java.util.Objects.equals(previous.get("plan_hash"), plan.get("previous_plan_hash")) ||
            java.util.Objects.equals(previous.get("plan_id"), plan.get("plan_id")) ||
            !java.util.Objects.equals(previousIdentity.get("goal_id"), identity.get("goal_id"))) throw invalid();
        for (String field : new String[]{"run_id", "action_authority_id", "environment_binding_id", "source_id",
                "room_id", "world_id", "participant_id", "subject_binding_id", "subject_native_id"}) {
            if (!(current.get(field) instanceof String expected) || expected.isBlank() || !expected.equals(wire.get(field))) throw invalid();
        }
        if (!(wire.get("action_request_id") instanceof String action) || action.isBlank() || action.equals(current.get("action_request_id"))) throw invalid();
        return prepared;
    }

    @SuppressWarnings("unchecked")
    private static SerialInitial serial(Map<String, Object> wire, PlayerActionConfig config,
                                       String producerEpoch, String origin, long tick, double elapsedMs) {
        var bound = verify(wire, config, producerEpoch, origin, tick, elapsedMs);
        Map<String, Object> plan = (Map<String, Object>) wire.get("temporal_plan");
        if (!"execute_sequence".equals(wire.get("action_kind")) ||
            !"native_fabric".equals(wire.get("requested_control_engine")) ||
            !(wire.get("arguments") instanceof Map<?, ?> rawArguments)) throw invalid();
        Map<String, Object> arguments = (Map<String, Object>) rawArguments;
        FluidSequenceEngine.validate(arguments);
        if (!plan.get("plan_id").equals(arguments.get("sequence_id")) ||
            !(plan.get("maximum_total_units") instanceof Number maximum) ||
            !(arguments.get("max_total_ticks") instanceof Number ticksMaximum) ||
            maximum.doubleValue() != ticksMaximum.doubleValue()) throw invalid();
        Map<?, ?> watermarks = (Map<?, ?>) plan.get("watermarks");
        Object stabilization = watermarks.get("stabilization_node_id");
        if (!(stabilization instanceof String nodeId) || nodeId.isBlank() ||
            !(arguments.get("nodes") instanceof java.util.List<?> nodes) ||
            nodes.stream().noneMatch(node -> node instanceof Map<?, ?> map && nodeId.equals(map.get("node_id")))) throw invalid();
        return new SerialInitial(bound, nodeId);
    }

    @SuppressWarnings("unchecked")
    static TemporalPlanClockBinding.BoundWindow verify(Map<String, Object> wire, PlayerActionConfig config,
                                                       String producerEpoch, String origin, long tick, double elapsedMs) {
        if (!(wire.get("temporal_plan") instanceof Map<?, ?> raw) ||
            !(wire.get("temporal_plan_canonical_json") instanceof String canonical)) throw invalid();
        Map<String, Object> plan = (Map<String, Object>) raw;
        TemporalPlanHashContent.verify(plan, canonical);
        TemporalCompilationContent.verify(wire, plan);
        if (!"environment.temporal_action_plan.v1".equals(plan.get("schema")) ||
            !(plan.get("identity") instanceof Map<?, ?> identity)) throw invalid();
        for (String field : new String[]{"automatic_replay", "adapter_strategy_authority", "answer_authority", "assistant_answer", "terminal_eligible"}) {
            if (!Boolean.FALSE.equals(plan.get(field))) throw invalid();
        }
        same(identity.get("environment_id"), wire.get("environment_binding_id"), config.environmentBindingId());
        same(identity.get("source_id"), wire.get("source_id"), config.sourceId());
        same(identity.get("subject_id"), wire.get("subject_binding_id"), config.subjectBindingId());
        same(identity.get("authority_id"), wire.get("action_authority_id"), config.actionAuthorityId());
        same(wire.get("room_id"), wire.get("room_id"), config.roomId());
        same(wire.get("world_id"), wire.get("world_id"), config.worldId());
        same(wire.get("participant_id"), wire.get("participant_id"), config.participantId());
        same(wire.get("subject_native_id"), wire.get("subject_native_id"), config.subjectNativeId());
        if (config.policyVersion() <= 0 || !(identity.get("authority_revision") instanceof Number revision) ||
            revision.doubleValue() != config.policyVersion()) throw invalid();
        return TemporalPlanClockBinding.decode(plan, producerEpoch, origin, tick, elapsedMs);
    }
    private static void same(Object source, Object wire, String paired) {
        if (paired == null || paired.isBlank() || !paired.equals(source) || !paired.equals(wire)) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("temporal_plan_identity_mismatch"); }
}
