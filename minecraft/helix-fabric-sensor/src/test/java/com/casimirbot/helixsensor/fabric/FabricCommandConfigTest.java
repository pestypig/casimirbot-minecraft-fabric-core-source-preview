package com.casimirbot.helixsensor.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class FabricCommandConfigTest {
    private static Map<String, Object> validCommand() {
        return Map.ofEntries(
            Map.entry("command_execution_enabled", true),
            Map.entry("endpoint", "http://localhost:1522/api/environment-command/v1/authorities/command_authority%3Atest"),
            Map.entry("bearer_token", "helix_env_cmd_test"),
            Map.entry("command_authority_id", "command_authority:test"),
            Map.entry("environment_binding_id", "environment_binding:test"),
            Map.entry("room_id", "shared_realtime_room:test"),
            Map.entry("source_id", "source:room-ingress:test"),
            Map.entry("world_id", "minecraft:local:test"),
            Map.entry("adapter_profile_id", "game.minecraft.readonly.v1"),
            Map.entry("domain_adapter", "minecraft.fabric_mod.v1"),
            Map.entry("policy_version", 1),
            Map.entry("host_access_enabled", false),
            Map.entry("automatic_retry_enabled", false),
            Map.entry("expires_at", "2026-08-03T12:00:00.000Z")
        );
    }

    @Test
    void acceptsOnlySeparateExactNonHostCommandConfiguration() {
        FabricCommandConfig config = FabricCommandConfig.fromRootMap(
            Map.of("command", validCommand())
        );
        assertTrue(config.connectorAllowed());

        Map<String, Object> hostDefault = new java.util.LinkedHashMap<>(
            validCommand()
        );
        hostDefault.remove("host_access_enabled");
        assertTrue(FabricCommandConfig.fromRootMap(
            Map.of("command", hostDefault)
        ).connectorAllowed());
    }

    @Test
    void rejectsHostAccessRetryAndMissingPolicyVersion() {
        Map<String, Object> host = new java.util.LinkedHashMap<>(validCommand());
        host.put("host_access_enabled", true);
        assertFalse(FabricCommandConfig.fromRootMap(
            Map.of("command", host)
        ).connectorAllowed());

        Map<String, Object> retry = new java.util.LinkedHashMap<>(validCommand());
        retry.put("automatic_retry_enabled", true);
        assertFalse(FabricCommandConfig.fromRootMap(
            Map.of("command", retry)
        ).connectorAllowed());

        Map<String, Object> noPolicy = new java.util.LinkedHashMap<>(validCommand());
        noPolicy.remove("policy_version");
        assertFalse(FabricCommandConfig.fromRootMap(
            Map.of("command", noPolicy)
        ).connectorAllowed());
    }
}
