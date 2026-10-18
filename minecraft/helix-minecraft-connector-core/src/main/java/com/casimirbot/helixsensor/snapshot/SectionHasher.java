package com.casimirbot.helixsensor.snapshot;

import com.casimirbot.helixsensor.HelixJson;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class SectionHasher {
    private SectionHasher() {}

    public static String hash(Object value) {
        return hashJson(HelixJson.stringify(value));
    }

    public static String hashIncludingNulls(Object value) {
        return hashJson(HelixJson.stringifyIncludingNulls(value));
    }

    private static String hashJson(String json) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder("sha256:");
            for (byte b : bytes) builder.append(String.format("%02x", b));
            return builder.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
