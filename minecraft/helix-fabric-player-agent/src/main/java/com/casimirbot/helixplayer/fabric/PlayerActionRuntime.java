package com.casimirbot.helixplayer.fabric;

import com.casimirbot.helixsensor.HelixJson;
import com.casimirbot.helixsensor.snapshot.SectionHasher;
import com.casimirbot.helixplayer.fabric.PlayerActionWorkflow.ActionRequest;
import com.casimirbot.helixplayer.fabric.PlayerActionWorkflow.ManualOverridePolicy;
import com.casimirbot.helixplayer.fabric.PlayerActionWorkflow.PlayerSnapshot;
import com.casimirbot.helixplayer.fabric.PlayerActionWorkflow.State;
import com.casimirbot.helixplayer.fabric.PlayerActionWorkflow.WorkflowEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

final class PlayerActionRuntime implements AutoCloseable {
    private static final String PROTOCOL_VERSION = "helix.environment_action.v1";
    static final String ADAPTER_VERSION = "0.4.11";
    private static final int POLL_INTERVAL_TICKS = 20;
    // Nominally one second at 20 Hz. This is a publication opportunity, not
    // a freshness guarantee: transport remains single-flight and admission
    // must still reject an older/unmapped resident sample.
    private static final int HEARTBEAT_INTERVAL_TICKS = 20;
    private static final int LOCAL_STATUS_INTERVAL_TICKS = 5;
    private static final int MAX_PENDING_DELIVERIES = 768;
    private static final int RESERVED_TERMINAL_DELIVERIES = 3;
    private static final long SLOW_DELIVERY_MILLIS = 750;

    private record ActiveEnvelope(
        Map<String, Object> wire,
        String actionExecutionId,
        String startedAt,
        Map<String, Object> startedClock,
        String controlEngine,
        List<String> progressEventRefs,
        EnvironmentCapacityTelemetry capacityTelemetry
    ) {}

    // Owned on the Minecraft client thread. Keep root action identity unchanged.
    private static final class TemporalDeliveryState {
        Map<String, Object> currentWire;
        Map<String, Object> queuedWire;
        // Diagnostic context only, scoped to the synchronous queue callback.
        String acceptingSuccessorActionRequestId;
        Map<String, Object> measurements = Map.of();
        final TemporalResponseSlot<TemporalReceived> received = new TemporalResponseSlot<>();
        long interruptionRevision;
        String checkpointEvidenceKey;
        long checkpointDeliveryWatermark;
        TemporalPoll uncertainPoll;
        String queuedCheckpoint;
        TemporalDeliveryState(Map<String, Object> wire) { currentWire = wire; }
    }
    private TemporalDeliveryState temporalDeliveryState;
    private record TemporalPoll(ActiveEnvelope envelope, Map<String, Object> wire, long interruptionRevision, String checkpoint, Map<String, Object> body) {}
    private record TemporalReceived(TemporalPoll poll, Map<String, Object> wire) {}

    // Client-thread-owned, memory-only transport retention. Never survives a
    // resident replacement or authorizes execution/replay on its own.
    static final class TemporalResponseSlot<T> {
        private T value;
        boolean offer(T response) {
            if (value != null || response == null) return false;
            value = response;
            return true;
        }
        boolean pending() { return value != null; }
        T take(boolean current, boolean evidencePending) {
            if (!current) { value = null; return null; }
            if (evidencePending) return null;
            T response = value;
            value = null;
            return response;
        }
    }

    private record LocalDiagnosticEnvelope(
        String actionRequestId,
        String workflowId,
        String actionKind,
        Map<String, Object> arguments,
        String controlEngine,
        String stagingRequestRef,
        Map<String, Object> startingState,
        String startedAt,
        Map<String, Object> startedClock
    ) {}

    private final PlayerActionConfig config;
    private final Minecraft minecraft;
    private final java.util.concurrent.Executor clientExecutor;
    private final Logger logger;
    private final NativeFabricControlBridge bridge;
    private final java.util.function.Supplier<PlayerActionWorkflow.PlayerSnapshot> evidenceSnapshot;
    private final PlayerActionController controller;
    private final ExecutorService network;
    private final TemporalDeliveryLane temporalDeliveryLane = new TemporalDeliveryLane();
    private final ExecutorService criticalDeliveryNetwork;
    private final ExecutorService projectionDeliveryNetwork;
    private final PlayerActionHttpClient http;
    private final Consumer<String> localDiagnosticMessage;
    private final PlayerActionLocalStatusWriter localStatusWriter;
    private volatile String producerEpochRef = id("environment_action_epoch");
    private volatile String manifestId = id("environment_action_manifest");
    private final String executionClockId = id("minecraft_client_tick_clock");
    private final EnvironmentMonotonicClock monotonicClock = new EnvironmentMonotonicClock();
    private final AtomicBoolean cyclePending = new AtomicBoolean(false);
    private final AtomicBoolean temporalPollDeferred = new AtomicBoolean(false);
    private final AtomicBoolean manifestPending = new AtomicBoolean(false);
    private final AtomicBoolean heartbeatPublishPending = new AtomicBoolean(false);
    private final AtomicBoolean criticalDeliveryFlushPending = new AtomicBoolean(false);
    private final AtomicBoolean projectionDeliveryFlushPending = new AtomicBoolean(false);
    private volatile boolean manifestReady;
    private volatile boolean heartbeatReady;
    private volatile boolean workflowEventBatchSupported;
    private volatile Instant lastHeartbeatAcceptedAt;
    private volatile Instant credentialExpiresAt;
    private volatile boolean emergencyStopLatched;
    private volatile boolean eventStreamResyncRequired;
    private volatile boolean manualInputDetected;
    private volatile long latestEventSequence = -1;
    private volatile long latestAcknowledgedEventSequence = -1;
    private volatile long lastProjectionAcknowledgedNanos;
    private volatile String lastTransportError = "";
    private volatile ActiveEnvelope activeEnvelope;
    private volatile LocalDiagnosticEnvelope localDiagnosticEnvelope;
    private final PlayerActionDeliveryOutbox deliveryOutbox =
        new PlayerActionDeliveryOutbox(MAX_PENDING_DELIVERIES);
    private volatile Map<String, Object> latestClockSnapshot = Map.of();
    private final List<WorkflowEvent> deferredWorkflowEvents = new ArrayList<>();
    private boolean residentCallbackInProgress;
    private volatile MinecraftViabilityGuardian.Decision latestViabilityDecision;
    private volatile String lastViabilityNotice = "";
    private volatile String lastViabilityEventReason = "";
    private boolean remoteViabilityGuardianArmed;
    private long ticks;

    PlayerActionRuntime(
        PlayerActionConfig config,
        Minecraft minecraft,
        Logger logger,
        Consumer<String> localDiagnosticMessage
    ) {
        this(config, minecraft, logger, localDiagnosticMessage, minecraft::execute,
            minecraft.gameDirectory.toPath().resolve("config").resolve("helix-fabric-player-agent.runtime-status.json"));
    }

