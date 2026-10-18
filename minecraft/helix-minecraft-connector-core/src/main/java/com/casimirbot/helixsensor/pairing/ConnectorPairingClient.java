package com.casimirbot.helixsensor.pairing;

import com.casimirbot.helixsensor.HelixJson;
import com.casimirbot.helixsensor.HelixSensorConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Provider-neutral connector bootstrap client. It knows only the Helix pairing
 * wire contract; Fabric and Paper own their command registration and config
 * storage.
 */
public final class ConnectorPairingClient implements AutoCloseable {
    public static final String REDEEM_PATH =
        "/api/environment-connectors/v1/pairing/redeem";
    public static final String UNPAIR_PATH =
        "/api/environment-connectors/v1/pairing/unpair";
    public static final String LOCAL_PAIRING_ENDPOINT =
        "http://localhost:1522" + REDEEM_PATH;

    private static final Pattern PAIRING_CODE = Pattern.compile(
        "^[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$"
    );
    private static final Pattern OPAQUE_NONCE = Pattern.compile(
        "^[a-zA-Z0-9_-]{32,160}$"
    );
    private static final SecureRandom RANDOM = new SecureRandom();

    public record PairedSourceConfig(
        String endpoint,
        String pairingEndpoint,
        String bearerToken,
        String sourceId,
        String roomId,
        String worldId,
        String domainAdapter,
        Map<String, Object> commandConfig,
        Map<String, Object> actionConfig,
        Map<String, Object> interactionConfig,
        boolean commandOnly,
        boolean actionOnly,
        boolean replayed
    ) {}

    public static final class PairingException extends Exception {
        private final String code;
        private final int statusCode;

        public PairingException(String code, int statusCode, String message) {
            super(message);
            this.code = code;
            this.statusCode = statusCode;
        }

        public String code() {
            return code;
        }

        public int statusCode() {
            return statusCode;
        }
    }

    private final HttpClient client;

