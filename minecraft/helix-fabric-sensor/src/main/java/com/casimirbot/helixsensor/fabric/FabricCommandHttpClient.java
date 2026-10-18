package com.casimirbot.helixsensor.fabric;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

final class FabricCommandHttpClient implements AutoCloseable {
    record Response(int statusCode, String body) {
        boolean success() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    private final FabricCommandConfig config;
    private final HttpClient client;

    FabricCommandHttpClient(FabricCommandConfig config) {
        this.config = config;
        this.client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(8))
            .build();
    }

    CompletableFuture<Response> publishCatalog(String json) {
        return post("/catalog", json);
    }

    CompletableFuture<Response> pollPending() {
        return send(
            HttpRequest.newBuilder()
                .uri(URI.create(
                    config.endpoint() +
                    "/requests/pending?limit=" + config.maxPendingPerPoll()
                ))
                .timeout(Duration.ofSeconds(8))
                .GET()
                .build()
        );
    }

    CompletableFuture<Response> publishResult(String json) {
        return post("/requests/result", json);
    }

    private CompletableFuture<Response> post(String path, String json) {
        return send(
            HttpRequest.newBuilder()
                .uri(URI.create(config.endpoint() + path))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                    json,
                    StandardCharsets.UTF_8
                ))
                .build()
        );
    }

    private CompletableFuture<Response> send(HttpRequest request) {
        HttpRequest authenticated = HttpRequest.newBuilder(request, (name, value) -> true)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + config.bearerToken())
            .build();
        // Commands are never retried automatically. A transport failure after
        // dispatch has an unknown outcome and the durable lease must expire.
        return client.sendAsync(
            authenticated,
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        ).thenApply(response -> new Response(
            response.statusCode(),
            response.body() == null ? "" : response.body()
        ));
    }

    @Override
    public void close() {
        client.shutdownNow();
    }
}
