package com.casimirbot.helixsensor.fabric;

import com.casimirbot.helixsensor.HelixJson;
import com.casimirbot.helixsensor.HelixSensorConfig;
import com.casimirbot.helixsensor.scope.SensorScope;
import com.casimirbot.helixsensor.scope.SensorScopePolicy;
import com.casimirbot.helixsensor.pairing.ConnectorPairingClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.logging.Logger;
import net.fabricmc.loader.api.FabricLoader;

public final class FabricSensorConfigLoader {
    public static final String CONFIG_FILE_NAME = "helix-fabric-sensor.json";

    private FabricSensorConfigLoader() {}

    public static HelixSensorConfig loadOrCreate(Logger logger) {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
        if (!Files.exists(path)) {
            writeDisabledTemplate(path, logger);
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return fromMap(HelixJson.asObject(HelixJson.parse(text)));
        } catch (RuntimeException | IOException error) {
            logger.warning(
                "Helix Fabric Sensor configuration is invalid; the connector will remain disabled. " +
                "No configuration values were logged."
            );
            return disabledDefaults();
        }
    }

    public static FabricCommandConfig loadCommandConfig(Logger logger) {
        Path path = FabricLoader.getInstance().getConfigDir().resolve(CONFIG_FILE_NAME);
        if (!Files.exists(path)) return FabricCommandConfig.disabled();
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            return FabricCommandConfig.fromRootMap(
                HelixJson.asObject(HelixJson.parse(text))
            );
        } catch (RuntimeException | IOException error) {
            logger.warning(
                "Helix Fabric command configuration is invalid; command execution remains disabled."
            );
            return FabricCommandConfig.disabled();
        }
    }

    public static String loadPairingEndpoint(Logger logger) {
        return pairingEndpointFromMap(readRoot(logger));
    }

    static String pairingEndpointFromMap(Map<String, Object> root) {
        String sourceEndpoint = text(
            root,
            "endpoint",
            HelixSensorConfig.INACTIVE_ENDPOINT
        );
        return text(
            root,
            "pairing_endpoint",
            ConnectorPairingClient.pairingEndpointFromSourceEndpoint(
                sourceEndpoint
            )
        );
    }

    public static String loadOrCreatePairingNonce(
        String pairingCode,
        Logger logger
    ) {
        Map<String, Object> root = readRoot(logger);
        String codeHash = pairingCodeHash(pairingCode);
        Map<String, Object> pending = object(root.get("pairing_pending"));
        String existingHash = text(pending, "code_hash", "");
        String existingNonce = text(pending, "redemption_nonce", "");
        if (
            codeHash.equals(existingHash) &&
            existingNonce.matches("^[a-zA-Z0-9_-]{32,160}$")
        ) {
            return existingNonce;
        }
        String nonce = ConnectorPairingClient.newRedemptionNonce();
        root.put(
            "pairing_pending",
            Map.of("code_hash", codeHash, "redemption_nonce", nonce)
        );
        writeRoot(root, logger);
        return nonce;
    }

    public static void savePairedSource(
        ConnectorPairingClient.PairedSourceConfig paired,
        Logger logger
    ) {
        Map<String, Object> root = readRoot(logger);
        if (paired.commandOnly()) {
            if (paired.commandConfig().isEmpty()) {
                throw new IllegalArgumentException(
                    "A command-only pairing did not include command configuration."
                );
            }
            root.put("command", new LinkedHashMap<>(paired.commandConfig()));
            root.remove("pairing_pending");
            writeRoot(root, logger);
            return;
        }
        root.put("enabled", true);
        root.put("endpoint", paired.endpoint());
        root.put("pairing_endpoint", paired.pairingEndpoint());
        root.put("bearer_token", paired.bearerToken());
        root.put("source_id", paired.sourceId());
        root.put("room_id", paired.roomId());
        root.put("world_id", paired.worldId());
        root.put("domain_adapter", paired.domainAdapter());
        root.put("execution_enabled", false);
        // A source-only re-pair changes the exact source/world identity. Never
        // carry a command credential from the previous binding across that
        // boundary; it has been revoked server-side and must be paired again.
        root.put(
            "command",
            commandConfigForPairedSource(paired.commandConfig())
        );
        root.remove("pairing_pending");
        writeRoot(root, logger);
    }

    public static void clearPairedSource(Logger logger) {
        Map<String, Object> root = readRoot(logger);
        root.put("enabled", false);
        root.put("endpoint", HelixSensorConfig.INACTIVE_ENDPOINT);
        root.put("bearer_token", "replace-me");
        root.put(
            "source_id",
            "source:room-ingress:replace-with-generated-id"
        );
        root.put("room_id", "room:minecraft");
        root.put("world_id", "minecraft:fabric-integrated-server");
        root.put("domain_adapter", "minecraft.fabric_mod.v1");
        root.put("execution_enabled", false);
        root.put("command", disabledCommandTemplate());
        root.remove("pairing_pending");
        writeRoot(root, logger);
    }

    static HelixSensorConfig fromMap(Map<String, Object> config) {
        SensorScopePolicy scopePolicy = new SensorScopePolicy(
            SensorScope.from(text(config, "sensor_scope_default", "player_observable")),
            bool(config, "allow_privileged_container_scan", false),
            bool(config, "allow_privileged_entity_scan", false),
            true
        );
        return new HelixSensorConfig(
            bool(config, "enabled", false),
            HelixSensorConfig.stripTrailingSlash(
                text(config, "endpoint", HelixSensorConfig.INACTIVE_ENDPOINT)
            ),
            text(config, "bearer_token", "replace-me"),
            text(config, "source_id", "source:room-ingress:replace-with-generated-id"),
            text(config, "room_id", "room:minecraft"),
            text(config, "world_id", "minecraft:fabric-integrated-server"),
            text(config, "domain_adapter", "minecraft.fabric_mod.v1"),
            text(config, "source_label", "Minecraft Fabric Sensor"),
            positive(config, "snapshot_interval_ticks", 100),
            positive(
                config,
                "heartbeat_interval_ticks",
                HelixSensorConfig.DEFAULT_HEARTBEAT_INTERVAL_TICKS
            ),
            positive(config, "probe_poll_interval_ticks", 40),
            positive(config, "burst_interval_ticks", 20),
            positive(config, "burst_duration_ticks", 120),
            bool(config, "send_only_changed_sections", false),
            bool(config, "include_section_hashes", true),
            positive(config, "max_payload_bytes", 48_000),
            1,
            scopePolicy,
            bool(config, "read_only_probes_enabled", true),
            positive(config, "max_pending_probes_per_poll", 8),
            bool(config, "execution_enabled", false),
            false,
            new HelixSensorConfig.SeedMapOptions(
                64,
                "village",
                false,
                1,
                true,
                true
            ),
            new HelixSensorConfig.SnapshotOptions(
                true,
                true,
                true,
                true,
                false,
                false,
                true,
                true,
                false,
                positive(config, "nearby_entity_radius", 16),
                positive(config, "crop_radius", 16),
                positive(config, "local_map_radius", 8),
                0,
                positive(config, "max_entities", 128),
                positive(config, "max_crops", 48),
                positive(config, "max_local_blocks", 128),
                0,
                positive(config, "max_inventory_stacks", 64)
            ),
            new HelixSensorConfig.ProbeOptions(
                positive(config, "max_route_radius", 64),
                positive(config, "max_probe_duration_ms", 250),
                positive(config, "probe_ttl_ms", 10_000)
            )
        );
    }

    public static HelixSensorConfig disabledDefaults() {
        return fromMap(defaultTemplate());
    }

    static Map<String, Object> defaultTemplate() {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("enabled", false);
        config.put("endpoint", HelixSensorConfig.INACTIVE_ENDPOINT);
        config.put(
            "pairing_endpoint",
            ConnectorPairingClient.LOCAL_PAIRING_ENDPOINT
        );
        config.put("bearer_token", "replace-me");
        config.put("source_id", "source:room-ingress:replace-with-generated-id");
        config.put("room_id", "room:minecraft");
        config.put("world_id", "minecraft:fabric-integrated-server");
        config.put("domain_adapter", "minecraft.fabric_mod.v1");
        config.put("source_label", "Minecraft Fabric Sensor");
        config.put("execution_enabled", false);
        config.put("read_only_probes_enabled", true);
        config.put("snapshot_interval_ticks", 100);
        config.put(
            "heartbeat_interval_ticks",
            HelixSensorConfig.DEFAULT_HEARTBEAT_INTERVAL_TICKS
        );
        config.put("probe_poll_interval_ticks", 40);
        config.put("max_pending_probes_per_poll", 8);
        config.put("send_only_changed_sections", false);
        config.put("include_section_hashes", true);
        config.put("sensor_scope_default", "player_observable");
        config.put("allow_privileged_container_scan", false);
        config.put("allow_privileged_entity_scan", false);
        config.put("command", disabledCommandTemplate());
        return config;
    }

    static Map<String, Object> disabledCommandTemplate() {
        return new LinkedHashMap<>(
            Map.of(
                "command_execution_enabled", false,
                "host_access_enabled", false,
                "automatic_retry_enabled", false
            )
        );
    }

    static Map<String, Object> commandConfigForPairedSource(
        Map<String, Object> pairedCommandConfig
    ) {
        return pairedCommandConfig.isEmpty()
            ? disabledCommandTemplate()
            : new LinkedHashMap<>(pairedCommandConfig);
    }

    private static void writeDisabledTemplate(Path path, Logger logger) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(
                path,
                HelixJson.stringify(defaultTemplate()) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            );
            logger.info(
                "Created a disabled Helix Fabric Sensor configuration. " +
                "Bind a room source before enabling it."
            );
        } catch (IOException error) {
            logger.warning(
                "Could not create the disabled Helix Fabric Sensor configuration."
            );
        }
    }

    private static Map<String, Object> readRoot(Logger logger) {
        Path path = configPath();
        if (!Files.exists(path)) writeDisabledTemplate(path, logger);
        try {
            return new LinkedHashMap<>(
                HelixJson.asObject(
                    HelixJson.parse(
                        Files.readString(path, StandardCharsets.UTF_8)
                    )
                )
            );
        } catch (RuntimeException | IOException error) {
            logger.warning(
                "Helix Fabric Sensor configuration could not be read; no configuration values were logged."
            );
            return defaultTemplate();
        }
    }

    private static void writeRoot(
        Map<String, Object> root,
        Logger logger
    ) {
        Path path = configPath();
        Path temporary = null;
        try {
            Files.createDirectories(path.getParent());
            temporary = Files.createTempFile(
                path.getParent(),
                ".helix-fabric-sensor-",
                ".json.tmp"
            );
            Files.writeString(
                temporary,
                HelixJson.stringify(root) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
            );
            try {
                Files.setPosixFilePermissions(
                    temporary,
                    PosixFilePermissions.fromString("rw-------")
                );
            } catch (UnsupportedOperationException ignored) {
                // Windows ACL inheritance remains in force.
            }
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException error) {
            logger.warning(
                "Helix Fabric Sensor configuration could not be updated; no configuration values were logged."
            );
            throw new IllegalStateException(
                "Could not persist the Helix connector configuration.",
                error
            );
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best-effort cleanup of a non-secret failed temp path.
                }
            }
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance()
            .getConfigDir()
            .resolve(CONFIG_FILE_NAME);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
    }

    private static String pairingCodeHash(String pairingCode) {
        try {
            String normalized = pairingCode == null
                ? ""
                : pairingCode.trim().toUpperCase();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                ("helix.connector_pairing.fabric_pending.v1\0" + normalized)
                    .getBytes(StandardCharsets.UTF_8)
            );
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
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
        if (!(value instanceof String text) || text.isBlank()) return fallback;
        return text.trim();
    }

    private static int positive(
        Map<String, Object> config,
        String key,
        int fallback
    ) {
        Object value = config.get(key);
        if (!(value instanceof Number number)) return fallback;
        return HelixSensorConfig.positive(number.intValue(), fallback);
    }
}
