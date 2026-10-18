package com.casimirbot.helixsensor.probe;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ProbeSubmissionEnvelope {
    private static final String LEASE_SCHEMA =
        "helix.environment_connector.probe_lease.v1";
    private static final String SUBMISSION_SCHEMA =
        "helix.environment_connector.probe_submission.v1";

    private ProbeSubmissionEnvelope() {}

    public static Map<String, Object> forProbe(
        Map<String, Object> probe,
        Map<String, Object> result
    ) {
        Object transportValue = probe.get("connector_transport");
        if (!(transportValue instanceof Map<?, ?> transport)) return result;
        if (!LEASE_SCHEMA.equals(text(transport.get("schema")))) return result;
        String attemptId = text(transport.get("probe_attempt_id"));
        String leaseToken = text(transport.get("lease_token"));
        if (attemptId.isBlank() || leaseToken.length() < 32) return result;

        Map<String, Object> submission = new LinkedHashMap<>();
        submission.put("schema", SUBMISSION_SCHEMA);
        submission.put("probe_attempt_id", attemptId);
        submission.put("lease_token", leaseToken);
        submission.put("result", result);
        submission.put("submitted_at", Instant.now().toString());
        return submission;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
