package com.casimirbot.helixsensor.fabric;

import com.casimirbot.helixsensor.HelixJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import net.minecraft.server.MinecraftServer;

final class FabricCommandRuntime implements AutoCloseable {
    static final long CATALOG_REPUBLISH_INTERVAL_MILLIS = 30_000L;

    private final FabricCommandConfig config;
    private final Logger logger;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean catalogReady = new AtomicBoolean(false);
    private final AtomicBoolean catalogInFlight = new AtomicBoolean(false);
    private final AtomicBoolean pollInFlight = new AtomicBoolean(false);
    private final String producerEpochRef;

    private MinecraftServer server;
    private FabricCommandHttpClient httpClient;
    private FabricCommandExecutor executor;
    private long ticks;
    private volatile long lastCatalogPublishAttemptAtMillis;

    FabricCommandRuntime(
        FabricCommandConfig config,
        Logger logger,
        String producerEpochRef
    ) {
        this.config = config;
        this.logger = logger;
        this.producerEpochRef = producerEpochRef;
    }

    void start(MinecraftServer server) {
        if (!config.enabled()) {
            logger.info(
                "Helix Fabric command lane is disabled; observation remains available."
            );
            return;
        }
        if (!config.connectorAllowed()) {
            logger.warning(
                "Helix Fabric command configuration was rejected. A separate generated command credential and exact environment identity are required."
            );
            return;
        }
        if (!active.compareAndSet(false, true)) return;
        this.server = server;
        this.httpClient = new FabricCommandHttpClient(config);
        publishCatalog();
        logger.info(
            "Helix Fabric command lane started after source admission, with host access and command retries disabled."
        );
    }

    void refreshAfterManifestAdmission() {
        if (!active.get() || server == null || httpClient == null) return;
        long nowMillis = System.currentTimeMillis();
        if (
            !catalogRepublishDue(
                lastCatalogPublishAttemptAtMillis,
                nowMillis
            )
        ) return;
        // Manifest admission can succeed after a fast Helix API restart that
        // was shorter than the connector's refresh interval. The source may
        // never have observed a failed manifest, while the restarted API no
        // longer has its catalog snapshot. Periodically republish the bounded
        // catalog without pausing an already-valid command lane; the broker
        // idempotently replays the same tree/producer epoch when it still has
        // the snapshot.
        publishCatalog();
    }

    static boolean catalogRepublishDue(
        long lastAttemptAtMillis,
        long nowMillis
    ) {
        return lastAttemptAtMillis <= 0L ||
            nowMillis < lastAttemptAtMillis ||
            nowMillis - lastAttemptAtMillis >=
                CATALOG_REPUBLISH_INTERVAL_MILLIS;
    }

    boolean active() {
        return active.get();
    }

    void tick() {
        if (!active.get() || server == null || httpClient == null) return;
        ticks++;
        if (
            catalogReady.get() &&
            ticks % config.pollIntervalTicks() == 0L
        ) {
            poll();
        }
    }

    private void publishCatalog() {
        if (!catalogInFlight.compareAndSet(false, true)) return;
        lastCatalogPublishAttemptAtMillis = System.currentTimeMillis();
        Map<String, Object> catalog = FabricCommandCatalogBuilder.build(
            server,
            config,
            producerEpochRef
        );
        String catalogId = String.valueOf(catalog.get("command_catalog_id"));
        httpClient
            .publishCatalog(HelixJson.stringifyIncludingNulls(catalog))
            .whenComplete((response, error) -> {
                catalogInFlight.set(false);
                if (
                    error == null &&
                    response != null &&
                    response.success()
                ) {
                    String admittedCatalogId = catalogIdFromReceipt(
                        response.body(),
                        catalogId
                    );
                    if (admittedCatalogId == null) {
                        catalogReady.set(false);
                        logger.warning(
                            "Helix Fabric command catalog receipt was invalid; execution remains paused."
                        );
                        return;
                    }
                    executor = new FabricCommandExecutor(
                        server,
                        config,
                        admittedCatalogId
                    );
                    catalogReady.set(true);
                    logger.info(
                        "Helix Fabric live Brigadier command catalog was admitted."
                    );
                    return;
                }
                catalogReady.set(false);
                logger.warning(
                    "Helix Fabric command catalog was not admitted; execution remains paused (" +
                    catalogFailureSummary(response, error) + ")."
                );
            });
    }

