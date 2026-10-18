package com.casimirbot.helixsensor.fabric;

import com.casimirbot.helixsensor.HelixHttpClient;
import com.casimirbot.helixsensor.HelixJson;
import com.casimirbot.helixsensor.HelixSensorConfig;
import com.casimirbot.helixsensor.HelixSensorRuntimeStatus;
import com.casimirbot.helixsensor.manifest.EnvironmentSourceManifestFactory;
import com.casimirbot.helixsensor.probe.ProbeSubmissionEnvelope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class FabricConnectorRuntime implements AutoCloseable {
    public static final String ADAPTER_VERSION = "0.3.0";

    private final HelixSensorConfig config;
    private final FabricCommandConfig commandConfig;
    private final Logger logger;
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean admitted = new AtomicBoolean(false);
    private final AtomicBoolean manifestInFlight = new AtomicBoolean(false);
    private final AtomicBoolean heartbeatInFlight = new AtomicBoolean(false);
    private final AtomicBoolean snapshotInFlight = new AtomicBoolean(false);
    private final AtomicBoolean pollInFlight = new AtomicBoolean(false);
    private final AtomicInteger pendingProbeCount = new AtomicInteger(0);
    private final long sourceTickBase = Instant.now().toEpochMilli() * 20L;
    private final String manifestCreatedAt = Instant.now().toString();

    private MinecraftServer server;
    private HelixHttpClient httpClient;
    private HelixSensorRuntimeStatus runtimeStatus;
    private FabricProbeExecutor probeExecutor;
    private FabricTransportScheduler manifestScheduler;
    private FabricCommandRuntime commandRuntime;
    private long localTick;
    private String latestSnapshotId;
    private String latestSnapshotTimestamp;

    public FabricConnectorRuntime(
        HelixSensorConfig config,
        FabricCommandConfig commandConfig,
        Logger logger
    ) {
        this.config = config;
        this.commandConfig = commandConfig;
        this.logger = logger;
    }

    public void start(MinecraftServer server) {
        if (!config.enabled()) {
            logger.info(
                "Helix Fabric Sensor is installed but disabled. " +
                "No connector network activity was started."
            );
            return;
        }
        if (config.executionEnabled()) {
            logger.severe(
                "Helix Fabric Sensor refuses to start with execution_enabled=true."
            );
            return;
        }
        if (!config.sensorUploadsAllowed()) {
            logger.severe(
                "Helix Fabric Sensor requires HTTPS (or loopback HTTP) and a generated room-source credential."
            );
            return;
        }
        if (!active.compareAndSet(false, true)) return;
        this.server = server;
        this.runtimeStatus = new HelixSensorRuntimeStatus(config);
        this.httpClient = new HelixHttpClient(
            config,
            logger,
            runtimeStatus,
            () -> active.set(false),
            DesktopServiceEndpointResolver::resolve
        );
        this.probeExecutor = new FabricProbeExecutor(
            server,
            config,
            runtimeStatus
        );
        this.commandRuntime = new FabricCommandRuntime(
            commandConfig,
            logger,
            httpClient.producerEpochRef()
        );
        publishManifest();
        this.manifestScheduler = new FabricTransportScheduler(
            "helix-fabric-manifest-refresh"
        );
        this.manifestScheduler.start(
            this::publishManifestSafely,
            manifestRefreshIntervalMillis()
        );
        logger.info(
            "Helix Fabric Sensor started in read-only mode and is waiting for manifest admission."
        );
    }

    public void tick(MinecraftServer tickingServer) {
        if (!active.get() || server != tickingServer || httpClient == null) {
            return;
        }
        localTick++;
        if (!admitted.get()) return;
        if (commandRuntime != null) commandRuntime.tick();
        if (localTick % config.heartbeatIntervalTicks() == 0L) {
            publishHeartbeat();
        }
        if (localTick % config.snapshotIntervalTicks() == 0L) {
            publishSnapshot();
        }
        if (
            config.readOnlyProbesEnabled() &&
            localTick % config.probePollIntervalTicks() == 0L
        ) {
            pollProbes();
        }
    }

    public boolean active() {
        return active.get();
    }

    public boolean admitted() {
        return admitted.get();
    }

    public HelixSensorRuntimeStatus runtimeStatus() {
        return runtimeStatus;
    }

    private void publishManifest() {
        if (
            !active.get() ||
            httpClient == null ||
            httpClient.terminallyPaused() ||
            !manifestInFlight.compareAndSet(false, true)
        ) {
            return;
        }
        runtimeStatus.recordManifestAttempt();
        Map<String, Object> manifest = EnvironmentSourceManifestFactory.build(
            config,
            ADAPTER_VERSION,
            manifestCreatedAt,
            List.of("spatial_region", "registry_fact", "recipe_fact")
        );
        httpClient
            .postManifestAsync(HelixJson.stringify(manifest))
            .whenComplete((response, error) -> {
                manifestInFlight.set(false);
                if (error != null) {
                    admitted.set(false);
                    logger.warning(
                        "Helix Fabric Sensor manifest transport failed."
                    );
                    return;
                }
                if (response.success()) {
                    boolean recovered = admitted.compareAndSet(false, true);
                    if (recovered) {
                        logger.info(
                            "Helix Fabric Sensor manifest was admitted; read-only observations are active."
                        );
                    }
                    FabricCommandRuntime currentCommandRuntime = commandRuntime;
                    if (currentCommandRuntime != null) {
                        if (currentCommandRuntime.active()) {
                            currentCommandRuntime.refreshAfterManifestAdmission();
                        } else {
                            currentCommandRuntime.start(server);
                        }
                    }
                    return;
                }
                admitted.set(false);
                if (
                    !"client_backoff".equals(response.errorCode()) &&
                    !"client_terminally_paused".equals(response.errorCode())
                ) {
                    logger.warning(
                        "Helix Fabric Sensor manifest was not admitted: " +
                        response.failureSummary()
                    );
                }
            });
    }

    private void publishManifestSafely() {
        try {
            publishManifest();
        } catch (RuntimeException error) {
            logger.warning(
                "Helix Fabric Sensor wall-clock manifest refresh failed: " +
                error.getClass().getSimpleName()
            );
        }
    }

    private long manifestRefreshIntervalMillis() {
        return manifestRefreshIntervalMillisFor(
            config.heartbeatIntervalTicks()
        );
    }

    static long manifestRefreshIntervalMillisFor(
        int heartbeatIntervalTicks
    ) {
        return Math.max(
            Math.multiplyExact(
                (long) HelixSensorConfig.MIN_MANIFEST_REFRESH_INTERVAL_TICKS,
                50L
            ),
            Math.multiplyExact((long) heartbeatIntervalTicks, 50L)
        );
    }

    private void publishHeartbeat() {
        if (!heartbeatInFlight.compareAndSet(false, true)) return;
        String now = Instant.now().toString();
        List<Map<String, Object>> players = new ArrayList<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String label = player.getGameProfile().getName();
            players.add(
                Map.of(
                    "actor_id",
                    FabricProbeExecutor.canonicalActorId(label),
                    "stable_actor_id",
                    player.getUUID().toString(),
                    "actor_label",
                    label,
                    "dimension",
                    FabricProbeExecutor.dimension(player)
                )
            );
        }
        Map<String, Object> heartbeat = new LinkedHashMap<>();
        heartbeat.put(
            "schema",
            "helix.environment_source_heartbeat.v1"
        );
        heartbeat.put(
            "heartbeat_id",
            "heartbeat:" + config.sourceId() + ":" + now
        );
        heartbeat.put("source_id", config.sourceId());
        heartbeat.put("room_id", config.roomId());
        heartbeat.put("domain", "minecraft");
        heartbeat.put("domain_adapter", config.domainAdapter());
        heartbeat.put(
            "status",
            httpClient.pausedForAuth()
                ? "error"
                : httpClient.degraded()
                    ? "degraded"
                    : "active"
        );
        heartbeat.put("server_tick", sourceTick());
        if (latestSnapshotId != null) {
            heartbeat.put("latest_snapshot_id", latestSnapshotId);
        }
        if (latestSnapshotTimestamp != null) {
            heartbeat.put("latest_snapshot_ts", latestSnapshotTimestamp);
        }
        heartbeat.put("active_players", players);
        heartbeat.put("pending_probe_count", pendingProbeCount.get());
        heartbeat.put(
            "backpressure",
            Map.of(
                "snapshot_upload_pending",
                snapshotInFlight.get(),
                "skipped_snapshot_count",
                runtimeStatus.skippedSnapshotCount,
                "avg_payload_bytes",
                runtimeStatus.avgPayloadBytes
            )
        );
        heartbeat.put(
            "runtime_status",
            Map.of(
                "upload_queue",
                runtimeStatus.uploadQueueState,
                "backoff_state",
                runtimeStatus.backoffState,
                "auth_failure_count",
                runtimeStatus.authFailureCount,
                "oversized_payload_count",
                runtimeStatus.oversizedPayloadCount,
                "contract_failure_count",
                runtimeStatus.contractFailureCount,
                "last_error",
                runtimeStatus.lastError == null ? "" : runtimeStatus.lastError
            )
        );
        heartbeat.put(
            "evidence_refs",
            List.of("minecraft:fabric:heartbeat:" + sourceTick())
        );
        heartbeat.put("assistant_answer", false);
        heartbeat.put("raw_content_included", false);
        heartbeat.put("created_at", now);
        httpClient
            .postHeartbeatAsync(HelixJson.stringify(heartbeat))
            .whenComplete((response, error) -> {
                heartbeatInFlight.set(false);
                if (error != null) {
                    logger.warning(
                        "Helix Fabric Sensor heartbeat transport failed."
                    );
                }
            });
    }

    private void publishSnapshot() {
        if (!snapshotInFlight.compareAndSet(false, true)) {
            runtimeStatus.recordSnapshotSkipped();
            return;
        }
        long started = System.nanoTime();
        FabricSnapshotBuilder.SnapshotBatch batch =
            FabricSnapshotBuilder.build(server, config, sourceTick());
        String json = HelixJson.stringify(batch.payload());
        runtimeStatus.recordPayload(
            json.getBytes(StandardCharsets.UTF_8).length,
            Math.max(0L, (System.nanoTime() - started) / 1_000_000L),
            "Fabric actor, inventory, entity, local-map, focus, and affordance snapshot"
        );
        httpClient
            .postWorldEventBatchAsync(json)
            .whenComplete((response, error) -> {
                snapshotInFlight.set(false);
                if (error == null && response != null && response.success()) {
                    latestSnapshotId = batch.snapshotId();
                    latestSnapshotTimestamp = batch.snapshotTimestamp();
                } else if (error != null) {
                    logger.warning(
                        "Helix Fabric Sensor snapshot transport failed."
                    );
                } else if (response != null) {
                    logger.warning(
                        "Helix Fabric Sensor snapshot was rejected: " +
                        response.failureSummary()
                    );
                } else {
                    logger.warning(
                        "Helix Fabric Sensor snapshot returned no receipt."
                    );
                }
            });
    }

    private void pollProbes() {
        if (!pollInFlight.compareAndSet(false, true)) return;
        httpClient
            .getPendingProbesAsync()
            .whenComplete((body, error) -> {
                if (error != null) {
                    pollInFlight.set(false);
                    logger.warning(
                        "Helix Fabric Sensor probe polling failed."
                    );
                    return;
                }
                Map<String, Object> parsed;
                try {
                    parsed = HelixJson.asObject(HelixJson.parse(body));
                } catch (RuntimeException parseError) {
                    admitted.set(false);
                    pollInFlight.set(false);
                    logger.warning(
                        "Helix Fabric Sensor probe transport or envelope failed; manifest readmission is required."
                    );
                    return;
                }
                List<Object> requested = HelixJson.asList(
                    parsed.get("probe_requests")
                );
                int limit = Math.min(
                    requested.size(),
                    config.maxPendingProbesPerPoll()
                );
                List<Map<String, Object>> probes = new ArrayList<>();
                for (int index = 0; index < limit; index++) {
                    probes.add(HelixJson.asObject(requested.get(index)));
                }
                pendingProbeCount.set(probes.size());
                runtimeStatus.setPendingProbeCount(probes.size());
                if (probes.isEmpty()) {
                    pollInFlight.set(false);
                    return;
                }
                MinecraftServer capturedServer = server;
                capturedServer.execute(() -> executeAndPublish(probes));
            });
    }

    private void executeAndPublish(List<Map<String, Object>> probes) {
        if (!active.get() || server == null || probeExecutor == null) {
            pollInFlight.set(false);
            return;
        }
        for (Map<String, Object> probe : probes) {
            Map<String, Object> result =
                probeExecutor.executeOnServerThread(probe);
            Map<String, Object> submission =
                ProbeSubmissionEnvelope.forProbe(probe, result);
            httpClient
                .postProbeResultAsync(HelixJson.stringify(submission))
                .whenComplete((response, error) -> markProbeComplete());
        }
        pollInFlight.set(false);
    }

    private void markProbeComplete() {
        pendingProbeCount.updateAndGet(value -> Math.max(0, value - 1));
        runtimeStatus.setPendingProbeCount(pendingProbeCount.get());
    }

    private long sourceTick() {
        return sourceTickBase + localTick;
    }

    @Override
    public void close() {
        active.set(false);
        admitted.set(false);
        pendingProbeCount.set(0);
        if (runtimeStatus != null) runtimeStatus.setPendingProbeCount(0);
        if (manifestScheduler != null) manifestScheduler.close();
        manifestScheduler = null;
        if (httpClient != null) httpClient.close();
        httpClient = null;
        probeExecutor = null;
        if (commandRuntime != null) commandRuntime.close();
        commandRuntime = null;
        server = null;
        logger.info("Helix Fabric Sensor stopped.");
    }
}
