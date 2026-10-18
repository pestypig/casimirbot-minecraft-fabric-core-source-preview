package com.casimirbot.helixsensor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.casimirbot.helixsensor.HelixJson;
import com.casimirbot.helixsensor.HelixSensorConfig;
import com.casimirbot.helixsensor.manifest.EnvironmentSourceManifestFactory;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class FabricManifestContractTest {
    @Test
    void advertisesExactlyTheImplementedReadOnlyProbeSet() {
        HelixSensorConfig config = FabricSensorConfigLoader.disabledDefaults();
        Map<String, Object> manifest = EnvironmentSourceManifestFactory.build(
            config,
            FabricConnectorRuntime.ADAPTER_VERSION,
            Instant.now().toString(),
            List.of("spatial_region", "registry_fact", "recipe_fact")
        );

        assertEquals(
            List.of(
                "actor_status",
                "nearby_entities",
                "reachability",
                "line_of_sight",
                "crop_state",
                "hazard_check",
                "inventory_check",
                "local_map_summary",
                "perception_snapshot",
                "spatial_region",
                "registry_fact",
                "recipe_fact"
            ),
            manifest.get("supported_probe_types")
        );
        assertEquals(12, ((List<?>) manifest.get("supported_probe_types")).size());
        Map<String, Object> execution = HelixJson.asObject(
            manifest.get("execution_policy")
        );
        assertFalse((Boolean) execution.get("may_execute_live_actions"));
        assertTrue((Boolean) execution.get("may_perform_read_only_probes"));
    }

    @Test
    void loadsACommonServerEntrypointAndAnOptionalClientPerceptionEntrypoint()
        throws Exception {
        try (
            InputStream input = getClass()
                .getClassLoader()
                .getResourceAsStream("fabric.mod.json")
        ) {
            assertTrue(input != null);
            Map<String, Object> metadata = HelixJson.asObject(
                HelixJson.parse(
                    new String(input.readAllBytes(), StandardCharsets.UTF_8)
                )
            );
            assertEquals("*", metadata.get("environment"));
            Map<String, Object> entrypoints = HelixJson.asObject(
                metadata.get("entrypoints")
            );
            assertTrue(entrypoints.containsKey("main"));
            assertTrue(entrypoints.containsKey("client"));
        }
    }
}
