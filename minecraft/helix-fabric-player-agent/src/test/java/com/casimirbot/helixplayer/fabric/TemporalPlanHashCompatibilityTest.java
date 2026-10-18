package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.*;
import com.casimirbot.helixsensor.HelixJson;
import com.casimirbot.helixsensor.snapshot.SectionHasher;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Negative compatibility evidence: do not use SectionHasher for ET v1 plans. */
final class TemporalPlanHashCompatibilityTest {
    @Test void existingNativeHasherDoesNotImplementEnvironmentTimeV1Ordering() {
        Map<String, Object> value = Map.of("a", 1, "A", 2, "_x", 3, "z", 4);
        // Node canonicalEnvironmentTimeValue/localeCompare golden, captured on this host.
        String nodeHash = "sha256:fd2472a704a598b26e234bd0be24f100f0eba5191710822df433bf629073483b";
        assertEquals("{\"A\":2,\"_x\":3,\"a\":1,\"z\":4}", HelixJson.stringifyIncludingNulls(value));
        assertNotEquals(nodeHash, SectionHasher.hashIncludingNulls(value));
        Map<String, Object> plan = new java.util.LinkedHashMap<>(value);
        plan.put("plan_hash", nodeHash);
        String canonical = "{\"_x\":3,\"a\":1,\"A\":2,\"z\":4}";
        assertDoesNotThrow(() -> TemporalPlanHashContent.verify(plan, canonical));
        assertThrows(IllegalArgumentException.class, () -> TemporalPlanHashContent.verify(plan, canonical + " "));
        plan.put("a", 9);
        assertThrows(IllegalArgumentException.class, () -> TemporalPlanHashContent.verify(plan, canonical));
    }
}
