package com.casimirbot.helixplayer.fabric;

import com.casimirbot.helixsensor.HelixJson;
import com.casimirbot.helixsensor.HelixSensorConfig;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.function.UnaryOperator;

final class PlayerActionHttpClient implements AutoCloseable {
    static final Duration CRITICAL_DELIVERY_TIMEOUT = Duration.ofSeconds(60);
    // The installed service durably snapshots a projection batch and then
    // publishes its operator activity before acknowledging it. A 12-second
    // timeout can discard that committed receipt under local pg-mem load,
    // leaving the immutable batch queued and heartbeats behind its retries.
    static final Duration PROJECTION_DELIVERY_TIMEOUT = Duration.ofSeconds(60);
    record Response(int statusCode, Map<String, Object> body) {
        boolean ok() {
            return statusCode >= 200 && statusCode < 300 && Boolean.TRUE.equals(body.get("ok"));
        }

        String error() {
            Object value = body.get("error");
            return value instanceof String text && !text.isBlank()
                ? text
                : "action_connector_request_failed";
        }
    }

    private final PlayerActionConfig config;
    private final HttpClient client;
    private final UnaryOperator<String> endpointResolver;

    PlayerActionHttpClient(PlayerActionConfig config) {
        this(config, InstalledDesktopServiceEndpointResolver::resolve);
    }

    PlayerActionHttpClient(PlayerActionConfig config, UnaryOperator<String> endpointResolver) {
        // A cached lease may expire while the client is closed. Keep the
        // authenticated transport available for a server heartbeat renewal;
        // PlayerActionRuntime still fences action polling on the fresh lease.
        if (!config.transportReady()) throw new IllegalArgumentException("Player-action transport is inactive.");
        this.config = config;
        this.endpointResolver = endpointResolver;
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    }

    Response get(String suffix) throws IOException, InterruptedException {
        return send(
            HttpRequest.newBuilder(endpoint(suffix))
                .GET()
                .timeout(Duration.ofSeconds(12))
                .build()
        );
    }

    Response post(String suffix, Map<String, Object> payload)
        throws IOException, InterruptedException {
        return post(suffix, payload, Duration.ofSeconds(12));
    }

    Response postCritical(String suffix, Map<String, Object> payload)
        throws IOException, InterruptedException {
        return post(suffix, payload, CRITICAL_DELIVERY_TIMEOUT);
    }

    Response postProjection(String suffix, Map<String, Object> payload)
        throws IOException, InterruptedException {
        return post(suffix, payload, PROJECTION_DELIVERY_TIMEOUT);
    }

    private Response post(String suffix, Map<String, Object> payload, Duration timeout)
        throws IOException, InterruptedException {
        return send(
            HttpRequest.newBuilder(endpoint(suffix))
                .POST(HttpRequest.BodyPublishers.ofString(
                    HelixJson.stringifyIncludingNulls(payload)
                ))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .build()
        );
    }

    private Response send(HttpRequest unauthed) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(unauthed.uri())
            .timeout(unauthed.timeout().orElse(Duration.ofSeconds(12)))
            .method(unauthed.method(), unauthed.bodyPublisher().orElse(HttpRequest.BodyPublishers.noBody()))
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + config.bearerToken());
        unauthed.headers().map().forEach((key, values) ->
            values.forEach(value -> builder.header(key, value))
        );
        HttpResponse<String> response = client.send(
            builder.build(),
            HttpResponse.BodyHandlers.ofString()
        );
        Map<String, Object> body;
        try {
            body = HelixJson.asObject(HelixJson.parse(response.body()));
        } catch (RuntimeException error) {
            body = Map.of("ok", false, "error", "action_connector_response_invalid");
        }
        return new Response(response.statusCode(), body);
    }

    private URI endpoint(String suffix) {
        // Resolve the paired base before adding protocol query parameters.
        // The installed-origin resolver deliberately rejects query-bearing
        // inputs; passing a poll URL left it on the closed pre-restart port.
        String value = endpointResolver.apply(
            config.endpoint().replaceAll("/+$", "")
        ) + suffix;
        if (!HelixSensorConfig.secureEndpointAllowed(value)) {
            throw new IllegalArgumentException("Player-action endpoint must use HTTPS or loopback HTTP.");
        }
        return URI.create(value);
    }

    @Override
    public void close() {
        // java.net.http.HttpClient has no close method on Java 21.
    }
}
