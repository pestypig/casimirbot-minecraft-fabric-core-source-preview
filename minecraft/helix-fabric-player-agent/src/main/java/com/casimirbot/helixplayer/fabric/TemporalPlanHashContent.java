package com.casimirbot.helixplayer.fabric;

import com.casimirbot.helixsensor.HelixJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Integrity only, not admission: hash server canonical bytes, then compare the
 * decoded semantic object with the delivered source plan using one local codec. */
final class TemporalPlanHashContent {
    static void verify(Map<String, Object> plan, String canonicalJson) {
        Map<?, ?> parsed = decode(plan.get("plan_hash"), canonicalJson);
        Map<String, Object> content = new LinkedHashMap<>(plan);
        content.remove("plan_hash");
        if (!HelixJson.stringifyIncludingNulls(parsed).equals(HelixJson.stringifyIncludingNulls(content))) throw invalid();
    }

    static Map<?, ?> decode(Object expectedHash, String canonicalJson) {
        if (canonicalJson == null || canonicalJson.length() > 262144) throw invalid();
        byte[] bytes = canonicalJson.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 262144) throw invalid();
        String hash;
        try {
            hash = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
        if (!hash.equals(expectedHash)) throw invalid();
        Object parsed = HelixJson.parse(canonicalJson);
        if (!(parsed instanceof Map<?, ?> map)) throw invalid();
        return map;
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("temporal_plan_hash_mismatch"); }
}
