package com.casimirbot.helixplayer.fabric;

import com.casimirbot.helixsensor.HelixJson;
import com.casimirbot.helixsensor.pairing.ConnectorPairingClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

final class PlayerActionConfigLoader {
    static final String FILE_NAME = "helix-fabric-player-agent.json";
    private static final String CONFIG_SCHEMA = "helix.fabric_player_agent.config.v1";

    private PlayerActionConfigLoader() {}

    record LoadedConfig(
        PlayerActionConfig action,
        PlayerInteractionConfig interaction
    ) {}

    static LoadedConfig loadAll(Logger logger) {
        Map<String, Object> root = readRoot(path(), logger);
        return new LoadedConfig(
            PlayerActionConfig.fromMap(object(root.get("action"))),
            PlayerInteractionConfig.fromMap(object(root.get("interaction")))
        );
    }

    static PlayerActionConfig load(Logger logger) {
        return loadAll(logger).action();
    }

    static String pairingEndpoint(Logger logger) {
        Object value = readRoot(path(), logger).get("pairing_endpoint");
        String configured = value instanceof String text && !text.isBlank()
            ? text.trim()
            : ConnectorPairingClient.LOCAL_PAIRING_ENDPOINT;
        return InstalledDesktopServiceEndpointResolver.resolve(configured);
    }

    static synchronized String loadOrCreateNonce(
        String pairingCode,
        String pairingEndpoint,
        Logger logger
    ) throws IOException {
        Path path = path();
        Map<String, Object> root = readRoot(path, logger);
        Map<String, Object> pending = object(root.get("pairing_pending"));
        String codeHash = pairingCodeHash(pairingCode);
        if (codeHash.equals(pending.get("code_hash")) && pending.get("nonce") instanceof String nonce) {
            return nonce;
        }
        String nonce = ConnectorPairingClient.newRedemptionNonce();
        root.put("schema", CONFIG_SCHEMA);
        root.put("pairing_endpoint", pairingEndpoint);
        root.put("pairing_pending", Map.of("code_hash", codeHash, "nonce", nonce));
        writeRoot(path, root);
        return nonce;
    }

    static synchronized LoadedConfig savePairedAction(
        ConnectorPairingClient.PairedSourceConfig paired,
        Logger logger
    ) throws IOException {
        if (!paired.actionOnly() || paired.actionConfig().isEmpty()) {
            throw new IOException("The pairing receipt did not contain a player-action configuration.");
        }
        PlayerActionConfig config = PlayerActionConfig.fromMap(paired.actionConfig());
        PlayerInteractionConfig interaction = PlayerInteractionConfig.fromMap(
            paired.interactionConfig()
        );
        if (!config.ready() || !interaction.ready()) {
            throw new IOException("The player-action pairing configuration is incomplete or unsafe.");
        }
        Path path = path();
        Map<String, Object> root = readRoot(path, logger);
        root.put("schema", CONFIG_SCHEMA);
        root.put("pairing_endpoint", paired.pairingEndpoint());
        root.put("action", new LinkedHashMap<>(paired.actionConfig()));
        root.put("interaction", new LinkedHashMap<>(paired.interactionConfig()));
        root.remove("pairing_pending");
        writeRoot(path, root);
        return new LoadedConfig(config, interaction);
    }

    static synchronized void clear(Logger logger) throws IOException {
        Path path = path();
        Map<String, Object> root = readRoot(path, logger);
        root.put("schema", CONFIG_SCHEMA);
        root.remove("action");
        root.remove("interaction");
        root.remove("pairing_pending");
        writeRoot(path, root);
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    private static Map<String, Object> readRoot(Path path, Logger logger) {
        if (!Files.exists(path)) return new LinkedHashMap<>();
        try {
            return new LinkedHashMap<>(HelixJson.asObject(
                HelixJson.parse(Files.readString(path, StandardCharsets.UTF_8))
            ));
        } catch (RuntimeException | IOException error) {
            logger.warn("Could not read the Helix player-agent configuration; embodiment remains disabled.");
            return new LinkedHashMap<>();
        }
    }

    private static void writeRoot(Path path, Map<String, Object> root) throws IOException {
        Files.createDirectories(path.getParent());
        Path pending = path.resolveSibling(path.getFileName() + ".pending");
        Files.writeString(
            pending,
            HelixJson.stringifyIncludingNulls(root),
            StandardCharsets.UTF_8
        );
        try {
            Files.move(
                pending,
                path,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
            Files.move(pending, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String pairingCodeHash(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                ("helix.connector_pairing.fabric_player_pending.v1\0" + code.trim().toUpperCase())
                    .getBytes(StandardCharsets.UTF_8)
            ));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
    }
}
