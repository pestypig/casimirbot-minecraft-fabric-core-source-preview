package com.casimirbot.helixsensor.command;

import com.casimirbot.helixsensor.HelixSensorConfig;
import com.casimirbot.helixsensor.HelixSensorRuntimeStatus;
import java.util.ArrayList;
import java.util.List;

public final class CommandStatusFormatter {
    private CommandStatusFormatter() {}

    public static List<String> status(HelixSensorConfig config, HelixSensorRuntimeStatus runtime) {
        HelixSensorRuntimeStatus effectiveRuntime =
            runtime == null ? new HelixSensorRuntimeStatus(config) : runtime;
        List<String> lines = new ArrayList<>();
        lines.add("HelixPaperSensor status");
        lines.add("enabled: " + effectiveRuntime.enabled);
        lines.add("endpoint: " + effectiveRuntime.endpoint);
        lines.add("source_id: " + effectiveRuntime.sourceId);
        lines.add("manifest: " + state(effectiveRuntime.lastManifestSuccessAt, effectiveRuntime.manifestFailureCount));
        lines.add("heartbeat: " + state(effectiveRuntime.lastHeartbeatSuccessAt, effectiveRuntime.heartbeatFailureCount));
        lines.add("snapshot: last sent " + effectiveRuntime.ago(effectiveRuntime.lastSnapshotSuccessAt));
        lines.add("upload queue: " + effectiveRuntime.uploadQueueState);
        lines.add("skipped snapshots: " + effectiveRuntime.skippedSnapshotCount);
        lines.add("pending probes: " + effectiveRuntime.pendingProbeCount);
        lines.add("execution_enabled: " + config.executionEnabled());
        lines.add("raw_nbt_included: false");
        if (effectiveRuntime.lastError != null) lines.add("last_error: " + effectiveRuntime.lastError);
        return lines;
    }

    public static List<String> probes(HelixSensorRuntimeStatus runtime) {
        List<String> lines = new ArrayList<>();
        lines.add("HelixPaperSensor probes");
        if (runtime == null) {
            lines.add("sensor runtime not started");
            return lines;
        }
        List<String> summaries = runtime.recentProbeSummaries();
        if (summaries.isEmpty()) lines.add("no recent probes");
        else lines.addAll(summaries);
        return lines;
    }

    public static List<String> debugPayload(HelixSensorRuntimeStatus runtime) {
        if (runtime == null) {
            return List.of(
                "HelixPaperSensor debug payload",
                "No snapshot payload built yet.",
                "payload_bytes 0",
                "avg_payload_bytes 0",
                "raw_nbt false"
            );
        }
        return List.of(
            "HelixPaperSensor debug payload",
            runtime.latestPayloadSummary(),
            "payload_bytes " + runtime.lastPayloadBytes,
            "avg_payload_bytes " + runtime.avgPayloadBytes,
            "raw_nbt false"
        );
    }

    private static String state(Object successAt, int failures) {
        if (successAt != null) return failures > 0 ? "sent / degraded" : "sent";
        return failures > 0 ? "failed" : "pending";
    }
}