    public ConnectorPairingClient() {
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    public static String newRedemptionNonce() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public PairedSourceConfig redeem(
        String pairingEndpoint,
        String pairingCode,
        String redemptionNonce,
        String domainAdapter,
        String connectorVersion
    ) throws PairingException, InterruptedException {
        String normalizedCode = pairingCode == null
            ? ""
            : pairingCode.trim().toUpperCase();
        if (!PAIRING_CODE.matcher(normalizedCode).matches()) {
            throw new PairingException(
                "connector_pairing_invalid",
                400,
                "The pairing code must use the XXXX-XXXX format."
            );
        }
        if (
            redemptionNonce == null ||
            !OPAQUE_NONCE.matcher(redemptionNonce.trim()).matches()
        ) {
            throw new PairingException(
                "connector_pairing_invalid",
                400,
                "The pairing retry nonce is invalid."
            );
        }
        URI endpoint = secureEndpoint(pairingEndpoint, REDEEM_PATH);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("pairing_code", normalizedCode);
        payload.put("redemption_nonce", redemptionNonce.trim());
        payload.put("domain_adapter", required(domainAdapter, "domain adapter"));
        payload.put("connector_kind", domainAdapter.trim());
        payload.put(
            "connector_version",
            required(connectorVersion, "connector version")
        );
        Map<String, Object> body = sendJson(
            endpoint,
            HelixJson.stringify(payload),
            null
        );
        if (!Boolean.TRUE.equals(body.get("ok"))) {
            throw responseFailure(body, 502);
        }
        if (
            !"helix.connector_pairing_redemption.v1".equals(
                string(body, "schema")
            )
        ) {
            throw new PairingException(
                "connector_pairing_response_invalid",
                502,
                "Helix returned an invalid pairing receipt."
            );
        }
        Map<String, Object> config = HelixJson.asObject(body.get("plugin_config"));
        String sourceEndpoint = string(config, "endpoint");
        String returnedPairingEndpoint = string(config, "pairing_endpoint");
        String bearerToken = string(config, "bearer_token");
        String sourceId = string(config, "source_id");
        String roomId = string(config, "room_id");
        String worldId = string(config, "world_id");
        String returnedAdapter = string(config, "domain_adapter");
        boolean commandOnly = "command_only".equals(
            string(config, "pairing_mode")
        );
        boolean actionOnly = "action_only".equals(
            string(config, "pairing_mode")
        );
        Map<String, Object> commandConfig = config.get("command") instanceof Map<?, ?>
            ? new LinkedHashMap<>(HelixJson.asObject(config.get("command")))
            : Map.of();
        Map<String, Object> actionConfig = config.get("action") instanceof Map<?, ?>
            ? new LinkedHashMap<>(HelixJson.asObject(config.get("action")))
            : Map.of();
        Map<String, Object> interactionConfig = config.get("interaction") instanceof Map<?, ?>
            ? new LinkedHashMap<>(HelixJson.asObject(config.get("interaction")))
            : Map.of();
        boolean sourceIdentityInvalid =
            !domainAdapter.trim().equals(returnedAdapter) ||
            !HelixSensorConfig.secureEndpointAllowed(returnedPairingEndpoint) ||
            sourceId.isBlank() ||
            roomId.isBlank() ||
            worldId.isBlank();
        boolean sourceTransportInvalid = !commandOnly && !actionOnly && (
            Boolean.TRUE.equals(config.get("execution_enabled")) ||
            !HelixSensorConfig.secureEndpointAllowed(sourceEndpoint) ||
            !bearerToken.matches("^helix_room_src_[a-zA-Z0-9_-]{43,96}$")
        );
        if (sourceIdentityInvalid || sourceTransportInvalid) {
            throw new PairingException(
                "connector_pairing_response_invalid",
                502,
                "Helix returned a mismatched or unsafe pairing configuration."
            );
        }
        validateCommandConfig(
            commandConfig,
            sourceId,
            roomId,
            worldId,
            returnedAdapter
        );
        validateActionConfig(
            actionConfig,
            sourceId,
            roomId,
            worldId,
            returnedAdapter
        );
        validateInteractionConfig(
            interactionConfig,
            actionConfig,
            sourceId,
            roomId,
            worldId
        );
        if (
            (actionOnly && actionConfig.isEmpty()) ||
            (actionOnly && interactionConfig.isEmpty()) ||
            (!actionOnly && !actionConfig.isEmpty()) ||
            (!actionOnly && !interactionConfig.isEmpty()) ||
            (commandOnly && commandConfig.isEmpty()) ||
            (actionOnly && commandOnly)
        ) {
            throw new PairingException(
                "connector_pairing_response_invalid",
                502,
                "Helix returned an incomplete connector-only pairing configuration."
            );
        }
        return new PairedSourceConfig(
            sourceEndpoint,
            returnedPairingEndpoint,
            bearerToken,
            sourceId,
            roomId,
            worldId,
            returnedAdapter,
            Map.copyOf(commandConfig),
            Map.copyOf(actionConfig),
            Map.copyOf(interactionConfig),
            commandOnly,
            actionOnly,
            Boolean.TRUE.equals(body.get("replayed"))
        );
    }

    private static void validateInteractionConfig(
        Map<String, Object> interaction,
        Map<String, Object> action,
        String sourceId,
        String roomId,
        String worldId
    ) throws PairingException {
        if (interaction.isEmpty()) return;
        boolean valid =
            "helix.environment_interaction.config.v1".equals(string(interaction, "schema")) &&
            HelixSensorConfig.secureEndpointAllowed(string(interaction, "endpoint")) &&
            string(interaction, "bearer_token").matches("^helix_env_interact_[a-zA-Z0-9_-]{43,96}$") &&
            roomId.equals(string(interaction, "room_id")) &&
            sourceId.equals(string(interaction, "source_id")) &&
            worldId.equals(string(interaction, "world_id")) &&
            string(action, "action_authority_id").equals(string(interaction, "action_authority_id")) &&
            string(action, "connector_installation_id").equals(string(interaction, "connector_installation_id")) &&
            string(action, "participant_id").equals(string(interaction, "participant_id")) &&
            string(action, "subject_binding_id").equals(string(interaction, "subject_binding_id")) &&
            string(action, "subject_native_id").equals(string(interaction, "subject_native_id"));
        if (!valid) {
            throw new PairingException(
                "connector_pairing_response_invalid",
                502,
                "Helix returned a mismatched or unsafe interaction configuration."
            );
        }
    }

    private static void validateActionConfig(
        Map<String, Object> action,
        String sourceId,
        String roomId,
        String worldId,
        String domainAdapter
    ) throws PairingException {
        if (action.isEmpty()) return;
        Object policyVersion = action.get("policy_version");
        boolean valid =
            "helix.environment_action.connector_config.v1".equals(
                string(action, "schema")
            ) &&
            Boolean.TRUE.equals(action.get("action_execution_enabled")) &&
            !Boolean.TRUE.equals(action.get("host_access_enabled")) &&
            !Boolean.TRUE.equals(action.get("automatic_replay_enabled")) &&
            Boolean.TRUE.equals(action.get("emergency_stop_required")) &&
            HelixSensorConfig.secureEndpointAllowed(string(action, "endpoint")) &&
            string(action, "bearer_token").matches(
                "^helix_env_action_[a-zA-Z0-9_-]{43,96}$"
            ) &&
            !string(action, "action_authority_id").isBlank() &&
            !string(action, "connector_installation_id").isBlank() &&
            !string(action, "environment_binding_id").isBlank() &&
            !string(action, "adapter_profile_id").isBlank() &&
            !string(action, "participant_id").isBlank() &&
            !string(action, "subject_binding_id").isBlank() &&
            !string(action, "subject_native_id").isBlank() &&
            sourceId.equals(string(action, "source_id")) &&
            roomId.equals(string(action, "room_id")) &&
            worldId.equals(string(action, "world_id")) &&
            domainAdapter.equals(string(action, "domain_adapter")) &&
            policyVersion instanceof Number number &&
            number.intValue() > 0;
        if (!valid) {
            throw new PairingException(
                "connector_pairing_response_invalid",
                502,
                "Helix returned a mismatched or unsafe player-action pairing configuration."
            );
        }
    }

    private static void validateCommandConfig(
        Map<String, Object> command,
        String sourceId,
        String roomId,
        String worldId,
        String domainAdapter
    ) throws PairingException {
        if (command.isEmpty()) return;
        Object policyVersion = command.get("policy_version");
        boolean valid =
            "helix.environment_command.connector_config.v1".equals(
                string(command, "schema")
            ) &&
            Boolean.TRUE.equals(command.get("command_execution_enabled")) &&
            !Boolean.TRUE.equals(command.get("host_access_enabled")) &&
            !Boolean.TRUE.equals(command.get("automatic_retry_enabled")) &&
            HelixSensorConfig.secureEndpointAllowed(string(command, "endpoint")) &&
            string(command, "bearer_token").matches(
                "^helix_env_cmd_[a-zA-Z0-9_-]{43,96}$"
            ) &&
            !string(command, "command_authority_id").isBlank() &&
            !string(command, "environment_binding_id").isBlank() &&
            !string(command, "adapter_profile_id").isBlank() &&
            sourceId.equals(string(command, "source_id")) &&
            roomId.equals(string(command, "room_id")) &&
            worldId.equals(string(command, "world_id")) &&
            domainAdapter.equals(string(command, "domain_adapter")) &&
            policyVersion instanceof Number number &&
            number.intValue() > 0;
        if (!valid) {
            throw new PairingException(
                "connector_pairing_response_invalid",
                502,
                "Helix returned a mismatched or unsafe command pairing configuration."
            );
        }
    }

    public void unpair(PairedSourceConfig config)
        throws PairingException, InterruptedException {
        URI endpoint = secureEndpoint(config.pairingEndpoint(), UNPAIR_PATH);
        Map<String, Object> payload = Map.of(
            "binding_id",
            bindingIdFromIngressEndpoint(config.endpoint())
        );
        Map<String, Object> body = sendJson(
            endpoint,
            HelixJson.stringify(payload),
            config.bearerToken()
        );
        if (
            !Boolean.TRUE.equals(body.get("ok")) ||
            !"revoked".equals(string(body, "status"))
        ) {
            throw responseFailure(body, 502);
        }
    }

    public static String pairingEndpointFromSourceEndpoint(String endpoint) {
        try {
            URI source = URI.create(endpoint);
            if (!HelixSensorConfig.secureEndpointAllowed(source.toString())) {
                return LOCAL_PAIRING_ENDPOINT;
            }
            return new URI(
                source.getScheme(),
                source.getUserInfo(),
                source.getHost(),
                source.getPort(),
                REDEEM_PATH,
                null,
                null
            ).toString();
        } catch (Exception ignored) {
            return LOCAL_PAIRING_ENDPOINT;
        }
    }

    private Map<String, Object> sendJson(
        URI endpoint,
        String body,
        String bearerToken
    ) throws PairingException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearerToken != null && !bearerToken.isBlank()) {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        HttpResponse<String> response;
        try {
            response = client.send(
                builder.build(),
                HttpResponse.BodyHandlers.ofString()
            );
        } catch (IOException error) {
            throw new PairingException(
                "connector_pairing_unreachable",
                503,
                "The Helix pairing endpoint could not be reached."
            );
        }
        Map<String, Object> parsed;
        try {
            parsed = HelixJson.asObject(HelixJson.parse(response.body()));
        } catch (RuntimeException error) {
            throw new PairingException(
                "connector_pairing_response_invalid",
                response.statusCode(),
                "Helix returned an invalid pairing response."
            );
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw responseFailure(parsed, response.statusCode());
        }
        return parsed;
    }

