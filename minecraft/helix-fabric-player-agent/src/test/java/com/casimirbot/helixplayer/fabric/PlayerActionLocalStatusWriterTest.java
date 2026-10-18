package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PlayerActionLocalStatusWriterTest {
    @Test
    void projectionContainsOnlyCredentialFreeReadinessAndWorkflowState() {
        Instant heartbeat = Instant.parse("2026-08-31T05:00:00Z");
        Instant updated = Instant.parse("2026-08-31T05:00:00.250Z");
        Map<String, Object> payload = PlayerActionLocalStatusWriter.payload(
            "environment_action_authority:test",
            "environment_action_epoch:test",
            true,
            heartbeat,
            "environment_action_workflow:test",
            "running",
            true,
            false,
            false,
            false,
            updated
        );

        assertEquals(PlayerActionLocalStatusWriter.SCHEMA, payload.get("schema"));
        assertEquals(ProcessHandle.current().pid(), payload.get("client_process_id"));
        assertEquals(ProcessHandle.current().info().startInstant()
            .map(Instant::toString).orElse(null), payload.get("client_process_started_at"));
        assertEquals(heartbeat.toString(), payload.get("last_heartbeat_accepted_at"));
        assertEquals("environment_action_workflow:test", payload.get("active_workflow_id"));
        assertEquals(true, payload.get("ready_for_actions"));
        assertEquals(false, payload.get("critical_delivery_pending"));
        assertEquals(true, payload.get("controls_asserted"));
        assertEquals(false, payload.get("controls_released"));
        assertEquals(false, payload.get("credential_included"));
        assertEquals(false, payload.get("answer_authority"));
        assertEquals(false, payload.get("terminal_eligible"));
        assertFalse(payload.containsKey("credential"));
        assertFalse(payload.containsKey("pairing_code"));
        assertFalse(payload.containsKey("native_player_id"));
    }

    @Test
    void idleProjectionReleasesControlsAndMayHaveNoAcceptedHeartbeat() {
        Map<String, Object> payload = PlayerActionLocalStatusWriter.payload(
            "environment_action_authority:test",
            "environment_action_epoch:test",
            false,
            null,
            null,
            "succeeded",
            false,
            false,
            false,
            true,
            Instant.parse("2026-08-31T05:00:01Z")
        );

        assertNull(payload.get("last_heartbeat_accepted_at"));
        assertNull(payload.get("active_workflow_id"));
        assertEquals(true, payload.get("critical_delivery_pending"));
        assertTrue((Boolean) payload.get("controls_released"));
    }
}
