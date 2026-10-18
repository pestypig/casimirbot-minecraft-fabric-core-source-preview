package com.casimirbot.helixplayer.fabric;

import com.casimirbot.helixsensor.HelixJson;
import com.casimirbot.helixsensor.HelixSensorConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class PlayerInteractionClient implements AutoCloseable {
    private final PlayerInteractionConfig config;
    private final HttpClient client;
    private final AtomicReference<CompletableFuture<HttpResponse<String>>> active = new AtomicReference<>();
    private volatile String activeRequestId = "";

    PlayerInteractionClient(PlayerInteractionConfig config) {
        this.config = config;
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    }

    boolean ready() {
        return config.ready();
    }

    boolean busy() {
        CompletableFuture<HttpResponse<String>> current = active.get();
        return current != null && !current.isDone();
    }

    String statusText() {
        if (!ready()) return "Helix in-game Ask is not paired.";
        return busy()
            ? "Helix in-game Ask is reasoning for request " + activeRequestId + "."
            : "Helix in-game Ask is ready for room " + config.roomId() + ".";
    }

    void ask(String prompt, Consumer<String> completion) {
        if (!ready()) {
            completion.accept("Helix in-game Ask is not paired. Pair the player connector again.");
            return;
        }
        String question = prompt == null ? "" : prompt.trim();
        if (question.isBlank()) {
            completion.accept("Usage: /helix ask <natural language request>");
            return;
        }
        if (busy()) {
            completion.accept("A Helix in-game Ask request is already running. Use /helix cancel first.");
            return;
        }
        String requestId = "minecraft_ingame_request:" + UUID.randomUUID();
        String turnId = "ask:minecraft_ingame:" + UUID.randomUUID();
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("schema", "helix.environment_interaction.request.v1");
        identity.put("request_id", requestId);
        identity.put("idempotency_key", requestId);
        identity.put("prompt", question);
        identity.put("connector_installation_id", config.connectorInstallationId());
        identity.put("subject_native_id", config.subjectNativeId());
        identity.put("world_id", config.worldId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", question);
        payload.put("prompt", question);
        payload.put("raw_user_prompt", question);
        payload.put("turn_id", turnId);
        payload.put("trace_id", turnId);
        payload.put("session_id", "helix-ask:room:" + config.roomId());
        payload.put("thread_id", "helix-ask:room:" + config.roomId());
        payload.put("agent_runtime", "codex");
        payload.put("selected_agent_provider", "codex");
        payload.put("mode", "act");
        payload.put("environment_interaction_request", identity);

        HttpRequest request = HttpRequest.newBuilder(secureEndpoint(config.endpoint()))
            .timeout(Duration.ofMinutes(5))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + config.bearerToken())
            .POST(HttpRequest.BodyPublishers.ofString(HelixJson.stringifyIncludingNulls(payload)))
            .build();
        activeRequestId = requestId;
        CompletableFuture<HttpResponse<String>> future = client.sendAsync(
            request,
            HttpResponse.BodyHandlers.ofString()
        );
        active.set(future);
        future.whenComplete((response, error) -> {
            active.compareAndSet(future, null);
            activeRequestId = "";
            if (error != null) {
                completion.accept("Helix Ask stopped before a terminal response: " + safeError(error));
                return;
            }
            completion.accept(render(response));
        });
    }

    String cancel() {
        CompletableFuture<HttpResponse<String>> current = active.getAndSet(null);
        activeRequestId = "";
        if (current == null || current.isDone()) return "No in-game Helix Ask request is active.";
        current.cancel(true);
        return "The active in-game Helix Ask request was canceled locally.";
    }

    private static String render(HttpResponse<String> response) {
        try {
            Map<String, Object> body = HelixJson.asObject(HelixJson.parse(response.body()));
            Object projected = body.get("environment_interaction_receipt");
            if (projected instanceof Map<?, ?> rawReceipt) {
                Map<String, Object> receipt = new LinkedHashMap<>();
                rawReceipt.forEach((key, value) -> {
                    if (key instanceof String name) receipt.put(name, value);
                });
                boolean authorityOk = Boolean.TRUE.equals(receipt.get("terminal_authority_ok"));
                String text = firstText(receipt.get("text"));
                if (authorityOk && !text.isBlank()) return "Helix: " + text;
                String error = firstText(receipt.get("error"));
                return "Helix did not return an authoritative terminal answer" +
                    (error.isBlank() ? "." : ": " + error);
            }
            String kind = firstText(body.get("terminal_artifact_kind"));
            String failure = firstText(body.get("terminal_failure_text"));
            if ("typed_failure".equals(kind) && !failure.isBlank()) return "Helix: " + failure;
            String error = firstText(body.get("terminal_error_code"), body.get("error"));
            return "Helix returned " + response.statusCode() + (error.isBlank() ? "." : ": " + error);
        } catch (RuntimeException invalid) {
            return "Helix returned an invalid response (HTTP " + response.statusCode() + ").";
        }
    }

    private static String firstText(Object... values) {
        for (Object value : values) {
            if (value instanceof String text && !text.isBlank()) return text.trim();
        }
        return "";
    }

    private static URI secureEndpoint(String value) {
        String resolved = InstalledDesktopServiceEndpointResolver.resolve(value);
        if (!HelixSensorConfig.secureEndpointAllowed(resolved)) {
            throw new IllegalArgumentException("In-game Ask endpoint must use HTTPS or loopback HTTP.");
        }
        return URI.create(resolved);
    }

    private static String safeError(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) current = current.getCause();
        String name = current.getClass().getSimpleName();
        return name.isBlank() ? "request_failed" : name;
    }

    @Override
    public void close() {
        cancel();
    }
}
