package com.casimirbot.helixsensor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class HelixSensorRuntimeStatus {
    public boolean enabled;
    public String endpoint;
    public String sourceId;

    public Instant lastManifestAttemptAt;
    public Instant lastManifestSuccessAt;
    public Instant lastHeartbeatSuccessAt;
    public Instant lastSnapshotSuccessAt;
    public Instant lastProbePollSuccessAt;
    public Instant lastProbeResultSuccessAt;

    public int manifestFailureCount;
    public int heartbeatFailureCount;
    public int snapshotFailureCount;
    public int probeFailureCount;

    public int skippedSnapshotCount;
    public int oversizedPayloadCount;
    public int forbiddenProbeBlockedCount;
    public int authFailureCount;
    public int contractFailureCount;

    public long lastPayloadBytes;
    public long avgPayloadBytes;
    public long lastSnapshotBuildMillis;
    public long lastHttpMillis;

    public String backoffState = "idle";
    public String lastError;
    public String uploadQueueState = "idle";
    public int pendingProbeCount;

    private long payloadByteTotal;
    private long payloadSampleCount;
    private String latestPayloadSummary = "No snapshot payload built yet.";
    private final Deque<String> recentProbeSummaries = new ArrayDeque<>();

    public HelixSensorRuntimeStatus(HelixSensorConfig config) {
        this.enabled = config.enabled();
        this.endpoint = config.endpoint();
        this.sourceId = config.sourceId();
    }

    public synchronized void recordManifestAttempt() {
        lastManifestAttemptAt = Instant.now();
    }

    public synchronized void recordHttpResult(String path, int status, long elapsedMillis) {
        recordHttpResult(
            path,
            status,
            elapsedMillis,
            status >= 200 && status < 300
        );
    }

    public synchronized void recordHttpResult(
        String path,
        int status,
        long elapsedMillis,
        boolean applicationSuccess
    ) {
        lastHttpMillis = elapsedMillis;
        if (status >= 200 && status < 300 && applicationSuccess) {
            backoffState = "idle";
            lastError = null;
            if (path.contains("/manifest")) lastManifestSuccessAt = Instant.now();
            if (path.contains("/heartbeat")) lastHeartbeatSuccessAt = Instant.now();
            if (path.contains("/world-event")) lastSnapshotSuccessAt = Instant.now();
            if (path.contains("/probes/pending")) lastProbePollSuccessAt = Instant.now();
            if (path.contains("/probes/result")) lastProbeResultSuccessAt = Instant.now();
            return;
        }
        if (path.contains("/manifest")) manifestFailureCount++;
        if (path.contains("/heartbeat")) heartbeatFailureCount++;
        if (path.contains("/world-event/batch")) snapshotFailureCount++;
        if (path.contains("/probes")) probeFailureCount++;
        if (status == 401 || status == 403) {
            authFailureCount++;
            lastError = "auth_error:" + status;
        } else if (status == 413) {
            oversizedPayloadCount++;
            lastError = "payload_too_large";
        } else {
            lastError = "http_" + status;
        }
    }

    public synchronized void recordIngressReceipt(
        String errorCode,
        String message,
        String requestId,
        long sequence
    ) {
        String code = errorCode == null || errorCode.isBlank()
            ? "ingress_rejected"
            : errorCode;
        String detail = message == null || message.isBlank()
            ? code
            : code + ": " + message;
        lastError = detail + " [request=" + requestId + ", sequence=" + sequence + "]";
    }

    public synchronized void recordBackoff(String state, String error) {
        backoffState = state;
        lastError = error;
    }

    public synchronized void recordSnapshotSkipped() {
        skippedSnapshotCount++;
    }

    public synchronized void recordSnapshotContractFailure(String issue) {
        contractFailureCount++;
        lastError = issue;
    }

    public synchronized void recordPayload(long bytes, long buildMillis, String summary) {
        lastPayloadBytes = bytes;
        lastSnapshotBuildMillis = buildMillis;
        payloadByteTotal += bytes;
        payloadSampleCount++;
        avgPayloadBytes = payloadSampleCount == 0 ? 0 : payloadByteTotal / payloadSampleCount;
        latestPayloadSummary = summary;
    }

    public synchronized void setUploadInFlight(boolean inFlight) {
        uploadQueueState = inFlight ? "in-flight" : backoffState.equals("idle") ? "idle" : "backoff";
    }

    public synchronized void setPendingProbeCount(int count) {
        pendingProbeCount = Math.max(0, count);
    }

    public synchronized void recordProbeSummary(String probeType, String status) {
        recentProbeSummaries.addLast(probeType + ": " + status);
        while (recentProbeSummaries.size() > 8) recentProbeSummaries.removeFirst();
    }

    public synchronized void recordForbiddenProbeBlocked() {
        forbiddenProbeBlockedCount++;
    }

    public synchronized String latestPayloadSummary() {
        return latestPayloadSummary;
    }

    public synchronized List<String> recentProbeSummaries() {
        return new ArrayList<>(recentProbeSummaries);
    }

    public synchronized String ago(Instant value) {
        if (value == null) return "never";
        long seconds = Math.max(0, Duration.between(value, Instant.now()).toSeconds());
        return seconds + "s ago";
    }
}