    private static PairingException responseFailure(
        Map<String, Object> body,
        int statusCode
    ) {
        String code = string(body, "error");
        String message = string(body, "message");
        return new PairingException(
            code.isBlank() ? "connector_pairing_failed" : code,
            statusCode,
            message.isBlank() ? "Connector pairing failed." : message
        );
    }

    private static URI secureEndpoint(String value, String requiredPath)
        throws PairingException {
        String normalized = value == null ? "" : value.trim();
        if (!HelixSensorConfig.secureEndpointAllowed(normalized)) {
            throw new PairingException(
                "connector_pairing_endpoint_unsafe",
                400,
                "Pairing requires HTTPS or loopback HTTP."
            );
        }
        try {
            URI provided = URI.create(normalized);
            return new URI(
                provided.getScheme(),
                provided.getUserInfo(),
                provided.getHost(),
                provided.getPort(),
                requiredPath,
                null,
                null
            );
        } catch (Exception error) {
            throw new PairingException(
                "connector_pairing_endpoint_unsafe",
                400,
                "The pairing endpoint is invalid."
            );
        }
    }

    private static String bindingIdFromIngressEndpoint(String endpoint)
        throws PairingException {
        try {
            String path = URI.create(endpoint).getPath();
            String marker = "/bindings/";
            int offset = path.indexOf(marker);
            if (offset < 0) throw new IllegalArgumentException();
            String encoded = path.substring(offset + marker.length());
            int slash = encoded.indexOf('/');
            if (slash >= 0) encoded = encoded.substring(0, slash);
            String bindingId = java.net.URLDecoder.decode(
                encoded,
                java.nio.charset.StandardCharsets.UTF_8
            );
            if (bindingId.isBlank()) throw new IllegalArgumentException();
            return bindingId;
        } catch (RuntimeException error) {
            throw new PairingException(
                "connector_pairing_response_invalid",
                400,
                "The installed source endpoint has no binding identity."
            );
        }
    }

    private static String required(String value, String label)
        throws PairingException {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isBlank()) {
            throw new PairingException(
                "connector_pairing_invalid",
                400,
                "The " + label + " is required."
            );
        }
        return normalized;
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String text ? text.trim() : "";
    }

    @Override
    public void close() {
        // java.net.http.HttpClient has no close method on Java 21.
    }
}
