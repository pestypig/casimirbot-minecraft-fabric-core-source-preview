package com.casimirbot.helixplayer.fabric;

import com.casimirbot.helixsensor.HelixJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Writes a credential-free, local-only projection for fixture supervision.
 * This is observation, never action admission: the remote authority and
 * connector heartbeat remain the only sources that can lease gameplay work.
 */
final class PlayerActionLocalStatusWriter {
    static final String SCHEMA = "helix.minecraft.player_local_runtime_status.v1";
    private static final int REPLACE_ATTEMPTS = 3;
    private static final long REPLACE_RETRY_MILLIS = 5L;
    private static final long CLIENT_PROCESS_ID = ProcessHandle.current().pid();
    private static final String CLIENT_PROCESS_STARTED_AT = ProcessHandle.current()
        .info().startInstant().map(Instant::toString).orElse(null);

    private final Path statusPath;
    private final Logger logger;
    private String lastPayload = "";

    PlayerActionLocalStatusWriter(Path statusPath, Logger logger) {
        this.statusPath = statusPath;
        this.logger = logger;
    }

    void write(
        String actionAuthorityId,
        String producerEpochRef,
        boolean readyForActions,
        Instant lastHeartbeatAcceptedAt,
        String activeWorkflowId,
        String workflowState,
        boolean controlsAsserted,
        boolean emergencyStopLatched,
        boolean manualInputDetected,
        boolean criticalDeliveryPending,
        Instant updatedAt
    ) {
        Map<String, Object> payload = payload(
            actionAuthorityId,
            producerEpochRef,
            readyForActions,
            lastHeartbeatAcceptedAt,
            activeWorkflowId,
            workflowState,
            controlsAsserted,
            emergencyStopLatched,
            manualInputDetected,
            criticalDeliveryPending,
            updatedAt
        );
        String json = HelixJson.stringify(payload) + System.lineSeparator();
        if (json.equals(lastPayload)) return;
        try {
            Files.createDirectories(statusPath.getParent());
            Path temporary = statusPath.resolveSibling(statusPath.getFileName() + ".tmp");
            Files.writeString(
                temporary,
                json,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
            replaceProjection(temporary);
            lastPayload = json;
        } catch (IOException error) {
            logger.warn(
                "Helix could not update the credential-free local player runtime status projection."
            );
        }
    }

    private void replaceProjection(Path temporary) throws IOException {
        IOException lastError = null;
        for (int attempt = 1; attempt <= REPLACE_ATTEMPTS; attempt++) {
            try {
                try {
                    Files.move(
                        temporary,
                        statusPath,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                    );
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(
                        temporary,
                        statusPath,
                        StandardCopyOption.REPLACE_EXISTING
                    );
                }
                return;
            } catch (IOException error) {
                lastError = error;
                if (attempt == REPLACE_ATTEMPTS) break;
                try {
                    Thread.sleep(REPLACE_RETRY_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(
                        "Interrupted while replacing the local player status projection.",
                        interrupted
                    );
                }
            }
        }
        throw lastError;
    }

    static Map<String, Object> payload(
        String actionAuthorityId,
        String producerEpochRef,
        boolean readyForActions,
        Instant lastHeartbeatAcceptedAt,
        String activeWorkflowId,
        String workflowState,
        boolean controlsAsserted,
        boolean emergencyStopLatched,
        boolean manualInputDetected,
        boolean criticalDeliveryPending,
        Instant updatedAt
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", SCHEMA);
        payload.put("client_process_id", CLIENT_PROCESS_ID);
        payload.put("client_process_started_at", CLIENT_PROCESS_STARTED_AT);
        payload.put("action_authority_id", actionAuthorityId);
        payload.put("producer_epoch_ref", producerEpochRef);
        payload.put("ready_for_actions", readyForActions);
        payload.put("critical_delivery_pending", criticalDeliveryPending);
        payload.put(
            "last_heartbeat_accepted_at",
            lastHeartbeatAcceptedAt == null ? null : lastHeartbeatAcceptedAt.toString()
        );
        payload.put("active_workflow_id", activeWorkflowId);
        payload.put("workflow_state", workflowState);
        payload.put("controls_asserted", controlsAsserted);
        payload.put("emergency_stop_latched", emergencyStopLatched);
        payload.put("manual_input_detected", manualInputDetected);
        payload.put("controls_released", !controlsAsserted);
        payload.put("updated_at", updatedAt.toString());
        payload.put("credential_included", false);
        payload.put(
            "content_role",
            "minecraft_player_local_runtime_status_not_assistant_answer"
        );
        payload.put("answer_authority", false);
        payload.put("assistant_answer", false);
        payload.put("terminal_eligible", false);
        payload.put("raw_content_included", false);
        return payload;
    }
}
