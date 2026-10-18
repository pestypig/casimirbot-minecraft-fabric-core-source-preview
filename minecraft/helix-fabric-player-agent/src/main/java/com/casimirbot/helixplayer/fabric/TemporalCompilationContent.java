package com.casimirbot.helixplayer.fabric;

import com.casimirbot.helixsensor.HelixJson;
import java.util.Map;
import java.util.Objects;

/** Verifies the admitted compiler artifact, not compiler correctness or authority. */
final class TemporalCompilationContent {
    static void verify(Map<String, Object> wire, Map<String, Object> plan) {
        if (!(wire.get("temporal_compilation_canonical_json") instanceof String canonical)) throw invalid();
        Map<?, ?> compiled = TemporalPlanHashContent.decode(wire.get("temporal_compilation_hash"), canonical);
        if (!(plan.get("identity") instanceof Map<?, ?> identity) ||
            !(plan.get("plan_id") instanceof String planId) || planId.isBlank() ||
            !(plan.get("plan_hash") instanceof String planHash) || planHash.isBlank() ||
            !(identity.get("goal_id") instanceof String goalId) || goalId.isBlank() ||
            !(identity.get("goal_revision") instanceof Number revision) || !Double.isFinite(revision.doubleValue()) ||
            revision.doubleValue() < 1 || revision.doubleValue() != Math.rint(revision.doubleValue()) ||
            !"environment.minecraft_temporal_plan_compilation.v1".equals(compiled.get("schema")) ||
            !Objects.equals(plan.get("plan_id"), compiled.get("source_plan_id")) ||
            !Objects.equals(plan.get("plan_hash"), compiled.get("source_plan_hash")) ||
            !Objects.equals(identity.get("goal_id"), compiled.get("source_goal_id")) ||
            !HelixJson.stringifyIncludingNulls(identity.get("goal_revision")).equals(HelixJson.stringifyIncludingNulls(compiled.get("source_goal_revision")))) throw invalid();
        for (String field : new String[]{"execution_authority", "answer_authority", "assistant_answer", "terminal_eligible"}) {
            if (!Boolean.FALSE.equals(compiled.get(field))) throw invalid();
        }
        if (!(compiled.get("arguments") instanceof Map<?, ?> arguments) ||
            !Objects.equals(wire.get("action_kind"), arguments.get("action_kind")) ||
            !HelixJson.stringifyIncludingNulls(arguments).equals(HelixJson.stringifyIncludingNulls(wire.get("arguments")))) throw invalid();
        String kind = String.valueOf(wire.get("action_kind"));
        String schemaKey = "execute_sequence".equals(kind) ? "sequence_schema" :
            "execute_reactive_program".equals(kind) ? "program_schema" : null;
        if (schemaKey == null || arguments.get(schemaKey) == null || !Objects.equals(compiled.get("target_schema"), arguments.get(schemaKey))) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("temporal_compilation_mismatch"); }
}