    PlayerActionRuntime(PlayerActionConfig config, Minecraft minecraft, Logger logger,
        Consumer<String> localDiagnosticMessage, java.util.concurrent.Executor clientExecutor,
        java.nio.file.Path statusPath) {
        this.config = config;
        try {
            this.credentialExpiresAt = Instant.parse(config.expiresAt());
        } catch (RuntimeException ignored) {
            this.credentialExpiresAt = null;
        }
        this.minecraft = minecraft;
        this.clientExecutor = java.util.Objects.requireNonNull(clientExecutor);
        this.logger = logger;
        this.localDiagnosticMessage = localDiagnosticMessage;
        this.localStatusWriter = new PlayerActionLocalStatusWriter(
            statusPath,
            logger
        );
        this.bridge = new NativeFabricControlBridge(minecraft);
        this.evidenceSnapshot = bridge::snapshot;
        this.controller = new PlayerActionController(bridge, this::onWorkflowEvent);
        this.network = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "helix-player-action-network");
            thread.setDaemon(true);
            return thread;
        });
        this.criticalDeliveryNetwork = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "helix-player-action-critical-delivery");
            thread.setDaemon(true);
            return thread;
        });
        this.projectionDeliveryNetwork = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "helix-player-action-projection-delivery");
            thread.setDaemon(true);
            return thread;
        });
        this.http = config.transportReady() ? new PlayerActionHttpClient(config) : null;
    }

    void start() {
        // Manifest admission is deferred until this client is connected as the
        // exact server-observed player bound by the room authority.
    }

    void tick() {
        boolean credentialCurrent = credentialLeaseCurrent();
        boolean remoteWorkflowLeaseExpired = credentialExpiryRequiresWorkflowStop(
            activeEnvelope != null, controller.activeWorkflowId(), credentialCurrent
        );
        boolean remoteGuardianMustDisarm = remoteGuardianMustDisarm(
            remoteViabilityGuardianArmed, credentialCurrent, heartbeatReady,
            eventStreamResyncRequired, emergencyStopLatched
        );
        if (remoteGuardianMustDisarm) {
            remoteViabilityGuardianArmed = false;
            bridge.disarmViabilityGuardian();
        }
        if (remoteWorkflowLeaseExpired) {
            controller.connectorOffline(
                "The Helix player-action credential expired; the active workflow was stopped and every client control was released."
            );
        } else if (remoteGuardianMustDisarm && activeEnvelope != null &&
            controller.activeWorkflowId() != null) {
            controller.connectorOffline(
                "The Helix player-action authority became unavailable; the active workflow and remote guardian stopped."
            );
        }
        if (remoteGuardianMustDisarm || remoteWorkflowLeaseExpired) bridge.releaseAll();
        // A remotely armed guardian must not assert another control on the
        // expiry or transport-failure tick before the ordinary stop check.
        boolean residentOwnsTick = tickViabilityGuardian();
        if (eventStreamResyncRequired) {
            // The server can no longer admit the immutable evidence sequence
            // for this producer epoch. Never continue acting while terminal
            // evidence is stranded behind that boundary.
            if (eventStreamResyncRequiresWorkflowStop(
                true,
                controller.activeWorkflowId()
            )) {
                controller.connectorOffline(
                    "The Helix evidence stream requires a fresh pairing; the active workflow was stopped and every client control was released."
                );
            }
            bridge.releaseAll();
        } else if (remoteWorkflowLeaseExpired || remoteGuardianMustDisarm) {
            // The stop ran before the resident guardian could take this tick.
        } else if (activeControlPlaneFailureRequiresStop(
            activeEnvelope != null,
            controller.activeWorkflowId(),
            lastTransportError
        )) {
            controller.connectorOffline(
                "The Helix action transport became unreachable; the client released every control and retained the terminal event for later delivery."
            );
            bridge.releaseAll();
        } else if (!residentOwnsTick) {
            ActiveEnvelope tickEnvelope = activeEnvelope;
            if (tickEnvelope == null || controller.activeWorkflowId() == null) {
                controller.tick();
            } else {
                boolean activeControl = controller.state() == State.RUNNING;
                long tickStartedNanos = System.nanoTime();
                tickEnvelope.capacityTelemetry().recordSchedulerTick(
                    activeControl,
                    tickStartedNanos
                );
                residentCallbackInProgress = true;
                try {
                    controller.tick();
                } finally {
                    tickEnvelope.capacityTelemetry().recordResidentComputation(
                        Math.max(0L, System.nanoTime() - tickStartedNanos)
                    );
                    residentCallbackInProgress = false;
                    flushDeferredWorkflowEvents();
                }
            }
        }
        ticks++;
        latestClockSnapshot = captureClockSnapshot(ticks);
        // A failed evidence POST must remain retryable even after the broker
        // deadline makes action polling ineligible. This only retransmits
        // immutable outbox receipts; it never replays physical controls.
        if (config.transportReady() && http != null && deliveryRetryDue(ticks, !deliveryOutbox.isEmpty())) {
            scheduleDeliveryFlush();
        }
        if (ticks % LOCAL_STATUS_INTERVAL_TICKS == 0) publishLocalStatus();
        if (activeEnvelope != null && ticks % (HEARTBEAT_INTERVAL_TICKS * 5) == 0) {
            long heartbeatAgeMillis = lastHeartbeatAcceptedAt == null ? -1
                : Math.max(0L, java.time.Duration.between(lastHeartbeatAcceptedAt, Instant.now()).toMillis());
            long lastProjectionAgeMillis = lastProjectionAcknowledgedNanos == 0L ? -1
                : Math.max(0L, (System.nanoTime() - lastProjectionAcknowledgedNanos) / 1_000_000L);
            logger.info(
                "Helix player-action delivery health: projection_pending={} projection_flush_pending={} " +
                "pending_count={} latest_ack_sequence={} last_projection_ack_age_ms={} heartbeat_age_ms={} " +
                "heartbeat_queued={}",
                !deliveryOutbox.isProjectionEmpty(), projectionDeliveryFlushPending.get(),
                deliveryOutbox.size(), latestAcknowledgedEventSequence, lastProjectionAgeMillis,
                heartbeatAgeMillis, heartbeatPublishPending.get()
            );
        }
        if (!config.transportReady() || http == null) return;
        if (!connectedIdentityMatches()) {
            if (controller.activeWorkflowId() != null) {
                controller.emergencyStop("The exact paired Minecraft player is no longer connected.");
                bridge.releaseAll();
            }
            return;
        }
        if (!manifestReady && ticks % POLL_INTERVAL_TICKS == 0 && manifestPending.compareAndSet(false, true)) {
            network.execute(() -> {
                try {
                    publishManifest();
                } finally {
                    manifestPending.set(false);
                }
            });
        }
        if (heartbeatPublicationDue(ticks, manifestReady)) scheduleHeartbeatPublish();
        if (eventStreamResyncRequired) return;
        if (
            actionPollingReady(manifestReady, heartbeatReady, credentialLeaseCurrent()) &&
            actionPollDue(ticks, activeEnvelope != null && temporalPollDeferred.get(), temporalEvidencePending()) &&
            cyclePending.compareAndSet(false, true)
        ) {
            temporalPollDeferred.set(false);
            network.execute(() -> {
                try {
                    pollControlsThenActions();
                } finally {
                    cyclePending.set(false);
                }
            });
        }
    }

    void renderFrame(long frameNanos) {
        ActiveEnvelope frameEnvelope = activeEnvelope;
        long startedNanos = System.nanoTime();
        residentCallbackInProgress = frameEnvelope != null;
        try {
            controller.renderFrame(frameNanos);
        } finally {
            if (frameEnvelope != null) {
                frameEnvelope.capacityTelemetry().recordResidentComputation(
                    Math.max(0L, System.nanoTime() - startedNanos)
                );
                residentCallbackInProgress = false;
                flushDeferredWorkflowEvents();
            }
        }
    }

    boolean ready() {
        return config.transportReady() && credentialLeaseCurrent() && manifestReady && heartbeatReady &&
            !emergencyStopLatched && !eventStreamResyncRequired;
    }

    private boolean credentialLeaseCurrent() {
        Instant expiry = credentialExpiresAt;
        return expiry != null && Instant.now().isBefore(expiry);
    }

    String statusText() {
        if (!config.transportReady()) return "Helix player embodiment is not paired.";
        if (!credentialLeaseCurrent()) return "Helix player embodiment is waiting for a renewed server lease.";
        if (emergencyStopLatched) return "Helix player embodiment is emergency-stopped; pair a fresh authority to resume.";
        if (eventStreamResyncRequired) {
            return "Helix player embodiment needs a fresh pairing because its evidence stream no longer matches the server epoch.";
        }
        if (!manifestReady) return "Helix player embodiment is paired and waiting for manifest admission.";
        if (!heartbeatReady) return "Helix player embodiment is paired and waiting for its first admitted heartbeat.";
        String workflow = controller.activeWorkflowId();
        return workflow == null
            ? "Helix player embodiment is active and idle."
            : "Helix player embodiment is running workflow " + workflow + ".";
    }

    String localDiagnosticStatusText() {
        LocalDiagnosticEnvelope diagnostic = localDiagnosticEnvelope;
        if (diagnostic == null) return "Helix direct diagnostics are idle.";
        return "Helix direct diagnostic workflow " + diagnostic.workflowId() +
            " is running action " + diagnostic.actionKind() + ".";
    }

    String armLocalViabilityGuardian(long durationSeconds) {
        if (minecraft.player == null || minecraft.getConnection() == null) {
            return "The resident Minecraft guardian requires an active world connection.";
        }
        if (emergencyStopLatched) {
            return "The resident Minecraft guardian cannot arm while Emergency Stop is latched.";
        }
        long boundedSeconds = Math.max(10, Math.min(1_800, durationSeconds));
        remoteViabilityGuardianArmed = false;
        bridge.armViabilityGuardian(ticks, boundedSeconds * 20L);
        lastViabilityNotice = "";
        lastViabilityEventReason = "";
        return "The deterministic resident Minecraft guardian is armed for " +
            boundedSeconds + " seconds with local water/air stabilization and fail-closed hazard escalation.";
    }

    String disarmLocalViabilityGuardian() {
        remoteViabilityGuardianArmed = false;
        bridge.disarmViabilityGuardian();
        lastViabilityNotice = "";
        lastViabilityEventReason = "";
        return "The resident Minecraft guardian is disarmed and every guardian-owned control is released.";
    }

    String localViabilityGuardianStatusText() {
        MinecraftViabilityGuardian.Decision decision = latestViabilityDecision;
        if (!bridge.viabilityGuardianArmed()) return "The resident Minecraft guardian is not armed.";
        if (decision == null) return "The resident Minecraft guardian is armed and awaiting its first observation.";
        return "The resident Minecraft guardian is armed; latest decision " +
            decision.reasonCode() + " from observation revision " +
            decision.observationRevision() + ".";
    }

    private boolean tickViabilityGuardian() {
        if (!bridge.viabilityGuardianArmed()) {
            remoteViabilityGuardianArmed = false;
            return false;
        }
        ActiveEnvelope envelope = activeEnvelope;
        if (
            envelope != null &&
            "arm_viability_guardian".equals(text(envelope.wire(), "action_kind"))
        ) return false;
        java.util.Set<String> residentCoverage = activeResidentGuardianCoverage();
        MinecraftViabilityGuardian.Decision decision = bridge.observeViability(
            ticks,
            emergencyStopLatched,
            residentCoverage.contains(
                ConcurrentReactiveScheduler.COVERAGE_UNSAFE_LANDING
            ),
            residentCoverage.contains(ConcurrentReactiveScheduler.COVERAGE_FIRE)
        );
        latestViabilityDecision = decision;
        if (!bridge.viabilityGuardianArmed()) remoteViabilityGuardianArmed = false;
        boolean ownsTick = decision.proposal() ==
            MinecraftViabilityGuardian.ProposalKind.SWIM_UP;
        boolean delegatedRecovery = decision.proposal() ==
            MinecraftViabilityGuardian.ProposalKind.MONITOR_ADMITTED_RECOVERY;
        if (
            decision.proposal() != MinecraftViabilityGuardian.ProposalKind.NONE &&
            !delegatedRecovery
        ) {
            String workflowId = controller.activeWorkflowId();
            if (workflowId != null) {
                controller.cancel(
                    workflowId,
                    "The resident viability guardian interrupted the workflow: " +
                        decision.reasonCode()
                );
            }
            bridge.applyViabilityDecision(decision);
        }
        if (
            !decision.reasonCode().equals(lastViabilityEventReason) &&
            (!"viability_within_profile".equals(decision.reasonCode()) ||
                !lastViabilityEventReason.isBlank())
        ) {
            lastViabilityEventReason = decision.reasonCode();
            recordViabilityDecision(decision);
        }
        if (
            decision.semanticEscalationRequired() &&
            !decision.reasonCode().equals(lastViabilityNotice)
        ) {
            lastViabilityNotice = decision.reasonCode();
            localDiagnosticMessage.accept(
                "Resident guardian event: " + decision.reasonCode() +
                    ". Controls released=" + decision.controlsMustRelease() + "."
            );
        }
        return ownsTick;
    }

    private java.util.Set<String> activeResidentGuardianCoverage() {
        String actionKind = "";
        Map<String, Object> arguments = Map.of();
        ActiveEnvelope envelope = activeEnvelope;
        if (envelope != null) {
            actionKind = text(envelope.wire(), "action_kind");
            arguments = object(envelope.wire().get("arguments"));
        } else {
            LocalDiagnosticEnvelope diagnostic = localDiagnosticEnvelope;
            if (diagnostic != null) {
                actionKind = diagnostic.actionKind();
                arguments = diagnostic.arguments();
            }
        }
        if (!"execute_reactive_program".equals(actionKind)) return java.util.Set.of();
        return ConcurrentReactiveScheduler.residentGuardianCoverage(arguments);
    }

    private void recordViabilityDecision(
        MinecraftViabilityGuardian.Decision decision
    ) {
        Map<String, Object> record = baseNonAnswer();
        record.put("schema", "helix.minecraft.resident_decision.v1");
        record.put("profile_id", MinecraftViabilityGuardian.PROFILE_ID);
        record.put("artifact_version", MinecraftViabilityGuardian.ARTIFACT_VERSION);
        record.put("decision_sequence", decision.decisionSequence());
        record.put("observation_revision", decision.observationRevision());
        record.put("proposal", decision.proposal().name().toLowerCase(java.util.Locale.ROOT));
        record.put("reason_code", decision.reasonCode());
        record.put("arbiter_outcome", residentArbiterOutcome(decision));
        record.put("bounded_effect", residentBoundedEffect(decision));
        record.put("effect_applied", residentEffectApplied(decision));
        record.put("postcondition_status", residentPostconditionStatus(decision));
        record.put("controls_released", decision.controlsMustRelease());
        record.put("semantic_escalation_required", decision.semanticEscalationRequired());
        record.put("measurements", decision.measurements());
        record.put("clock", clockSnapshot());
        record.put("created_at", Instant.now().toString());
        logger.info("HELIX_MINECRAFT_RESIDENT_DECISION {}", HelixJson.stringify(record));

        if (!ready() || http == null) return;
        long sequence = ++latestEventSequence;
        Map<String, Object> batch = residentEnvironmentEventBatch(decision, sequence);
        if (!deliveryOutbox.enqueueSequence(List.of(
            new PlayerActionDeliveryOutbox.Delivery(
                PlayerActionDeliveryOutbox.Stage.ENVIRONMENT_EVENT_BATCH,
                batch
            )
        ), RESERVED_TERMINAL_DELIVERIES)) {
            recordTransportError("resident_event_delivery_outbox_full");
            bridge.disarmViabilityGuardian();
            bridge.releaseAll();
            return;
        }
        scheduleDeliveryFlush();
    }

    String startLocalDiagnostic(
        String actionKind,
        Map<String, Object> arguments,
        long maxDurationTicks
    ) {
        return startLocalDiagnostic(
            actionKind,
            arguments,
            maxDurationTicks,
            "native_fabric",
            null
        );
    }

    String startLocalDiagnostic(
        String actionKind,
        Map<String, Object> arguments,
        long maxDurationTicks,
        String controlEngine
    ) {
        return startLocalDiagnostic(
            actionKind,
            arguments,
            maxDurationTicks,
            controlEngine,
            null
        );
    }

    String startLocalDiagnostic(
        String actionKind,
        Map<String, Object> arguments,
        long maxDurationTicks,
        String controlEngine,
        String stagingRequestRef
    ) {
        if (minecraft.player == null || minecraft.getConnection() == null) {
            return "Helix direct diagnostics require an active Minecraft world connection.";
        }
        if (emergencyStopLatched) {
            return "Helix direct diagnostics are unavailable while the local emergency stop is latched.";
        }
        if (activeEnvelope != null || localDiagnosticEnvelope != null ||
            controller.activeWorkflowId() != null) {
            return "Helix cannot start a direct diagnostic while another player workflow is active.";
        }
        if (!directDiagnosticActionAllowed(actionKind)) {
            return "Helix direct diagnostics do not expose that action kind.";
        }
        if (!List.of("native_fabric", "baritone").contains(controlEngine)) {
            return "Helix direct diagnostics do not expose that control engine.";
        }
        if ("baritone".equals(controlEngine) && !"navigate_to".equals(actionKind)) {
            return "Helix direct diagnostics expose Baritone only for navigation.";
        }
        String actionRequestId = id("direct_player_action_request");
        String workflowId = id("direct_player_action_workflow");
        Map<String, Object> executionArguments = new LinkedHashMap<>(arguments);
        if ("arm_viability_guardian".equals(actionKind)) {
            executionArguments.put(
                "lease_expires_tick",
                ticks + Math.max(200, number(executionArguments, "duration_ticks").longValue())
            );
        }
        PlayerSnapshot initialSnapshot = bridge.snapshot();
        Map<String, Object> startingState = new LinkedHashMap<>();
        startingState.put("connected", initialSnapshot.connected());
        startingState.put("x", initialSnapshot.x());
        startingState.put("y", initialSnapshot.y());
        startingState.put("z", initialSnapshot.z());
        startingState.put("yaw", initialSnapshot.yaw());
        startingState.put("pitch", initialSnapshot.pitch());
        startingState.put("health", initialSnapshot.health());
        startingState.put("on_ground", initialSnapshot.onGround());
        startingState.putAll(bridge.compactFluidState());
        LocalDiagnosticEnvelope diagnostic = new LocalDiagnosticEnvelope(
            actionRequestId,
            workflowId,
            actionKind,
            Map.copyOf(executionArguments),
            controlEngine,
            stagingRequestRef,
            Map.copyOf(startingState),
            Instant.now().toString(),
            clockSnapshot()
        );
        localDiagnosticEnvelope = diagnostic;
        ActionRequest request = new ActionRequest(
            actionRequestId,
            workflowId,
            actionKind,
            executionArguments,
            Math.max(1, Math.min(36_000, maxDurationTicks)),
            ManualOverridePolicy.CANCEL,
            controlEngine
        );
        if (!controller.start(request)) {
            localDiagnosticEnvelope = null;
            return "Helix could not start the direct diagnostic because the player controller is busy.";
        }
        if ("arm_viability_guardian".equals(actionKind) ||
            "disarm_viability_guardian".equals(actionKind)) remoteViabilityGuardianArmed = false;
        Map<String, Object> record = localDiagnosticBase(diagnostic);
        record.put("schema", "helix.minecraft.player.direct_diagnostic_request.v1");
        record.put("event_type", "diagnostic.requested");
        record.put("max_duration_ticks", request.maxDurationTicks());
        logger.info("HELIX_PLAYER_DIRECT_DIAGNOSTIC {}", HelixJson.stringify(record));
        return "Helix started direct diagnostic " + workflowId + " for " + actionKind + ".";
    }

    String cancelLocalDiagnostic() {
        LocalDiagnosticEnvelope diagnostic = localDiagnosticEnvelope;
        if (diagnostic == null) return "No Helix direct diagnostic is running.";
        if (!controller.cancel(
            diagnostic.workflowId(),
            "The local operator canceled the direct diagnostic."
        )) {
            return "The Helix direct diagnostic was no longer running.";
        }
        return "Helix canceled the direct diagnostic and released every asserted control.";
    }

    void localEmergencyStop(String reason) {
        emergencyStopLatched = true;
        remoteViabilityGuardianArmed = false;
        bridge.disarmViabilityGuardian();
        controller.emergencyStop(reason);
        bridge.releaseAll();
        if (http != null) scheduleHeartbeatPublish();
    }

    PlayerActionController controllerForIntegration() {
        return controller;
    }

    private boolean connectedIdentityMatches() {
        return minecraft.player != null &&
            minecraft.getConnection() != null &&
            minecraft.player.getUUID().toString().equalsIgnoreCase(config.subjectNativeId());
    }

    private void publishManifest() {
        try {
            PlayerActionHttpClient.Response response = http.post("/manifest", manifest());
            manifestReady = response.ok();
            if (!response.ok()) {
                heartbeatReady = false;
                recordTransportError(response.error());
            } else {
                clearTransportErrorIfDeliveryComplete();
                // Establish a fresh active heartbeat before the first work poll.
                // Otherwise the broker correctly rejects the poll as stale and
                // the transient bootstrap error can become self-sustaining.
                scheduleHeartbeatPublish();
            }
        } catch (Exception error) {
            manifestReady = false;
            heartbeatReady = false;
            recordTransportError("manifest_unreachable");
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    private void publishHeartbeat() {
        try {
            // Read resident state on its owning thread; only transport the
            // resulting snapshot here. Never sample the mutable bridge from
            // the projection worker or move network I/O onto the client tick.
            Map<String, Object> snapshot = runOnClient(this::heartbeat);
            long sentAcknowledgedEventSequence = snapshot.get("latest_event_sequence") instanceof Number sequence
                ? sequence.longValue() : -1L;
            long requestStartedNanos = System.nanoTime();
            PlayerActionHttpClient.Response response = http.post("/heartbeat", snapshot);
            long requestMillis = Math.max(0L, (System.nanoTime() - requestStartedNanos) / 1_000_000L);
            if (requestMillis >= SLOW_DELIVERY_MILLIS) {
                logger.warn("Helix player-action heartbeat response took {} ms with HTTP {}",
                    requestMillis, response.statusCode());
            }
            boolean validLeaseReceipt = true;
            if (response.ok() && response.body().get("credential_expires_at") instanceof String expiryText) {
                try {
                    credentialExpiresAt = Instant.parse(expiryText);
                } catch (RuntimeException ignored) {
                    validLeaseReceipt = false;
                    recordTransportError("heartbeat_expiry_invalid");
                }
            }
            heartbeatReady = response.ok() && validLeaseReceipt;
            workflowEventBatchSupported = response.ok() &&
                Boolean.TRUE.equals(response.body().get("workflow_event_batch_supported"));
            if (heartbeatReady) lastHeartbeatAcceptedAt = Instant.now();
            if (!heartbeatReady) {
                if (response.ok()) return;
                recordTransportError(response.error());
                if (heartbeatFailureRequiresManifestRepublish(response.error())) {
                    // A deferred/local server restore can legitimately forget
                    // the most recently admitted manifest while this connector
                    // remains alive. Re-enter manifest admission before any
                    // further action poll; never replay an action.
                    manifestReady = false;
                } else if (requiresFreshProducerEpoch(response.error())) {
                    if (heartbeatCursorConflictCanReconcile(
                        response.error(), !deliveryOutbox.isProjectionEmpty(),
                        sentAcknowledgedEventSequence, latestAcknowledgedEventSequence
                    )) {
                        // A projection may have committed while its receipt was
                        // delayed, or advanced after this heartbeat snapshot.
                        // Release controls, then retry the identical queued
                        // evidence. A real projection conflict still latches in
                        // flushDeliveryLane; never rotate across pending data.
                        bridge.releaseAll();
                        recordTransportError("heartbeat_event_cursor_reconciling");
                        scheduleProjectionDeliveryFlush();
                        clearTransportErrorIfDeliveryComplete();
                    } else if (rotateEvidenceEpochIfIdle()) {
                        publishManifest();
                    } else {
                        eventStreamResyncRequired = true;
                        bridge.releaseAll();
                    }
                }
            } else clearTransportErrorIfDeliveryComplete();
        } catch (Exception error) {
            heartbeatReady = false;
            recordTransportError("heartbeat_unreachable");
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    static boolean heartbeatPublicationDue(long tick, boolean manifestReady) {
        return manifestReady && tick > 0 && tick % HEARTBEAT_INTERVAL_TICKS == 0;
    }

    static boolean deliveryRetryDue(long tick, boolean evidencePending) {
        return evidencePending && tick > 0 && tick % (POLL_INTERVAL_TICKS * 5) == 0;
    }

    static boolean actionDispatchBlocked(
        boolean emergencyStopLatched,
        boolean localDiagnosticActive,
        boolean criticalEvidencePending
    ) {
        return emergencyStopLatched || localDiagnosticActive || criticalEvidencePending;
    }

    private void scheduleHeartbeatPublish() {
        if (!heartbeatPublishPending.compareAndSet(false, true)) return;
        long queuedAtNanos = System.nanoTime();
        // Heartbeats and environment projection share one executor so the
        // advertised cursor cannot race the interval after the server commits
        // a batch but before this client processes that batch's acknowledgement.
        // Critical workflow events and action results retain their independent
        // deadline-sensitive lane.
        projectionDeliveryNetwork.execute(() -> {
            try {
                long queueDelayMillis = Math.max(0L, (System.nanoTime() - queuedAtNanos) / 1_000_000L);
                if (queueDelayMillis >= SLOW_DELIVERY_MILLIS) {
                    logger.warn("Helix player-action heartbeat queued behind projection for {} ms", queueDelayMillis);
                }
                publishHeartbeat();
            } finally {
                heartbeatPublishPending.set(false);
            }
        });
    }

    private void publishLocalStatus() {
        String workflow = controller.activeWorkflowId();
        State state = controller.state();
        boolean running = workflow != null && state == State.RUNNING;
        boolean criticalDeliveryPending = !deliveryOutbox.isCriticalEmpty();
        localStatusWriter.write(
            config.actionAuthorityId(),
            producerEpochRef,
            ready() && !actionDispatchBlocked(
                emergencyStopLatched, localDiagnosticEnvelope != null, criticalDeliveryPending
            ),
            lastHeartbeatAcceptedAt,
            workflow,
            wireState(state),
            running && !emergencyStopLatched && !eventStreamResyncRequired,
            emergencyStopLatched,
            manualInputDetected,
            criticalDeliveryPending,
            Instant.now()
        );
    }

    private boolean rotateEvidenceEpochIfIdle() {
        if (!evidenceEpochRotationAllowed(
            controller.activeWorkflowId() != null,
            activeEnvelope != null,
            !deliveryOutbox.isEmpty()
        )) return false;
        producerEpochRef = id("environment_action_epoch");
        manifestId = id("environment_action_manifest");
        latestEventSequence = -1;
        latestAcknowledgedEventSequence = -1;
        lastProjectionAcknowledgedNanos = 0L;
        manifestReady = false;
        heartbeatReady = false;
        eventStreamResyncRequired = false;
        logger.warn(
            "Helix player-action evidence cursor changed across server persistence; publishing a fresh producer epoch without replaying an action."
        );
        return true;
    }

    private void pollControlsThenActions() {
        String pollStage = "controls_pending";
        long pollStartedNanos = System.nanoTime();
        try {
            long requestStartedNanos = System.nanoTime();
            PlayerActionHttpClient.Response controls = http.get("/controls/pending?limit=4");
            warnSlowPendingPoll("controls", requestStartedNanos);
            if (!controls.ok()) {
                recordTransportError(controls.error());
                return;
            }
            for (Object value : HelixJson.asList(controls.body().get("control_requests"))) {
                pollStage = "control_apply";
                Map<String, Object> control = HelixJson.asObject(value);
                Map<String, Object> result = runOnClient(() -> applyControl(control));
                pollStage = "control_result";
                PlayerActionHttpClient.Response submitted = http.post("/controls/result", result);
                if (!submitted.ok()) {
                    recordTransportError(submitted.error());
                    return;
                }
            }
            pollStage = "local_dispatch_gate";
            scheduleDeliveryFlush();
            if (activeEnvelope != null && !deliveryOutbox.isEmpty()) temporalPollDeferred.set(true);
            if (
                emergencyStopLatched ||
                localDiagnosticEnvelope != null
            ) return;
            if (activeEnvelope != null) {
                // Keep control polling independent of a slow/uncertain successor
                // response. The delivery lane stays single-flight and applies
                // responses only through the existing client-thread guards.
                temporalDeliveryLane.submit(() -> {
                    try { pollTemporalSuccessor(); }
                    catch (Exception error) {
                        recordTransportError("temporal_successor_transport_unreachable");
                        if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                    }
                });
                return;
            }
            if (actionDispatchBlocked(
                emergencyStopLatched,
                localDiagnosticEnvelope != null,
                !deliveryOutbox.isCriticalEmpty()
            )) return;
            pollStage = "actions_pending";
            requestStartedNanos = System.nanoTime();
            PlayerActionHttpClient.Response actions = http.get("/requests/pending?limit=1");
            warnSlowPendingPoll("actions", requestStartedNanos);
            if (!actions.ok()) {
                recordTransportError(actions.error());
                return;
            }
            List<Object> requests = HelixJson.asList(actions.body().get("action_requests"));
            if (!requests.isEmpty()) {
                Map<String, Object> request = HelixJson.asObject(requests.get(0));
                Map<String, Object> queueObservation = object(
                    actions.body().get("queue_observation")
                );
                long queueDepthAtLease = nonnegativeLong(
                    queueObservation,
                    "queue_depth_at_lease",
                    requests.size()
                );
                long oldestPendingAgeMs = nonnegativeLong(
                    queueObservation,
                    "oldest_pending_age_ms",
                    0L
                );
                long dispatchReceivedNanos = System.nanoTime();
                // A successful leased-work poll is the control-plane contact
                // that authorizes this exact local action to begin. Do not let
                // an older, already-recovered transport error cancel it.
                clearTransportError();
                pollStage = "action_accept";
                runOnClient(() -> {
                    accept(
                        request,
                        dispatchReceivedNanos,
                        queueDepthAtLease,
                        oldestPendingAgeMs
                    );
                    return Boolean.TRUE;
                });
            }
            clearTransportErrorIfDeliveryComplete();
        } catch (Exception error) {
            long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - pollStartedNanos
            );
            logger.warn("Helix player-action poll failed at " + pollStage +
                " after " + elapsedMs + "ms; reconcile the exact workflow before any retry.");
            recordTransportError("action_poll_" + pollStage + "_unreachable");
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    private void warnSlowPendingPoll(String lane, long startedNanos) {
        long elapsedMs = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
            System.nanoTime() - startedNanos
        );
        if (elapsedMs >= 5_000) {
            logger.warn("Helix player-action " + lane + " pending GET took " + elapsedMs +
                "ms; compare with the desktop broker's phase timings before attributing the delay.");
        }
    }

    private void accept(
        Map<String, Object> wire,
        long dispatchReceivedNanos,
        long queueDepthAtLease,
        long oldestPendingAgeMs
    ) {
        TemporalPlanDeliveryPreflight.SerialInitial temporal = null;
        if (hasTemporalMetadata(wire)) {
            // Source/compiled identity and serial stabilization must bind before
            // an envelope or controller is created. Never use finite fallback.
            try {
                if (!bridge.inventoryCountObservation().available())
                    throw new IllegalStateException("temporal_inventory_observation_unavailable");
                Map<String, Object> clock = monotonicClock.snapshot();
                temporal = TemporalPlanDeliveryPreflight.initialSerial(wire, config, producerEpochRef,
                    textRequired(clock, "origin_id"), ticks, number(clock, "elapsed_ms").doubleValue());
            } catch (RuntimeException invalidTemporalDelivery) {
                submitWithoutExecution(wire, "capability_unavailable",
                    "Temporal delivery failed source, compilation, paired identity, clock or inventory-sensor validation; no execution started.");
                return;
            }
        }
        String requestedEngine = text(wire, "requested_control_engine");
        String resolvedEngine = "baritone".equals(requestedEngine)
            ? "baritone"
            : "native_fabric";
        if (!bridge.supportsControlEngine(resolvedEngine)) {
            submitWithoutExecution(wire, "control_engine_unavailable", "The requested control engine is not installed in this Fabric client.");
            return;
        }
        try {
            Map<String, Object> constraints = object(wire.get("constraints"));
            long maxDurationMs = number(constraints, "max_duration_ms").longValue();
            Map<String, Object> arguments = object(wire.get("arguments"));
            if ("arm_viability_guardian".equals(text(wire, "action_kind"))) {
                arguments.put(
                    "lease_expires_tick",
                    ticks + Math.max(200, number(arguments, "duration_ticks").longValue())
                );
            }
            ActionRequest request = new ActionRequest(
                textRequired(wire, "action_request_id"),
                textRequired(wire, "workflow_id"),
                textRequired(wire, "action_kind"),
                arguments,
                Math.max(1, Math.min(36_000, (maxDurationMs + 49) / 50)),
                ManualOverridePolicy.fromWire(text(constraints, "manual_override_policy")),
                resolvedEngine
            );
            ActiveEnvelope envelope = new ActiveEnvelope(
                new LinkedHashMap<>(wire),
                id("environment_action_execution"),
                Instant.now().toString(),
                clockSnapshot(),
                resolvedEngine,
                new ArrayList<>(),
                new EnvironmentCapacityTelemetry(
                    dispatchReceivedNanos,
                    System.nanoTime(),
                    queueDepthAtLease,
                    oldestPendingAgeMs,
                    request.maxDurationTicks()
                )
            );
            activeEnvelope = envelope;
            temporalDeliveryState = temporal == null ? null : new TemporalDeliveryState(envelope.wire());
            if (!controller.start(request)) {
                activeEnvelope = null;
                temporalDeliveryState = null;
                submitWithoutExecution(wire, "duplicate_request", "Another player workflow is already active.");
            } else {
                if ("arm_viability_guardian".equals(request.actionKind())) remoteViabilityGuardianArmed = true;
                if ("disarm_viability_guardian".equals(request.actionKind())) remoteViabilityGuardianArmed = false;
            }
            if (activeEnvelope != null && temporal != null && !controller.attachTemporalWindow(request.actionRequestId(), temporal.clock().window(),
                () -> {
                    Map<String, Object> clock = monotonicClock.snapshot();
                    return new PlayerActionController.TemporalClockSample(textRequired(clock, "origin_id"), ticks,
                        number(clock, "elapsed_ms").doubleValue());
                }, temporal.stabilizationNode())) {
                controller.cancel(request.workflowId(), "The admitted temporal window could not attach before execution.");
            }
        } catch (RuntimeException error) {
            activeEnvelope = null;
            temporalDeliveryState = null;
            submitWithoutExecution(wire, "capability_unavailable", "The admitted player request could not be interpreted by this connector version.");
        }
    }

    static boolean hasTemporalMetadata(Map<String, Object> wire) {
        // Orphaned and null metadata must also enter strict temporal preflight.
        return wire.containsKey("temporal_plan") || wire.containsKey("temporal_plan_canonical_json") ||
            wire.containsKey("temporal_compilation_canonical_json") || wire.containsKey("temporal_compilation_hash");
    }

    private boolean temporalEvidencePending() {
        TemporalDeliveryState state = temporalDeliveryState;
        return state == null || deliveryOutbox.hasPendingThrough(state.checkpointDeliveryWatermark);
    }

    private void pollTemporalSuccessor() throws Exception {
        TemporalPoll uncertain = runOnClient(() -> temporalDeliveryState == null ? null : temporalDeliveryState.uncertainPoll);
        if (uncertain != null) {
            // A lost response may have consumed the one-shot lease. Inspect
            // only: neither absence nor a recorded status authorizes replay.
            PlayerActionHttpClient.Response status;
            long started = System.nanoTime();
            try {
                status = http.post("/requests/temporal-successor/status", uncertain.body());
            } finally {
                uncertain.envelope().capacityTelemetry().recordTemporalRequest(true, System.nanoTime() - started);
            }
            recordTransportError("temporal_successor_delivery_" + reconciliationStatus(status));
            return;
        }
        TemporalPoll poll = runOnClient(() -> {
            applyReceivedTemporalSuccessor();
            ActiveEnvelope envelope = activeEnvelope;
            TemporalDeliveryState state = temporalDeliveryState;
            if (envelope != null && state != null && !deliveryOutbox.isEmpty()) temporalPollDeferred.set(true);
            if (envelope == null || state == null || state.queuedWire != null || state.received.pending() ||
                !temporalDeliveryReady(controller.state(), eventStreamResyncRequired, emergencyStopLatched, temporalEvidencePending())) return null;
            List<Object> settlements = HelixJson.asList(state.measurements.get("checkpoint_settlements"));
            if (settlements.isEmpty()) return null;
            String checkpoint = text(HelixJson.asObject(settlements.get(settlements.size() - 1)), "checkpoint_id");
            Map<String, Object> plan = object(state.currentWire.get("temporal_plan"));
            boolean explicitCheckpoint = HelixJson.asList(plan.get("nodes")).stream().anyMatch(value -> {
                Map<String, Object> node = HelixJson.asObject(value);
                return "checkpoint".equals(node.get("kind")) && checkpoint.equals(node.get("checkpoint_id"));
            });
            if (!explicitCheckpoint) return null;
            // Client-thread observation after BOTH evidence lanes clear the
            // fixed fence. Includes scheduling delay; not the HTTP receipt time.
            envelope.capacityTelemetry().recordCheckpointEvidenceReady(
                HelixJson.stringify(List.of(text(plan, "plan_id"), checkpoint)), System.nanoTime());
            TemporalPoll prepared = new TemporalPoll(envelope, state.currentWire, state.interruptionRevision, checkpoint, Map.of(
                "resident_action_request_id", textRequired(envelope.wire(), "action_request_id"),
                "predecessor_plan_id", textRequired(plan, "plan_id"), "predecessor_plan_hash", textRequired(plan, "plan_hash"),
                "checkpoint_id", checkpoint));
            // Arm before crossing the network boundary. A later client-thread
            // timeout must not lose the record that a one-shot lease may exist.
            state.uncertainPoll = prepared;
            return prepared;
        });
        if (poll == null) return;
        PlayerActionHttpClient.Response response;
        long responseReceived;
        long pollStarted = System.nanoTime();
        try {
            try {
                response = http.post("/requests/temporal-successor", poll.body());
                responseReceived = System.nanoTime();
            } finally {
                poll.envelope().capacityTelemetry().recordTemporalRequest(false, System.nanoTime() - pollStarted);
            }
        } catch (java.io.IOException | InterruptedException failure) {
            retainUncertainTemporalPoll(poll);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return;
        }
        if (temporalResponseUncertain(response)) { retainUncertainTemporalPoll(poll); return; }
        if (!(response.body().get("action_request") instanceof Map<?, ?>)) {
            // Only an explicit empty response permits another delivery poll.
            runOnClient(() -> {
                TemporalDeliveryState state = temporalDeliveryState;
                if (state != null && state.uncertainPoll == poll) state.uncertainPoll = null;
                return false;
            });
            return;
        }
        Map<String, Object> successor = HelixJson.asObject(response.body().get("action_request"));
        poll.envelope().capacityTelemetry().recordTemporalDeliveryResponse(
            HelixJson.stringify(List.of(text(poll.body(), "predecessor_plan_id"), poll.checkpoint())),
            pollStarted, responseReceived);
        runOnClient(() -> {
            TemporalDeliveryState state = temporalDeliveryState;
            if (activeEnvelope != poll.envelope() || state == null || state.currentWire != poll.wire() ||
                state.interruptionRevision != poll.interruptionRevision() ||
                state.queuedWire != null ||
                !temporalDeliveryReady(controller.state(), eventStreamResyncRequired, emergencyStopLatched, false)) return false;
            if (!state.received.offer(new TemporalReceived(poll, successor))) return false;
            // The exact response is now retained on the client thread. Until
            // this handoff succeeds, subsequent attempts remain status-only.
            if (state.uncertainPoll == poll) state.uncertainPoll = null;
            return applyReceivedTemporalSuccessor();
        });
    }

    private void retainUncertainTemporalPoll(TemporalPoll poll) throws Exception {
        runOnClient(() -> {
            TemporalDeliveryState state = temporalDeliveryState;
            if (activeEnvelope == poll.envelope() && state != null && state.currentWire == poll.wire()) {
                state.uncertainPoll = poll;
            }
            return false;
        });
        recordTransportError("temporal_successor_delivery_unresolved");
    }

    static String reconciliationStatus(PlayerActionHttpClient.Response response) {
        if (!response.ok()) return "unresolved";
        String status = text(object(response.body().get("delivery_state")), "recorded_status");
        return switch (status) {
            case "queued", "admitted", "leased", "running", "succeeded", "failed", "canceled",
                 "timed_out", "emergency_stopped", "connector_offline", "authority_stale" -> "recorded_" + status;
            default -> "unresolved";
        };
    }

    static boolean temporalResponseUncertain(PlayerActionHttpClient.Response response) {
        if (!response.ok() || !response.body().containsKey("action_request")) return true;
        Object action = response.body().get("action_request");
        // Only the explicit null variant proves an empty delivery response.
        // A missing/malformed payload may follow a consumed one-shot lease.
        return action != null && !(action instanceof Map<?, ?>);
    }

    private boolean applyReceivedTemporalSuccessor() {
            TemporalDeliveryState state = temporalDeliveryState;
            if (state == null) return false;
            TemporalReceived received = state.received.take(activeEnvelope != null && state.queuedWire == null &&
                temporalDeliveryReady(controller.state(), eventStreamResyncRequired, emergencyStopLatched, false), temporalEvidencePending());
            if (received == null) return false;
            TemporalPoll poll = received.poll();
            Map<String, Object> successor = received.wire();
            if (activeEnvelope != poll.envelope() || state.currentWire != poll.wire() ||
                state.interruptionRevision != poll.interruptionRevision()) return false;
            try {
                Map<String, Object> clock = monotonicClock.snapshot();
                var prepared = TemporalPlanDeliveryPreflight.successorSerial(successor, state.currentWire, config,
                    producerEpochRef, textRequired(clock, "origin_id"), ticks, number(clock, "elapsed_ms").doubleValue());
                state.acceptingSuccessorActionRequestId = textRequired(successor, "action_request_id");
                if (!controller.queueTemporalSuccessor(textRequired(poll.envelope().wire(), "action_request_id"),
                    textRequired(object(state.currentWire.get("temporal_plan")), "plan_id"), poll.checkpoint(),
                    object(successor.get("arguments")), prepared.clock().window(), prepared.stabilizationNode())) throw new IllegalArgumentException();
                state.queuedWire = successor;
                state.queuedCheckpoint = poll.checkpoint();
                return true;
            } catch (RuntimeException invalid) {
                controller.cancel(textRequired(poll.envelope().wire(), "workflow_id"),
                    "Temporal successor delivery was incompatible or late; it was not executed.");
                return false;
            } finally {
                state.acceptingSuccessorActionRequestId = null;
            }
    }

    private Map<String, Object> applyControl(Map<String, Object> control) {
        String kind = text(control, "control_kind");
        String workflowId = nullableText(control.get("workflow_id"));
        boolean completed;
        boolean release = Boolean.TRUE.equals(control.get("release_all_controls"));
        if ("emergency_stop".equals(kind)) {
            emergencyStopLatched = true;
            completed = controller.emergencyStop(text(control, "reason"));
            bridge.releaseAll();
            release = true;
        } else if ("cancel".equals(kind)) {
            completed = workflowId != null && controller.cancel(workflowId, text(control, "reason"));
            bridge.releaseAll();
            release = true;
        } else if ("resume".equals(kind)) {
            completed = workflowId != null && controller.resume(workflowId);
        } else {
            completed = workflowId != null && workflowId.equals(controller.activeWorkflowId());
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "helix.environment_action.control_result.v1");
        result.put("control_request_id", textRequired(control, "control_request_id"));
        result.put("control_kind", kind);
        result.put("outcome", completed ? "completed" : "not_running");
        result.put("summary", completed
            ? "The Fabric client applied the requested workflow control."
            : "The exact workflow was not running in this Fabric client.");
        result.put("affected_workflow_ids", completed && workflowId != null ? List.of(workflowId) : List.of());
        result.put("workflow_state", completed ? wireState(controller.state()) : null);
        result.put("controls_released", release);
        result.put("evidence_refs", List.of());
        result.put("completed_at", Instant.now().toString());
        result.put("host_access_performed", false);
        result.put("model_invoked", false);
        result.put("assistant_answer", false);
        result.put("raw_content_included", false);
        return result;
    }

    static boolean eventMatchesEnvelope(WorkflowEvent event, Map<String, Object> wire) {
        return event.actionRequestId() != null && !event.actionRequestId().isBlank() &&
            event.actionRequestId().equals(wire.get("action_request_id")) &&
            event.workflowId() != null && !event.workflowId().isBlank() &&
            event.workflowId().equals(wire.get("workflow_id"));
    }

    private void onWorkflowEvent(WorkflowEvent event) {
        ActiveEnvelope currentEnvelope = activeEnvelope;
        if (
            residentCallbackInProgress &&
            currentEnvelope != null &&
            eventMatchesEnvelope(event, currentEnvelope.wire())
        ) {
            deferredWorkflowEvents.add(event);
            return;
        }
        LocalDiagnosticEnvelope diagnostic = localDiagnosticEnvelope;
        if (
            diagnostic != null &&
            diagnostic.actionRequestId().equals(event.actionRequestId()) &&
            event.workflowId().equals(diagnostic.workflowId())
        ) {
            onLocalDiagnosticEvent(diagnostic, event);
            return;
        }
        ActiveEnvelope envelope = activeEnvelope;
        if (envelope == null || !eventMatchesEnvelope(event, envelope.wire())) return;
        TemporalDeliveryState temporalState = temporalDeliveryState;
        if (temporalState != null) {
            if (event.state() != State.RUNNING || event.manualOverrideDetected()) {
                envelope.capacityTelemetry().interruptTemporalTiming();
                temporalState.interruptionRevision++;
                temporalState.received.take(false, false);
            }
            if (temporalState.queuedWire != null && java.util.Objects.equals(event.measurements().get("sequence_id"),
                object(temporalState.queuedWire.get("temporal_plan")).get("plan_id"))) {
                if (event.state() == State.RUNNING && !event.manualOverrideDetected()) {
                    Map<String, Object> previous = object(temporalState.currentWire.get("temporal_plan"));
                    Map<String, Object> next = object(temporalState.queuedWire.get("temporal_plan"));
                    envelope.capacityTelemetry().recordTemporalActivation(
                        HelixJson.stringify(List.of(text(previous, "plan_id"), temporalState.queuedCheckpoint)), System.nanoTime(), Map.of(
                            "run_id", text(envelope.wire(), "run_id"),
                            "resident_action_request_id", event.actionRequestId(),
                            "producer_epoch_ref", producerEpochRef,
                            "predecessor_plan_id", text(previous, "plan_id"),
                            "predecessor_plan_hash", text(previous, "plan_hash"),
                            "checkpoint_id", temporalState.queuedCheckpoint,
                            "successor_action_request_id", text(temporalState.queuedWire, "action_request_id"),
                            "successor_plan_id", text(next, "plan_id")));
                }
                temporalState.currentWire = temporalState.queuedWire;
                temporalState.queuedWire = null;
            }
            temporalState.measurements = event.measurements();
            Map<String, Object> acceptance = object(event.measurements().get("temporal_successor_acceptance"));
            if (event.state() == State.RUNNING && !event.manualOverrideDetected() && !acceptance.isEmpty()) {
                Map<String, Object> timingIdentity = new LinkedHashMap<>(acceptance);
                timingIdentity.put("run_id", text(envelope.wire(), "run_id"));
                timingIdentity.put("resident_action_request_id", event.actionRequestId());
                timingIdentity.put("producer_epoch_ref", producerEpochRef);
                timingIdentity.put("successor_action_request_id", temporalState.acceptingSuccessorActionRequestId);
                envelope.capacityTelemetry().recordTemporalAcceptance(HelixJson.stringify(List.of(
                    text(acceptance, "predecessor_sequence_id"), text(acceptance, "checkpoint_id"))),
                    System.nanoTime(), timingIdentity);
            }
            List<Object> checkpoints = HelixJson.asList(event.measurements().get("checkpoint_settlements"));
            if (event.state() == State.RUNNING && !event.manualOverrideDetected() && !checkpoints.isEmpty()) {
                String checkpoint = text(HelixJson.asObject(checkpoints.get(checkpoints.size() - 1)), "checkpoint_id");
                envelope.capacityTelemetry().recordCheckpoint(
                    HelixJson.stringify(List.of(text(event.measurements(), "sequence_id"), checkpoint)), System.nanoTime());
            }
        }
        String eventId = id("environment_action_event");
        long environmentEventSequence = ++latestEventSequence;
        manualInputDetected = manualInputDetected || event.manualOverrideDetected();
        envelope.progressEventRefs().add(eventId);
        if (event.manualOverrideDetected() && event.controlsReleased()) {
            Long releaseLatencyNanos = bridge.consumeManualReleaseLatencyNanos();
            if (releaseLatencyNanos != null) {
                envelope.capacityTelemetry().recordManualOrSafetyToRelease(
                    releaseLatencyNanos
                );
            }
        }
        Map<String, Object> payload = baseNonAnswer();
        payload.put("schema", "helix.environment_action.workflow_event.v1");
        payload.put("event_id", eventId);
        payload.put("action_request_id", event.actionRequestId());
        payload.put("workflow_id", event.workflowId());
        payload.put("sequence", event.sequence());
        payload.put("event_type", event.eventType());
        payload.put("workflow_state", wireState(event.state()));
        payload.put("progress_fraction", event.progressFraction());
        payload.put("summary", event.summary());
        payload.put("control_engine", envelope.controlEngine());
        Map<String, Object> capacityMeasurements = new LinkedHashMap<>(
            event.measurements()
        );
        capacityMeasurements.putAll(envelope.capacityTelemetry().snapshot());
        payload.put("measurements", capacityMeasurements);
        payload.put("clock", clockSnapshot());
        payload.put("evidence_refs", List.of());
        payload.put("manual_override_detected", event.manualOverrideDetected());
        payload.put("controls_released", event.controlsReleased());
        payload.put("created_at", Instant.now().toString());
        payload.put("content_role", "environment_action_event_not_assistant_answer");
        Map<String, Object> eventBatch = environmentEventBatch(
            envelope,
            event,
            eventId,
            environmentEventSequence
        );

        boolean terminal = terminal(event.state());
        Map<String, Object> settledResult = terminal
            ? result(
                envelope.wire(),
                envelope.actionExecutionId(),
                envelope.startedAt(),
                envelope.startedClock(),
                outcome(event.state()),
                event.summary(),
                envelope.progressEventRefs(),
                event.state() == State.SUCCEEDED,
                event.manualOverrideDetected(),
                capacityMeasurements
            )
            : null;
        if (terminal) { activeEnvelope = null; temporalDeliveryState = null; }
        List<PlayerActionDeliveryOutbox.Delivery> deliveries = new ArrayList<>();
        deliveries.add(new PlayerActionDeliveryOutbox.Delivery(
            PlayerActionDeliveryOutbox.Stage.WORKFLOW_EVENT,
            payload
        ));
        deliveries.add(new PlayerActionDeliveryOutbox.Delivery(
            PlayerActionDeliveryOutbox.Stage.ENVIRONMENT_EVENT_BATCH,
            eventBatch
        ));
        if (settledResult != null) {
            deliveries.add(new PlayerActionDeliveryOutbox.Delivery(
                PlayerActionDeliveryOutbox.Stage.ACTION_RESULT,
                settledResult
            ));
        }
        int reserve = terminal ? 0 : RESERVED_TERMINAL_DELIVERIES;
        if (!deliveryOutbox.enqueueSequence(deliveries, reserve)) {
            recordTransportError("action_delivery_outbox_full");
            emergencyStopLatched = true;
            minecraft.execute(() -> controller.emergencyStop(
                "The bounded evidence outbox filled; controls were released rather than losing workflow provenance."
            ));
            return;
        }
        if (temporalState != null) {
            String key = HelixJson.stringify(List.of(
                text(event.measurements(), "sequence_id"),
                HelixJson.asList(event.measurements().get("checkpoint_settlements"))));
            if (!key.equals(temporalState.checkpointEvidenceKey)) {
                temporalState.checkpointEvidenceKey = key;
                temporalState.checkpointDeliveryWatermark = deliveryOutbox.watermark();
            }
        }
        logger.debug(
            "Helix player-action delivery queued: stage={} pending={}",
            deliveries.get(0).stage().diagnosticName(),
            deliveryOutbox.size()
        );
        scheduleDeliveryFlush();
    }

    private void flushDeferredWorkflowEvents() {
        if (deferredWorkflowEvents.isEmpty()) return;
        List<WorkflowEvent> pending = List.copyOf(deferredWorkflowEvents);
        deferredWorkflowEvents.clear();
        for (WorkflowEvent event : pending) onWorkflowEvent(event);
    }

    private void onLocalDiagnosticEvent(
        LocalDiagnosticEnvelope diagnostic,
        WorkflowEvent event
    ) {
        Map<String, Object> record = localDiagnosticBase(diagnostic);
        record.put("schema", "helix.minecraft.player.direct_diagnostic_event.v1");
        record.put("event_type", event.eventType());
        record.put("sequence", event.sequence());
        record.put("workflow_state", wireState(event.state()));
        record.put("progress_fraction", event.progressFraction());
        record.put("summary", event.summary());
        record.put("measurements", event.measurements());
        record.put("clock", clockSnapshot());
        record.put("manual_override_detected", event.manualOverrideDetected());
        record.put("controls_released", event.controlsReleased());
        record.put("created_at", Instant.now().toString());
        logger.info("HELIX_PLAYER_DIRECT_DIAGNOSTIC {}", HelixJson.stringifyIncludingNulls(record));
        if (terminal(event.state())) {
            localDiagnosticEnvelope = null;
            localDiagnosticMessage.accept(
                "Helix direct diagnostic " + wireState(event.state()) +
                ": " + event.summary() + " Measurements: " +
                HelixJson.stringify(event.measurements())
            );
        }
    }

    private Map<String, Object> localDiagnosticBase(
        LocalDiagnosticEnvelope diagnostic
    ) {
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("lane", "direct_codex_reference");
        record.put("action_request_id", diagnostic.actionRequestId());
        record.put("workflow_id", diagnostic.workflowId());
        record.put("action_kind", diagnostic.actionKind());
        record.put("arguments", diagnostic.arguments());
        record.put("starting_state", diagnostic.startingState());
        record.put("started_at", diagnostic.startedAt());
        record.put("started_clock", diagnostic.startedClock());
        record.put("control_engine", diagnostic.controlEngine());
        record.put("staging_request_ref", diagnostic.stagingRequestRef());
        record.put("admission_status", "local_operator_diagnostic");
        record.put("helix_terminal_authority_status", "not_applicable");
        record.put("assistant_answer", false);
        record.put("terminal_eligible", false);
        record.put("raw_content_included", false);
        return record;
    }

    private void scheduleDeliveryFlush() {
        scheduleCriticalDeliveryFlush();
        scheduleProjectionDeliveryFlush();
    }

    private void scheduleCriticalDeliveryFlush() {
        if (deliveryOutbox.isCriticalEmpty() ||
            !criticalDeliveryFlushPending.compareAndSet(false, true)) return;
        criticalDeliveryNetwork.execute(() -> {
            boolean drained = false;
            try {
                drained = flushCriticalDeliveryOutbox();
            } finally {
                criticalDeliveryFlushPending.set(false);
                if (drained && !deliveryOutbox.isCriticalEmpty()) {
                    scheduleCriticalDeliveryFlush();
                }
            }
        });
    }

    private void scheduleProjectionDeliveryFlush() {
        if (deliveryOutbox.isProjectionEmpty() ||
            !projectionDeliveryFlushPending.compareAndSet(false, true)) return;
        long queuedAtNanos = System.nanoTime();
        projectionDeliveryNetwork.execute(() -> {
            boolean drained = false;
            try {
                long queueDelayMillis = Math.max(0L, (System.nanoTime() - queuedAtNanos) / 1_000_000L);
                if (queueDelayMillis >= SLOW_DELIVERY_MILLIS) {
                    logger.warn("Helix player-action projection flush queued for {} ms", queueDelayMillis);
                }
                drained = flushProjectionDeliveryOutbox();
            } finally {
                projectionDeliveryFlushPending.set(false);
                if (drained && !deliveryOutbox.isProjectionEmpty()) {
                    scheduleProjectionDeliveryFlush();
                }
            }
        });
    }

    private boolean flushCriticalDeliveryOutbox() {
        return flushDeliveryLane(false);
    }

    private boolean flushProjectionDeliveryOutbox() {
        return flushDeliveryLane(true);
    }

    static Map<String, Object> projectionBatchPayload(
        List<PlayerActionDeliveryOutbox.Delivery> deliveries
    ) {
        if (deliveries.isEmpty()) throw new IllegalArgumentException("Empty projection delivery");
        if (deliveries.size() == 1) return deliveries.get(0).payload();
        Map<String, Object> merged = new LinkedHashMap<>(deliveries.get(0).payload());
        merged.remove("batch_hash");
        List<Object> events = new ArrayList<>();
        for (PlayerActionDeliveryOutbox.Delivery delivery : deliveries) {
            Object value = delivery.payload().get("events");
            if (!(value instanceof List<?> members)) {
                throw new IllegalArgumentException("Projection delivery has no event list");
            }
            events.addAll(members);
        }
        merged.put("events", events);
        merged.put("last_sequence", deliveries.get(deliveries.size() - 1).payload().get("last_sequence"));
        merged.put("batch_hash", SectionHasher.hashIncludingNulls(merged));
        return merged;
    }

    private boolean flushDeliveryLane(boolean projectionLane) {
        while (true) {
            List<PlayerActionDeliveryOutbox.Delivery> projectionBatch = projectionLane
                ? deliveryOutbox.peekProjectionBatch() : List.of();
            List<PlayerActionDeliveryOutbox.Delivery> criticalBatch = projectionLane
                ? List.of() : deliveryOutbox.peekCriticalBatch(workflowEventBatchSupported);
            PlayerActionDeliveryOutbox.Delivery delivery = projectionLane
                ? projectionBatch.isEmpty() ? null : projectionBatch.get(0)
                : criticalBatch.isEmpty() ? null : criticalBatch.get(0);
            if (delivery == null) break;
            boolean batched = criticalBatch.size() > 1;
            try {
                long attemptStartedNanos = System.nanoTime();
                Map<String, Object> payload = projectionLane
                    ? projectionBatchPayload(projectionBatch)
                    : batched ? Map.of("events", criticalBatch.stream()
                        .map(PlayerActionDeliveryOutbox.Delivery::payload).toList())
                    : delivery.payload();
                String endpoint = batched ? "/requests/events" : delivery.stage().endpointSuffix();
                PlayerActionHttpClient.Response receipt = projectionLane
                    ? http.postProjection(endpoint, payload)
                    : http.postCritical(endpoint, payload);
                if (!receipt.ok()) {
                    logger.warn("Helix player-action {} delivery failed after {} ms with HTTP {}",
                        projectionLane ? "projection" : "critical",
                        Math.max(0L, (System.nanoTime() - attemptStartedNanos) / 1_000_000L),
                        receipt.statusCode());
                    String transportError = PlayerActionDeliveryOutbox.transportErrorCode(
                        delivery.stage(),
                        receipt.statusCode(),
                        receipt.error()
                    );
                    recordTransportError(transportError);
                    if (requiresFreshProducerEpoch(transportError)) {
                        eventStreamResyncRequired = true;
                        bridge.releaseAll();
                        logger.error(
                            "Helix player-action evidence stream requires a fresh pairing; controls were released and no further actions will be polled."
                        );
                        scheduleHeartbeatPublish();
                    }
                    return false;
                }
                boolean acknowledged = projectionLane
                    ? "helix.environment_event_batch_receipt.v1".equals(receipt.body().get("schema")) &&
                        java.util.Objects.equals(receipt.body().get("batch_id"), payload.get("batch_id")) &&
                        receipt.body().get("latest_event_sequence") instanceof Number latest &&
                        payload.get("last_sequence") instanceof Number expectedLast &&
                        latest.longValue() == expectedLast.longValue() &&
                        deliveryOutbox.acknowledgeProjectionBatch(projectionBatch)
                    : batched
                        ? "helix.environment_action.events_receipt.v1".equals(receipt.body().get("schema")) &&
                            deliveryOutbox.acknowledgeCriticalBatch(criticalBatch, receipt.body().get("event_ids"))
                        : deliveryOutbox.acknowledge(delivery);
                if (!acknowledged) {
                    recordTransportError(projectionLane
                        ? "action_delivery_projection_receipt_mismatch"
                        : "action_delivery_workflow_receipt_mismatch");
                    return false;
                }
                if (!projectionLane) {
                    long elapsedMillis = Math.max(0L, (System.nanoTime() - attemptStartedNanos) / 1_000_000L);
                    if (elapsedMillis >= SLOW_DELIVERY_MILLIS) {
                        logger.warn("Helix player-action critical delivery acknowledged after {} ms at stage {}",
                            elapsedMillis, delivery.stage().diagnosticName());
                    }
                }
                if (
                    acknowledged &&
                    delivery.stage() == PlayerActionDeliveryOutbox.Stage.ENVIRONMENT_EVENT_BATCH
                ) {
                    lastProjectionAcknowledgedNanos = System.nanoTime();
                    latestAcknowledgedEventSequence = acknowledgedEventSequence(
                        latestAcknowledgedEventSequence,
                        payload
                    );
                    long elapsedMillis = Math.max(0L,
                        (lastProjectionAcknowledgedNanos - attemptStartedNanos) / 1_000_000L);
                    if (elapsedMillis >= SLOW_DELIVERY_MILLIS) {
                        logger.warn("Helix player-action projection acknowledged after {} ms at sequence {}",
                            elapsedMillis, latestAcknowledgedEventSequence);
                    }
                }
                logger.debug(
                    "Helix player-action delivery acknowledged: stage={} pending={}",
                    delivery.stage().diagnosticName(),
                    deliveryOutbox.size()
                );
                // A heartbeat queued during this projection POST must run
                // before the next projection batch. The acknowledgement above
                // first advances the safe server-visible event cursor.
                if (projectionLane && projectionFlushShouldYieldToHeartbeat(
                    heartbeatPublishPending.get()
                )) break;
            } catch (Exception error) {
                String code = "action_delivery_" + delivery.stage().diagnosticName() + "_unreachable";
                if (!code.equals(lastTransportError)) {
                    logger.warn("Helix player-action {} delivery exception: type={} pending={}",
                        projectionLane ? "projection" : "critical",
                        error.getClass().getSimpleName(), deliveryOutbox.size());
                }
                recordTransportError(code);
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                return false;
            }
        }
        clearTransportErrorIfDeliveryComplete();
        return true;
    }

    private Map<String, Object> environmentEventBatch(
        ActiveEnvelope envelope,
        WorkflowEvent workflowEvent,
        String actionEventId,
        long sequence
    ) {
        String observedAt = Instant.now().toString();
        PlayerActionWorkflow.PlayerSnapshot snapshot = evidenceSnapshot.get();
        Map<String, Object> actor = new LinkedHashMap<>();
        actor.put("connected", snapshot.connected());
        actor.put("position", Map.of(
            "x", snapshot.x(),
            "y", snapshot.y(),
            "z", snapshot.z()
        ));
        actor.put("eye_y", snapshot.eyeY());
        actor.put("yaw", snapshot.yaw());
        actor.put("pitch", snapshot.pitch());
        actor.put("on_ground", snapshot.onGround());
        actor.put("horizontal_collision", snapshot.horizontalCollision());

        Map<String, Object> activeWorkflow = new LinkedHashMap<>();
        activeWorkflow.put("workflow_ref", workflowEvent.workflowId());
        activeWorkflow.put("action_kind", text(envelope.wire(), "action_kind"));
        activeWorkflow.put("workflow_state", wireState(workflowEvent.state()));
        if (workflowEvent.progressFraction() != null) {
            activeWorkflow.put("progress_fraction", workflowEvent.progressFraction());
        }
        activeWorkflow.put("manual_override_detected", workflowEvent.manualOverrideDetected());
        activeWorkflow.put("controls_released", workflowEvent.controlsReleased());

        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("actor", actor);
        attributes.put("active_workflow", activeWorkflow);
        Map<String, Object> workflowMeasurements = new LinkedHashMap<>(
            workflowEvent.measurements()
        );
        workflowMeasurements.putAll(envelope.capacityTelemetry().snapshot());
        attributes.put("workflow_measurements", workflowMeasurements);
        attributes.put("clock", clockSnapshot());
        attributes.put("action_event_ref", actionEventId);

        String streamEventId = id("environment_event");
        Map<String, Object> streamEvent = baseNonAnswer();
        streamEvent.put("schema", "helix.environment_event.v1");
        streamEvent.put("event_id", streamEventId);
        streamEvent.put("sequence", sequence);
        streamEvent.put("event_type", workflowEvent.eventType());
        streamEvent.put("producer_plane", "player_embodiment");
        streamEvent.put("domain", "minecraft");
        streamEvent.put("domain_adapter", config.domainAdapter());
        streamEvent.put("room_id", config.roomId());
        streamEvent.put("source_id", config.sourceId());
        streamEvent.put("world_id", config.worldId());
        streamEvent.put("producer_epoch_ref", producerEpochRef);
        streamEvent.put("subject_ref", config.subjectBindingId());
        streamEvent.put("workflow_ref", workflowEvent.workflowId());
        streamEvent.put("summary", workflowEvent.summary());
        streamEvent.put("attributes", attributes);
        streamEvent.put("evidence_refs", List.of(actionEventId));
        streamEvent.put("occurred_at", observedAt);
        streamEvent.put("observed_at", observedAt);
        streamEvent.put("provenance", "measured");
        streamEvent.put("raw_event_included", false);
        streamEvent.put("content_role", "environment_event_not_assistant_answer");

        Map<String, Object> batch = baseNonAnswer();
        batch.put("schema", "helix.environment_event_batch.v1");
        batch.put("batch_id", id("environment_event_batch"));
        batch.put("room_id", config.roomId());
        batch.put("source_id", config.sourceId());
        batch.put("world_id", config.worldId());
        batch.put("producer_epoch_ref", producerEpochRef);
        batch.put("producer_plane", "player_embodiment");
        batch.put("first_sequence", sequence);
        batch.put("last_sequence", sequence);
        batch.put("events", List.of(streamEvent));
        batch.put("created_at", observedAt);
        batch.put("content_role", "environment_event_batch_not_assistant_answer");
        batch.put("batch_hash", SectionHasher.hashIncludingNulls(batch));
        return batch;
    }

    private Map<String, Object> residentEnvironmentEventBatch(
        MinecraftViabilityGuardian.Decision decision,
        long sequence
    ) {
        String observedAt = Instant.now().toString();
        String eventId = id("environment_event");
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("resident_decision", Map.ofEntries(
            Map.entry("profile_id", MinecraftViabilityGuardian.PROFILE_ID),
            Map.entry("artifact_version", MinecraftViabilityGuardian.ARTIFACT_VERSION),
            Map.entry("decision_sequence", decision.decisionSequence()),
            Map.entry("observation_revision", decision.observationRevision()),
            Map.entry("proposal", decision.proposal().name().toLowerCase(java.util.Locale.ROOT)),
            Map.entry("reason_code", decision.reasonCode()),
            Map.entry("arbiter_outcome", residentArbiterOutcome(decision)),
            Map.entry("bounded_effect", residentBoundedEffect(decision)),
            Map.entry("effect_applied", residentEffectApplied(decision)),
            Map.entry("postcondition_status", residentPostconditionStatus(decision)),
            Map.entry("controls_released", decision.controlsMustRelease()),
            Map.entry("semantic_escalation_required", decision.semanticEscalationRequired()),
            Map.entry("measurements", decision.measurements())
        ));
        attributes.put("clock", clockSnapshot());

        Map<String, Object> event = baseNonAnswer();
        event.put("schema", "helix.environment_event.v1");
        event.put("event_id", eventId);
        event.put("sequence", sequence);
        event.put("event_type", "resident.decision");
        event.put("producer_plane", "player_embodiment");
        event.put("domain", "minecraft");
        event.put("domain_adapter", config.domainAdapter());
        event.put("room_id", config.roomId());
        event.put("source_id", config.sourceId());
        event.put("world_id", config.worldId());
        event.put("producer_epoch_ref", producerEpochRef);
        event.put("subject_ref", config.subjectBindingId());
        event.put("workflow_ref", null);
        event.put("summary", "The deterministic resident Minecraft guardian recorded " + decision.reasonCode() + ".");
        event.put("attributes", attributes);
        event.put("evidence_refs", List.of());
        event.put("occurred_at", observedAt);
        event.put("observed_at", observedAt);
        event.put("provenance", "measured");
        event.put("raw_event_included", false);
        event.put("content_role", "environment_event_not_assistant_answer");

        Map<String, Object> batch = baseNonAnswer();
        batch.put("schema", "helix.environment_event_batch.v1");
        batch.put("batch_id", id("environment_event_batch"));
        batch.put("room_id", config.roomId());
        batch.put("source_id", config.sourceId());
        batch.put("world_id", config.worldId());
        batch.put("producer_epoch_ref", producerEpochRef);
        batch.put("producer_plane", "player_embodiment");
        batch.put("first_sequence", sequence);
        batch.put("last_sequence", sequence);
        batch.put("events", List.of(event));
        batch.put("created_at", observedAt);
        batch.put("content_role", "environment_event_batch_not_assistant_answer");
        batch.put("batch_hash", SectionHasher.hashIncludingNulls(batch));
        return batch;
    }

    private static String residentArbiterOutcome(
        MinecraftViabilityGuardian.Decision decision
    ) {
        return switch (decision.proposal()) {
            case NONE -> "not_requested";
            case MONITOR_ADMITTED_RECOVERY -> "delegated_to_admitted_recovery";
            default -> "admitted";
        };
    }

    private static boolean residentEffectApplied(
        MinecraftViabilityGuardian.Decision decision
    ) {
        return decision.proposal() != MinecraftViabilityGuardian.ProposalKind.NONE &&
            decision.proposal() !=
                MinecraftViabilityGuardian.ProposalKind.MONITOR_ADMITTED_RECOVERY;
    }

    private static String residentBoundedEffect(
        MinecraftViabilityGuardian.Decision decision
    ) {
        return switch (decision.proposal()) {
            case NONE -> "none";
            case SWIM_UP -> "swim_up_input";
            case MONITOR_ADMITTED_RECOVERY -> "continue_admitted_recovery";
            case RELEASE_AND_ESCALATE, ABSTAIN_AND_ESCALATE -> "release_controls";
        };
    }

    private static String residentPostconditionStatus(
        MinecraftViabilityGuardian.Decision decision
    ) {
        return switch (decision.reasonCode()) {
            case "breathing_restored_surface_hold" -> "breathing_restored_surface_hold_active";
            case "water_exit_verified" -> "water_exit_verified";
            case "unsafe_landing_recovery_active" -> "admitted_fall_recovery_active";
            case "fire_recovery_program_active" -> "admitted_fire_recovery_active";
            case "fire_recovery_postcondition_observed" -> "fire_recovery_postcondition_observed";
            case "fall_recovery_verified" -> "fall_recovery_verified";
            case "fire_recovery_verified" -> "fire_recovery_verified";
            case "movement_blocked_requires_semantic_replan",
                 "unsafe_landing_requires_admitted_recovery",
                 "fire_pressure_requires_semantic_replan",
                 "lava_pressure_requires_semantic_replan",
                 "manual_override",
                 "emergency_stop",
                 "guardian_lease_expired",
                 "guardian_lease_expired_during_water_recovery" -> "controls_released";
            default -> "pending_or_not_applicable";
        };
    }

    private void submitWithoutExecution(Map<String, Object> wire, String outcome, String summary) {
        Map<String, Object> payload = result(
            wire,
            id("environment_action_execution"),
            null,
            null,
            outcome,
            summary,
            List.of(),
            false,
            false,
            Map.of()
        );
        PlayerActionDeliveryOutbox.Delivery delivery =
            new PlayerActionDeliveryOutbox.Delivery(
                PlayerActionDeliveryOutbox.Stage.ACTION_RESULT,
                payload
            );
        if (!deliveryOutbox.enqueueSequence(List.of(delivery), 0)) {
            recordTransportError("action_delivery_outbox_full");
            return;
        }
        scheduleDeliveryFlush();
    }

    private Map<String, Object> result(
        Map<String, Object> wire,
        String executionId,
        String startedAt,
        Map<String, Object> startedClock,
        String outcome,
        String summary,
        List<String> eventRefs,
        boolean postconditionsSatisfied,
        boolean manualOverride,
        Map<String, Object> measurements
    ) {
        String actionKind = text(wire, "action_kind");
        List<Map<String, Object>> postconditions = new ArrayList<>();
        for (Object value : list(wire.get("postconditions"))) {
            Map<String, Object> condition = object(value);
            Map<String, Object> checked = new LinkedHashMap<>();
            checked.put("condition_id", textRequired(condition, "condition_id"));
            checked.put("condition_kind", textRequired(condition, "condition_kind"));
            checked.put("required", Boolean.TRUE.equals(condition.get("required")));
            checked.put("status", postconditionsSatisfied ? "satisfied" : "not_checked");
            checked.put("summary", postconditionsSatisfied
                ? "The native Fabric executor completed its action-specific postcondition check."
                : "The workflow did not complete, so this postcondition was not claimed as satisfied.");
            checked.put("evidence_refs", List.copyOf(eventRefs));
            checked.put("checked_at", Instant.now().toString());
            postconditions.add(checked);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "helix.environment_action.result.v1");
        result.put("action_request_id", textRequired(wire, "action_request_id"));
        result.put("workflow_id", textRequired(wire, "workflow_id"));
        result.put("action_execution_id", executionId);
        result.put("capability_id", textRequired(wire, "capability_id"));
        result.put("capability_version", number(wire, "capability_version").intValue());
        result.put("action_kind", actionKind);
        result.put("outcome", outcome);
        result.put("summary", summary);
        result.put("control_engine", startedAt == null
            ? "none"
            : "baritone".equals(text(wire, "requested_control_engine"))
                ? "baritone"
                : "native_fabric");
        result.put("started_at", startedAt);
        result.put("completed_at", Instant.now().toString());
        result.put("progress_event_refs", List.copyOf(eventRefs));
        result.put("postconditions", postconditions);
        result.put("evidence_refs", List.copyOf(eventRefs));
        Map<String, Object> completedClock = clockSnapshot();
        result.put("started_clock", startedClock);
        result.put("completed_clock", completedClock);
        result.put(
            "duration_ticks",
            startedClock == null
                ? null
                : Math.max(
                    0,
                    longNumber(completedClock, "tick_index") -
                        longNumber(startedClock, "tick_index")
                )
        );
        // Retain per-plan evidence unchanged; only the settled result's effect
        // projection combines history, so a quiet final plan cannot erase it.
        if ("execute_sequence".equals(actionKind)) measurements = ResidentEffectMeasurements.resultView(measurements);
        boolean programKind = "execute_sequence".equals(actionKind) ||
            "execute_reactive_program".equals(actionKind);
        boolean motionKind = List.of(
            "navigate_to", "look_at", "track_target", "walk", "jump", "combat_guard", "follow", "collect", "mine", "place"
        ).contains(actionKind) ||
            (programKind && Boolean.TRUE.equals(measurements.get("player_motion_performed")));
        boolean interactionKind = List.of(
            "interact", "attack", "combat_guard", "mine", "place", "craft", "consume", "inventory_transfer"
        ).contains(actionKind) ||
            (programKind && Boolean.TRUE.equals(measurements.get("player_interaction_performed")));
        boolean directInventoryKind = List.of("hotbar_select", "equip", "consume").contains(actionKind) ||
            (programKind && Boolean.TRUE.equals(measurements.get("inventory_mutation_performed")));
        boolean measuredInventoryMutation =
            positiveMeasurement(measurements, "collected_count") ||
            positiveMeasurement(measurements, "produced_count") ||
            positiveMeasurement(measurements, "consumed_count") ||
            positiveMeasurement(measurements, "transferred_count") ||
            positiveMeasurement(measurements, "inventory_mutations_performed");
        boolean measuredWorldMutation = positiveMeasurement(
            measurements,
            "world_mutations_performed"
        );
        boolean executionStarted = startedAt != null;
        boolean actionAppliedBeforeManualOverride =
            !manualOverride || positiveMeasurement(
                measurements,
                "action_ticks_before_override"
            );
        boolean executionPerformed = effectExecutionPerformed(
            executionStarted,
            actionAppliedBeforeManualOverride,
            measurements
        );
        result.put(
            "side_effects_performed",
            executionPerformed && (
                motionKind || interactionKind || directInventoryKind ||
                measuredInventoryMutation || measuredWorldMutation
            )
        );
        result.put("player_motion_performed", motionKind && executionPerformed);
        result.put("player_interaction_performed", interactionKind && executionPerformed);
        result.put(
            "inventory_mutation_performed",
            executionPerformed && (
                directInventoryKind || measuredInventoryMutation
            )
        );
        result.put("world_mutation_performed", executionPerformed && measuredWorldMutation);
        result.put("manual_override_detected", manualOverride);
        result.put(
            "manual_override_reason",
            manualOverride ? measurements.get("manual_input_reason") : null
        );
        result.put("controls_released", true);
        result.put("host_access_performed", false);
        result.put("automatic_replay_performed", false);
        result.put("model_invoked", false);
        result.put("assistant_answer", false);
        result.put("raw_content_included", false);
        return result;
    }

    static boolean effectExecutionPerformed(
        boolean executionStarted,
        boolean actionAppliedBeforeManualOverride,
        Map<String, Object> measurements
    ) {
        boolean hasExplicitEffectMeasurement = measurements.containsKey(
            "side_effects_performed"
        ) || measurements.containsKey(
            "player_motion_performed"
        ) || measurements.containsKey(
            "player_interaction_performed"
        ) || measurements.containsKey(
            "inventory_mutation_performed"
        ) || measurements.containsKey(
            "world_mutation_performed"
        );
        boolean measuredEffect = Boolean.TRUE.equals(
            measurements.get("side_effects_performed")
        ) || Boolean.TRUE.equals(
            measurements.get("player_motion_performed")
        ) || Boolean.TRUE.equals(
            measurements.get("player_interaction_performed")
        ) || Boolean.TRUE.equals(
            measurements.get("inventory_mutation_performed")
        ) || Boolean.TRUE.equals(
            measurements.get("world_mutation_performed")
        );
        return executionStarted &&
            actionAppliedBeforeManualOverride &&
            (
                hasExplicitEffectMeasurement
                    ? measuredEffect
                    : !Boolean.TRUE.equals(measurements.get("effect_prevented"))
            );
    }

    private Map<String, Object> manifest() {
        Map<String, Object> manifest = baseNonAnswer();
        manifest.put("schema", "helix.environment_action.connector_manifest.v1");
        manifest.put("manifest_id", manifestId);
        manifest.put("connector_installation_id", config.connectorInstallationId());
        manifest.put("producer_epoch_ref", producerEpochRef);
        manifest.put("action_authority_id", config.actionAuthorityId());
        manifest.put("environment_binding_id", config.environmentBindingId());
        manifest.put("room_id", config.roomId());
        manifest.put("source_id", config.sourceId());
        manifest.put("world_id", config.worldId());
        manifest.put("participant_id", config.participantId());
        manifest.put("subject_binding_id", config.subjectBindingId());
        manifest.put("subject_native_id", config.subjectNativeId());
        manifest.put("domain", "minecraft");
        manifest.put("domain_adapter", config.domainAdapter());
        manifest.put("adapter_profile_id", config.adapterProfileId());
        manifest.put("adapter_version", ADAPTER_VERSION);
        manifest.put("protocol_version", PROTOCOL_VERSION);
        manifest.put("capabilities", instanceCapabilities());
        List<Map<String, Object>> engines = new ArrayList<>();
        engines.add(Map.of(
            "control_engine", "native_fabric",
            "available", true,
            "version", "1"
        ));
        if (bridge.baritoneAvailable()) {
            engines.add(Map.of(
                "control_engine", "baritone",
                "available", true,
                "version", bridge.baritoneVersion(),
                "goal_forms", List.of("near_position"),
                "mutation_policy", "movement_only",
                "breaking_allowed", false,
                "placement_allowed", false,
                "inventory_mutation_allowed", false
            ));
        }
        manifest.put("available_control_engines", engines);
        manifest.put("safety_policy", Map.of(
            "manual_override_supported", true,
            "manual_override_policy", "cancel",
            "progress_observations_supported", true,
            "postcondition_verification_supported", true,
            "emergency_stop_supported", true,
            "release_controls_on_disconnect", true,
            "host_access_supported", false,
            "automatic_replay_supported", false,
            "model_execution_supported", false
        ));
        manifest.put("created_at", Instant.now().toString());
        manifest.put("credential_included", false);
        manifest.put("content_role", "environment_action_connector_manifest_not_assistant_answer");
        return manifest;
    }

    private Map<String, Object> heartbeat() {
        if (!minecraft.isSameThread()) {
            throw new IllegalStateException("heartbeat_snapshot_requires_client_thread");
        }
        String workflow = controller.activeWorkflowId();
        boolean running = workflow != null && controller.state() == State.RUNNING;
        Map<String, Object> heartbeat = baseNonAnswer();
        heartbeat.put("schema", "helix.environment_action.connector_heartbeat.v1");
        heartbeat.put("heartbeat_id", id("environment_action_heartbeat"));
        heartbeat.put("manifest_id", manifestId);
        heartbeat.put("connector_installation_id", config.connectorInstallationId());
        heartbeat.put("producer_epoch_ref", producerEpochRef);
        heartbeat.put("action_authority_id", config.actionAuthorityId());
        heartbeat.put("environment_binding_id", config.environmentBindingId());
        heartbeat.put("room_id", config.roomId());
        heartbeat.put("source_id", config.sourceId());
        heartbeat.put("world_id", config.worldId());
        heartbeat.put("participant_id", config.participantId());
        heartbeat.put("subject_binding_id", config.subjectBindingId());
        heartbeat.put(
            "status",
            connectorHeartbeatStatus(
                emergencyStopLatched,
                eventStreamResyncRequired,
                lastTransportError
            )
        );
        heartbeat.put("active_workflow_ids", workflow == null ? List.of() : List.of(workflow));
        heartbeat.put(
            "controls_asserted",
            running && !emergencyStopLatched && !eventStreamResyncRequired
        );
        heartbeat.put("manual_input_detected", manualInputDetected);
        heartbeat.put("emergency_stop_latched", emergencyStopLatched);
        List<Map<String, Object>> engineStates = new ArrayList<>();
        String activeEngine = activeEnvelope == null
            ? ""
            : activeEnvelope.controlEngine();
        Map<String, Object> nativeEngine = new LinkedHashMap<>();
        nativeEngine.put("control_engine", "native_fabric");
        nativeEngine.put("status", running && "native_fabric".equals(activeEngine) ? "busy" : "available");
        nativeEngine.put("last_error", lastTransportError.isBlank() ? null : lastTransportError);
        engineStates.add(nativeEngine);
        if (bridge.baritoneAvailable()) {
            BaritoneFacade.Status status = bridge.baritoneStatus();
            Map<String, Object> baritoneEngine = new LinkedHashMap<>();
            baritoneEngine.put("control_engine", "baritone");
            baritoneEngine.put("status", status.pathState().name().toLowerCase());
            baritoneEngine.put("goal_owned", status.goalOwned());
            baritoneEngine.put("process_active", status.processActive());
            baritoneEngine.put("mutation_policy", "movement_only");
            baritoneEngine.put("mutation_policy_intact", status.mutationPolicyIntact());
            baritoneEngine.put("safe_cancel_last_result", status.safeCancelLastResult());
            baritoneEngine.put("last_error", status.lastError().isBlank() ? null : status.lastError());
            if (status.estimatedTicksToGoal() != null) {
                baritoneEngine.put("estimated_ticks_to_goal", status.estimatedTicksToGoal());
            }
            engineStates.add(baritoneEngine);
        }
        heartbeat.put("control_engines", engineStates);
        heartbeat.put(
            "latest_event_sequence",
            latestAcknowledgedEventSequence < 0 ? null : latestAcknowledgedEventSequence
        );
        heartbeat.put("clock", clockSnapshot());
        heartbeat.put("evidence_refs", List.of());
        heartbeat.put("created_at", Instant.now().toString());
        heartbeat.put("credential_included", false);
        heartbeat.put("content_role", "environment_action_connector_heartbeat_not_assistant_answer");
        return heartbeat;
    }

    static long acknowledgedEventSequence(
        long currentSequence,
        Map<String, Object> batch
    ) {
        Object candidate = batch.get("last_sequence");
        if (!(candidate instanceof Number number)) return currentSequence;
        return Math.max(currentSequence, number.longValue());
    }

    private List<Map<String, Object>> instanceCapabilities() {
        List<Map<String, Object>> capabilities = new ArrayList<>();
        capabilities.add(capability(
            "com.casimirbot.minecraft.player.navigate",
            "navigate_to",
            "continuous_control",
            List.of("long_running"),
            bridge.baritoneAvailable() ? List.of("native_fabric", "baritone") : List.of("native_fabric"),
            false
        ));
        capabilities.addAll(List.of(
            capability("com.casimirbot.minecraft.player.look", "look_at", "player_motion", List.of("single_action")),
            capability("com.casimirbot.minecraft.player.camera.track", "track_target", "continuous_control", List.of("long_running")),
            capability("com.casimirbot.minecraft.player.walk", "walk", "continuous_control", List.of("long_running")),
            capability("com.casimirbot.minecraft.player.jump", "jump", "player_motion", List.of("single_action")),
            capability("com.casimirbot.minecraft.player.interact", "interact", "player_interaction", List.of("single_action")),
            capability("com.casimirbot.minecraft.player.combat.attack", "attack", "player_interaction", List.of("long_running")),
            capability(
                "com.casimirbot.minecraft.player.combat.guard",
                "combat_guard",
                "continuous_control",
                List.of("long_running"),
                List.of("native_fabric"),
                true
            ),
            capability("com.casimirbot.minecraft.player.hotbar.select", "hotbar_select", "player_inventory", List.of("single_action")),
            capability("com.casimirbot.minecraft.player.equipment.equip", "equip", "player_inventory", List.of("single_action")),
            capability("com.casimirbot.minecraft.player.follow", "follow", "continuous_control", List.of("long_running")),
            capability("com.casimirbot.minecraft.player.collect", "collect", "continuous_control", List.of("long_running")),
            capability("com.casimirbot.minecraft.player.mine", "mine", "world_mutation", List.of("long_running"), List.of("native_fabric"), true),
            capability("com.casimirbot.minecraft.player.place", "place", "world_mutation", List.of("long_running"), List.of("native_fabric"), true),
            capability("com.casimirbot.minecraft.player.craft", "craft", "player_inventory", List.of("long_running")),
            capability("com.casimirbot.minecraft.player.consume", "consume", "player_inventory", List.of("long_running")),
            capability("com.casimirbot.minecraft.player.inventory.transfer", "inventory_transfer", "player_inventory", List.of("long_running")),
            capability(
                "com.casimirbot.minecraft.player.sequence.execute",
                "execute_sequence",
                "continuous_control",
                List.of("long_running"),
                List.of("native_fabric"),
                true
            ),
            capability(
                "com.casimirbot.minecraft.player.guardian.execute",
                "execute_reactive_program",
                "continuous_control",
                List.of("long_running"),
                List.of("native_fabric"),
                true
            ),
            capability(
                "com.casimirbot.minecraft.player.viability_guardian.arm",
                "arm_viability_guardian",
                "continuous_control",
                List.of("single_action"),
                List.of("native_fabric"),
                false
            ),
            capability(
                "com.casimirbot.minecraft.player.viability_guardian.disarm",
                "disarm_viability_guardian",
                "continuous_control",
                List.of("single_action"),
                List.of("native_fabric"),
                false
            )
        ));
        return List.copyOf(capabilities);
    }

    private static Map<String, Object> capability(
        String capabilityId,
        String actionKind,
        String effectClass,
        List<String> modes
    ) {
        return capability(
            capabilityId,
            actionKind,
            effectClass,
            modes,
            List.of("native_fabric"),
            false
        );
    }

    static Map<String, Object> capability(
        String capabilityId,
        String actionKind,
        String effectClass,
        List<String> modes,
        List<String> controlEngines,
        boolean mutationScopeRequired
    ) {
        Map<String, Object> result = new LinkedHashMap<>(Map.of(
            "capability_id", capabilityId,
            "capability_version", 1,
            "action_kind", actionKind,
            "effect_class", effectClass,
            "workflow_modes", modes,
            "control_engines", controlEngines,
            "requires_world_mutation_scope", mutationScopeRequired,
            "requires_confirmation", true
        ));
        if ("execute_sequence".equals(actionKind)) {
            result.put("execution_features", List.of("latest_start_tick_v1", "temporal_plan_v1"));
        } else if ("execute_reactive_program".equals(actionKind)) {
            result.put("execution_features", List.of("latest_start_tick_v1"));
        }
        return Map.copyOf(result);
    }

    private <T> T runOnClient(java.util.concurrent.Callable<T> callable) throws Exception {
        return ClientThreadCall.await(clientExecutor, callable, 12_000);
    }

    private Map<String, Object> captureClockSnapshot(long clientTick) {
        Long worldTick = minecraft.level == null
            ? null
            : Math.max(0L, minecraft.level.getGameTime());
        Map<String, Object> clock = new LinkedHashMap<>();
        clock.put("schema", "helix.environment_clock_snapshot.v1");
        clock.put("clock_id", executionClockId);
        clock.put("clock_kind", "minecraft_game_tick");
        clock.put("tick_rate_hz", 20);
        clock.put("tick_index", Math.max(0L, clientTick));
        clock.put("world_tick_index", worldTick);
        clock.put("monotonic", monotonicClock.snapshot());
        clock.put(
            "synchronization",
            worldTick == null ? "client_local" : "server_synchronized"
        );
        clock.put("observed_at", Instant.now().toString());
        return Collections.unmodifiableMap(clock);
    }

    private Map<String, Object> clockSnapshot() {
        Map<String, Object> snapshot = latestClockSnapshot;
        return snapshot.isEmpty()
            ? captureClockSnapshot(Math.max(0L, ticks))
            : new LinkedHashMap<>(snapshot);
    }

    private void recordTransportError(String error) {
        if (!error.equals(lastTransportError)) {
            logger.warn("Helix player-action connector degraded: {}", error);
        }
        lastTransportError = error;
    }

    private void clearTransportError() {
        lastTransportError = "";
    }

    private void clearTransportErrorIfDeliveryComplete() {
        if (deliveryOutbox.isEmpty()) clearTransportError();
    }

    static boolean actionPollingReady(
        boolean manifestReady,
        boolean heartbeatReady,
        boolean credentialLeaseCurrent
    ) {
        return manifestReady && heartbeatReady && credentialLeaseCurrent;
    }

    static boolean directDiagnosticActionAllowed(String actionKind) {
        return List.of(
            "navigate_to", "look_at", "track_target", "walk", "jump", "interact",
            "attack", "combat_guard", "hotbar_select", "equip", "follow", "collect", "mine", "place",
            "craft", "consume", "inventory_transfer", "execute_sequence",
            "execute_reactive_program", "arm_viability_guardian",
            "disarm_viability_guardian"
        ).contains(actionKind);
    }

    static boolean activeControlPlaneFailureRequiresStop(
        boolean remoteActionActive,
        String activeWorkflowId,
        String transportError
    ) {
        return remoteActionActive &&
            activeWorkflowId != null &&
            transportError != null &&
            !transportError.isBlank();
    }

    static boolean credentialExpiryRequiresWorkflowStop(
        boolean remoteActionActive,
        String activeWorkflowId,
        boolean credentialLeaseCurrent
    ) {
        return remoteActionActive && activeWorkflowId != null && !credentialLeaseCurrent;
    }

    static boolean remoteGuardianMustDisarm(
        boolean remotelyArmed,
        boolean credentialLeaseCurrent,
        boolean heartbeatReady,
        boolean evidenceStreamResyncRequired,
        boolean emergencyStopped
    ) {
        return remotelyArmed && (!credentialLeaseCurrent || !heartbeatReady ||
            evidenceStreamResyncRequired || emergencyStopped);
    }

    static boolean temporalDeliveryReady(State state, boolean resyncRequired, boolean emergencyStopped, boolean evidencePending) {
        return state == State.RUNNING && !resyncRequired && !emergencyStopped && !evidencePending;
    }

    // A periodic poll can coincide with progress publication on every cycle.
    // Retain that opportunity until acknowledged evidence drains; do not poll
    // through backpressure, add another network worker, or replay a response.
    static boolean actionPollDue(long tick, boolean deferred, boolean evidencePending) {
        return tick > 0 && (tick % POLL_INTERVAL_TICKS == 0 || (deferred && !evidencePending));
    }

    static boolean projectionFlushShouldYieldToHeartbeat(boolean heartbeatPending) {
        return heartbeatPending;
    }

    static boolean eventStreamResyncRequiresWorkflowStop(
        boolean resyncRequired,
        String activeWorkflowId
    ) {
        return resyncRequired && activeWorkflowId != null;
    }

    static String connectorHeartbeatStatus(
        boolean emergencyStopLatched,
        boolean eventStreamResyncRequired,
        String previousTransportError
    ) {
        // Reaching the heartbeat endpoint is itself the recovery probe. A
        // previous poll/transport error remains diagnostic engine metadata but
        // must not make the recovery heartbeat fail the broker's active-status
        // gate forever.
        if (emergencyStopLatched) return "paused";
        if (eventStreamResyncRequired || requiresFreshProducerEpoch(previousTransportError)) {
            return "error";
        }
        return "active";
    }

    static boolean requiresFreshProducerEpoch(String transportError) {
        return transportError != null && (
            transportError.equals(
                "action_delivery_environment_event_batch_http_409_action_event_conflict"
            ) || transportError.equals("action_event_stream_resync_required")
        );
    }

    static boolean heartbeatCursorConflictCanReconcile(
        String transportError,
        boolean projectionPending,
        long sentAcknowledgedEventSequence,
        long latestAcknowledgedEventSequence
    ) {
        return "action_event_stream_resync_required".equals(transportError) &&
            (projectionPending || latestAcknowledgedEventSequence > sentAcknowledgedEventSequence);
    }

    static boolean heartbeatFailureRequiresManifestRepublish(String error) {
        return "action_heartbeat_invalid".equals(error) ||
            "action_manifest_required".equals(error);
    }

    static boolean evidenceEpochRotationAllowed(
        boolean controllerWorkflowActive,
        boolean remoteEnvelopeActive,
        boolean deliveryPending
    ) {
        return !controllerWorkflowActive && !remoteEnvelopeActive && !deliveryPending;
    }

    private static String wireState(State state) {
        return state == null ? null : state.name().toLowerCase(java.util.Locale.ROOT);
    }

    private static String outcome(State state) {
        return switch (state) {
            case SUCCEEDED -> "succeeded";
            case CANCELED -> "request_canceled";
            case TIMED_OUT -> "workflow_timeout";
            case EMERGENCY_STOPPED -> "emergency_stopped";
            case CONNECTOR_OFFLINE -> "connector_offline";
            case PAUSED_MANUAL_OVERRIDE -> "manual_override";
            default -> "failed";
        };
    }

    private static boolean terminal(State state) {
        return state == State.CANCELED || state == State.SUCCEEDED ||
            state == State.FAILED || state == State.TIMED_OUT ||
            state == State.EMERGENCY_STOPPED || state == State.CONNECTOR_OFFLINE;
    }

    private static Map<String, Object> baseNonAnswer() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("answer_authority", false);
        value.put("assistant_answer", false);
        value.put("terminal_eligible", false);
        value.put("raw_content_included", false);
        return value;
    }

    private static String id(String prefix) {
        return prefix + ":" + UUID.randomUUID();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map
            ? new LinkedHashMap<>((Map<String, Object>) map)
            : new LinkedHashMap<>();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : List.of();
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String text ? text.trim() : "";
    }

    private static String textRequired(Map<String, Object> map, String key) {
        String value = text(map, key);
        if (value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private static String nullableText(Object value) {
        return value instanceof String text && !text.isBlank() ? text.trim() : null;
    }

    private static Number number(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) throw new IllegalArgumentException("Missing " + key);
        return number;
    }

    private static long longNumber(Map<String, Object> map, String key) {
        return number(map, key).longValue();
    }

    private static long nonnegativeLong(
        Map<String, Object> map,
        String key,
        long fallback
    ) {
        Object value = map.get(key);
        if (!(value instanceof Number number)) return fallback;
        return Math.max(0L, number.longValue());
    }

    private static boolean positiveMeasurement(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number && number.doubleValue() > 0;
    }

    @Override
    public void close() {
        emergencyStopLatched = true;
        remoteViabilityGuardianArmed = false;
        bridge.disarmViabilityGuardian();
        controller.emergencyStop("The player-action connector stopped.");
        bridge.releaseAll();
        publishLocalStatus();
        network.shutdownNow();
        temporalDeliveryLane.close();
        criticalDeliveryNetwork.shutdownNow();
        projectionDeliveryNetwork.shutdownNow();
        if (http != null) http.close();
    }
}