    static String catalogFailureSummary(
        FabricCommandHttpClient.Response response,
        Throwable error
    ) {
        if (error != null) return "transport_error";
        if (response == null) return "missing_response";
        String errorCode = "unspecified";
        try {
            Map<String, Object> receipt = HelixJson.asObject(
                HelixJson.parse(response.body())
            );
            String candidate = String.valueOf(
                receipt.getOrDefault("error", "")
            ).trim().toLowerCase(java.util.Locale.ROOT);
            if (candidate.matches("^[a-z0-9_]{1,80}$")) {
                errorCode = candidate;
            }
        } catch (RuntimeException ignored) {
            // Never log the response body. A bounded code is sufficient.
        }
        return "http_" + response.statusCode() + "_" + errorCode;
    }

    static String catalogIdFromReceipt(String body, String proposedCatalogId) {
        try {
            Map<String, Object> receipt = HelixJson.asObject(
                HelixJson.parse(body)
            );
            if (
                !"helix.environment_command.catalog_receipt.v1".equals(
                    String.valueOf(receipt.get("schema"))
                ) ||
                !Boolean.TRUE.equals(receipt.get("ok"))
            ) {
                return null;
            }
            String admitted = String.valueOf(
                receipt.getOrDefault("command_catalog_id", "")
            ).trim();
            if (!admitted.startsWith("command_catalog:")) return null;
            boolean replayed = Boolean.TRUE.equals(receipt.get("replayed"));
            if (!replayed && !admitted.equals(proposedCatalogId)) return null;
            // A replay may intentionally return the already-recorded catalog
            // id instead of this publish attempt's proposed id.
            return admitted;
        } catch (RuntimeException error) {
            return null;
        }
    }

    private void poll() {
        if (!pollInFlight.compareAndSet(false, true)) return;
        httpClient.pollPending().whenComplete((response, error) -> {
            if (error != null || response == null || !response.success()) {
                pollInFlight.set(false);
                if (response != null && (response.statusCode() == 401 || response.statusCode() == 403)) {
                    active.set(false);
                    logger.warning(
                        "Helix Fabric command credential was rejected; command polling stopped."
                    );
                }
                return;
            }
            List<Map<String, Object>> requests;
            try {
                Map<String, Object> body = HelixJson.asObject(
                    HelixJson.parse(response.body())
                );
                List<Object> raw = HelixJson.asList(body.get("command_requests"));
                requests = new ArrayList<>();
                for (
                    int index = 0;
                    index < Math.min(raw.size(), config.maxPendingPerPoll());
                    index++
                ) {
                    requests.add(HelixJson.asObject(raw.get(index)));
                }
            } catch (RuntimeException parseError) {
                pollInFlight.set(false);
                logger.warning(
                    "Helix Fabric command poll returned an invalid envelope."
                );
                return;
            }
            if (requests.isEmpty()) {
                pollInFlight.set(false);
                return;
            }
            MinecraftServer captured = server;
            captured.execute(() -> executeAndPublish(requests));
        });
    }

    private void executeAndPublish(List<Map<String, Object>> requests) {
        if (!active.get() || executor == null || httpClient == null) {
            pollInFlight.set(false);
            return;
        }
        List<CompletableFuture<?>> publishes = new ArrayList<>();
        for (Map<String, Object> request : requests) {
            Map<String, Object> result = executor.execute(request);
            publishes.add(
                httpClient
                    .publishResult(HelixJson.stringify(result))
                    .exceptionally(error -> null)
            );
        }
        CompletableFuture
            .allOf(publishes.toArray(CompletableFuture[]::new))
            .whenComplete((ignored, error) -> pollInFlight.set(false));
    }

    @Override
    public void close() {
        active.set(false);
        catalogReady.set(false);
        catalogInFlight.set(false);
        pollInFlight.set(false);
        lastCatalogPublishAttemptAtMillis = 0L;
        if (httpClient != null) httpClient.close();
        httpClient = null;
        executor = null;
        server = null;
    }
}
