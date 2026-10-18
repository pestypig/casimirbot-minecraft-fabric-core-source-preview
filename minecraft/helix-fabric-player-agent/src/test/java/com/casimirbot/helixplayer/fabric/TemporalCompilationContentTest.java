package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.*;
import com.casimirbot.helixsensor.HelixJson;
import com.casimirbot.helixsensor.snapshot.SectionHasher;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class TemporalCompilationContentTest {
    @Test void compiledTransportBindsSourcePlanAndExactArgumentsForBothEngines() {
        for (String kind : new String[]{"execute_sequence", "execute_reactive_program"}) {
            String key = kind.equals("execute_sequence") ? "sequence_schema" : "program_schema";
            Map<String, Object> plan = new LinkedHashMap<>(Map.of("plan_id", "plan", "plan_hash", "source-hash",
                "identity", Map.of("goal_id", "goal", "goal_revision", 1)));
            Map<String, Object> arguments = Map.of("action_kind", kind, key, "schema:test");
            Map<String, Object> compiled = new LinkedHashMap<>(Map.of("schema", "environment.minecraft_temporal_plan_compilation.v1",
                "source_plan_id", "plan", "source_plan_hash", "source-hash", "source_goal_id", "goal", "source_goal_revision", 1,
                "arguments", arguments, "target_schema", "schema:test"));
            for (String field : new String[]{"execution_authority", "answer_authority", "assistant_answer", "terminal_eligible"}) compiled.put(field, false);
            Map<String, Object> wire = new LinkedHashMap<>(Map.of("action_kind", kind, "arguments", arguments,
                "temporal_compilation_canonical_json", HelixJson.stringifyIncludingNulls(compiled),
                "temporal_compilation_hash", SectionHasher.hashIncludingNulls(compiled)));
            assertDoesNotThrow(() -> TemporalCompilationContent.verify(wire, plan));
            wire.put("arguments", Map.of("action_kind", kind, key, "other"));
            assertThrows(IllegalArgumentException.class, () -> TemporalCompilationContent.verify(wire, plan));
            wire.put("arguments", arguments);
            plan.put("plan_id", "other");
            assertThrows(IllegalArgumentException.class, () -> TemporalCompilationContent.verify(wire, plan));
        }
    }
}
