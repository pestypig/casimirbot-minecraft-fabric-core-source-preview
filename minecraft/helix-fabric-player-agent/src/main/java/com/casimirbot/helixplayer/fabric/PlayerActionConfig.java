package com.casimirbot.helixplayer.fabric;

import com.casimirbot.helixsensor.HelixSensorConfig;
import java.time.Instant;
import java.util.Map;

record PlayerActionConfig(
    String endpoint,
    String bearerToken,
    String actionAuthorityId,
    String connectorInstallationId,
    String environmentBindingId,
    String roomId,
    String sourceId,
    String worldId,
    String adapterProfileId,
    String domainAdapter,
    String participantId,
    String subjectBindingId,
    String subjectNativeId,
    int policyVersion,
    String expiresAt
) {
    static final String DOMAIN_ADAPTER = "minecraft.fabric_client.v1";

    static PlayerActionConfig disabled() {
        return new PlayerActionConfig(
            "", "", "", "", "", "", "", "", "", DOMAIN_ADAPTER,
            "", "", "", 0, ""
        );
    }

    static PlayerActionConfig fromMap(Map<String, Object> action) {
        return new PlayerActionConfig(
            text(action, "endpoint"),
            text(action, "bearer_token"),
            text(action, "action_authority_id"),
            text(action, "connector_installation_id"),
            text(action, "environment_binding_id"),
            text(action, "room_id"),
            text(action, "source_id"),
            text(action, "world_id"),
            text(action, "adapter_profile_id"),
            text(action, "domain_adapter"),
            text(action, "participant_id"),
            text(action, "subject_binding_id"),
            text(action, "subject_native_id"),
            integer(action, "policy_version"),
            text(action, "expires_at")
        );
    }

    boolean ready() {
        return transportReady() && future(expiresAt);
    }

    // The server can extend the credential lease while this client is running.
    // Keep the authenticated transport available to learn that new expiry from
    // heartbeat receipts; ordinary actions still require a current lease.
    boolean transportReady() {
        return HelixSensorConfig.secureEndpointAllowed(endpoint) &&
            bearerToken.matches("^helix_env_action_[a-zA-Z0-9_-]{43,96}$") &&
            !actionAuthorityId.isBlank() &&
            !connectorInstallationId.isBlank() &&
            !environmentBindingId.isBlank() &&
            !roomId.isBlank() &&
            !sourceId.isBlank() &&
            !worldId.isBlank() &&
            !adapterProfileId.isBlank() &&
            DOMAIN_ADAPTER.equals(domainAdapter) &&
            !participantId.isBlank() &&
            !subjectBindingId.isBlank() &&
            !subjectNativeId.isBlank() &&
            policyVersion > 0;
    }

    private static boolean future(String value) {
        try {
            return Instant.parse(value).isAfter(Instant.now());
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String text ? text.trim() : "";
    }

    private static int integer(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }
}
