package com.casimirbot.helixplayer.fabric;

import com.casimirbot.helixsensor.HelixSensorConfig;
import java.time.Instant;
import java.util.Map;

record PlayerInteractionConfig(
    String endpoint,
    String bearerToken,
    String interactionCredentialId,
    String actionAuthorityId,
    String environmentBindingId,
    String roomId,
    String participantId,
    String subjectBindingId,
    String subjectNativeId,
    String sourceId,
    String worldId,
    String connectorInstallationId,
    String expiresAt
) {
    static PlayerInteractionConfig disabled() {
        return new PlayerInteractionConfig("", "", "", "", "", "", "", "", "", "", "", "", "");
    }

    static PlayerInteractionConfig fromMap(Map<String, Object> value) {
        return new PlayerInteractionConfig(
            text(value, "endpoint"), text(value, "bearer_token"),
            text(value, "interaction_credential_id"), text(value, "action_authority_id"),
            text(value, "environment_binding_id"), text(value, "room_id"),
            text(value, "participant_id"), text(value, "subject_binding_id"),
            text(value, "subject_native_id"), text(value, "source_id"),
            text(value, "world_id"), text(value, "connector_installation_id"),
            text(value, "expires_at")
        );
    }

    boolean ready() {
        return HelixSensorConfig.secureEndpointAllowed(endpoint) &&
            bearerToken.matches("^helix_env_interact_[a-zA-Z0-9_-]{43,96}$") &&
            !interactionCredentialId.isBlank() && !actionAuthorityId.isBlank() &&
            !environmentBindingId.isBlank() && !roomId.isBlank() &&
            !participantId.isBlank() && !subjectBindingId.isBlank() &&
            !subjectNativeId.isBlank() && !sourceId.isBlank() &&
            !worldId.isBlank() && !connectorInstallationId.isBlank() && future(expiresAt);
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String text ? text.trim() : "";
    }

    private static boolean future(String value) {
        try {
            return Instant.parse(value).isAfter(Instant.now());
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
