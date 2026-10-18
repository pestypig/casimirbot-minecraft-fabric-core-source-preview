package com.casimirbot.helixsensor.fabric;

import com.casimirbot.helixsensor.HelixJson;
import com.casimirbot.helixsensor.HelixSensorConfig;
import java.util.Map;

record FabricCommandConfig(
    boolean enabled,
    String endpoint,
    String bearerToken,
    String commandAuthorityId,
    String environmentBindingId,
    String roomId,
    String sourceId,
    String worldId,
    String adapterProfileId,
    String domainAdapter,
    int policyVersion,
    int pollIntervalTicks,
    int maxPendingPerPoll,
    String expiresAt,
    boolean hostAccessEnabled,
    boolean automaticRetryEnabled
) {
    static FabricCommandConfig fromRootMap(Map<String, Object> root) {
        Object raw = root.get("command");
        Map<String, Object> command = raw instanceof Map<?, ?>
            ? HelixJson.asObject(raw)
            : Map.of();
        return new FabricCommandConfig(
            bool(command, "command_execution_enabled", false),
            HelixSensorConfig.stripTrailingSlash(text(command, "endpoint", "")),
            text(command, "bearer_token", "replace-me"),
            text(command, "command_authority_id", ""),
            text(command, "environment_binding_id", ""),
            text(command, "room_id", ""),
            text(command, "source_id", ""),
            text(command, "world_id", ""),
            text(command, "adapter_profile_id", ""),
            text(command, "domain_adapter", "minecraft.fabric_mod.v1"),
            positive(command, "policy_version", 0),
            positive(command, "poll_interval_ticks", 20),
            Math.min(8, positive(command, "max_pending_per_poll", 4)),
            text(command, "expires_at", ""),
            bool(command, "host_access_enabled", false),
            bool(command, "automatic_retry_enabled", false)
        );
    }

    static FabricCommandConfig disabled() {
        return fromRootMap(Map.of());
    }

    boolean connectorAllowed() {
        return enabled
            && HelixSensorConfig.secureEndpointAllowed(endpoint)
            && bearerToken.startsWith("helix_env_cmd_")
            && !commandAuthorityId.isBlank()
            && !environmentBindingId.isBlank()
            && !roomId.isBlank()
            && !sourceId.isBlank()
            && !worldId.isBlank()
            && !adapterProfileId.isBlank()
            && "minecraft.fabric_mod.v1".equals(domainAdapter)
            && policyVersion > 0
            && !hostAccessEnabled
            && !automaticRetryEnabled;
    }

    private static boolean bool(
        Map<String, Object> config,
        String key,
        boolean fallback
    ) {
        Object value = config.get(key);
        return value instanceof Boolean bool ? bool : fallback;
    }

    private static String text(
        Map<String, Object> config,
        String key,
        String fallback
    ) {
        Object value = config.get(key);
        return value instanceof String text && !text.isBlank()
            ? text.trim()
            : fallback;
    }

    private static int positive(
        Map<String, Object> config,
        String key,
        int fallback
    ) {
        Object value = config.get(key);
        return value instanceof Number number && number.intValue() > 0
            ? number.intValue()
            : fallback;
    }
}
