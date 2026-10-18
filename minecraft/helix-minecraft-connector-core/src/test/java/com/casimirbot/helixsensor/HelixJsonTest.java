package com.casimirbot.helixsensor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class HelixJsonTest {
    @Test
    void strictContractEncodingCanPreserveExplicitNullFields() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("nullable", null);
        value.put("present", true);

        assertEquals("{\"present\":true}", HelixJson.stringify(value));
        assertEquals(
            "{\"nullable\":null,\"present\":true}",
            HelixJson.stringifyIncludingNulls(value)
        );
    }

    @Test
    void floatingNumbersMatchNodeCanonicalJsonSpelling() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("whole", 0.0D);
        value.put("position", 30_000_000.0D);
        value.put("fraction", 0.125D);
        value.put("small", 1.0E-7D);
        value.put("negative_zero", -0.0D);

        assertEquals(
            "{\"fraction\":0.125,\"negative_zero\":0,\"position\":30000000,\"small\":1e-7,\"whole\":0}",
            HelixJson.stringifyIncludingNulls(value)
        );
    }
}
