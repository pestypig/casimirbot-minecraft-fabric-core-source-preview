package com.casimirbot.helixsensor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.casimirbot.helixsensor.HelixSensorConfig;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class FabricSensorConfigLoaderTest {
    @Test
    void createsDisabledReadOnlyFabricDefaults() {
        HelixSensorConfig config = FabricSensorConfigLoader.disabledDefaults();

        assertFalse(config.enabled());
        assertFalse(config.executionEnabled());
        assertTrue(config.readOnlyProbesEnabled());
        assertEquals(
            HelixSensorConfig.DEFAULT_HEARTBEAT_INTERVAL_TICKS,
            config.heartbeatIntervalTicks()
        );
        assertTrue(config.domainAdapter().equals("minecraft.fabric_mod.v1"));
        assertFalse(config.sendOnlyChangedSections());
        assertFalse(config.sensorUploadsAllowed());
        assertEquals(
            false,
            FabricSensorConfigLoader.disabledCommandTemplate().get(
                "command_execution_enabled"
            )
        );
        assertFalse(
            FabricSensorConfigLoader.disabledCommandTemplate().containsKey(
                "bearer_token"
            )
        );
    }

    @Test
    void sourceOnlyPairingCannotCarryAnOlderCommandCredential() {
        Map<String, Object> sourceOnly =
            FabricSensorConfigLoader.commandConfigForPairedSource(Map.of());
        assertEquals(false, sourceOnly.get("command_execution_enabled"));
        assertFalse(sourceOnly.containsKey("bearer_token"));

        Map<String, Object> pairedCommand = new LinkedHashMap<>(
            Map.of(
                "command_execution_enabled", true,
                "bearer_token", "test-only-credential"
            )
        );
        Map<String, Object> retained =
            FabricSensorConfigLoader.commandConfigForPairedSource(pairedCommand);
        assertEquals("test-only-credential", retained.get("bearer_token"));
        assertNotSame(pairedCommand, retained);
    }

    @Test
    void localRepairUsesTheConfiguredPairingEndpoint() {
        Map<String, Object> config = new LinkedHashMap<>(
            FabricSensorConfigLoader.defaultTemplate()
        );
        config.put(
            "pairing_endpoint",
            "http://127.0.0.1:64969/api/environment-connectors/v1/pairing/redeem"
        );

        assertEquals(
            "http://127.0.0.1:64969/api/environment-connectors/v1/pairing/redeem",
            FabricSensorConfigLoader.pairingEndpointFromMap(config)
        );
    }

    @Test
    void admitsOnlyBoundLoopbackOrHttpsConfiguration() {
        Map<String, Object> loopback = new LinkedHashMap<>(
            FabricSensorConfigLoader.defaultTemplate()
        );
        loopback.put("enabled", true);
        loopback.put(
            "endpoint",
            "http://127.0.0.1:1522/api/room-ingress/v1/bindings/binding:test"
        );
        loopback.put("bearer_token", "room-source-test-credential");
        assertTrue(FabricSensorConfigLoader.fromMap(loopback).sensorUploadsAllowed());

        loopback.put("endpoint", "http://example.com/ingress");
        assertFalse(FabricSensorConfigLoader.fromMap(loopback).sensorUploadsAllowed());

        loopback.put("endpoint", "https://example.com/ingress");
        loopback.put("execution_enabled", true);
        assertFalse(FabricSensorConfigLoader.fromMap(loopback).sensorUploadsAllowed());
    }
}
