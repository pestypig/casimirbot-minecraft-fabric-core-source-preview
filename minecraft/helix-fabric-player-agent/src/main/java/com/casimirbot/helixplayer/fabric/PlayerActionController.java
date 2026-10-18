package com.casimirbot.helixplayer.fabric;

import static com.casimirbot.helixplayer.fabric.PlayerActionWorkflow.*;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Supplier;

public final class PlayerActionController {
    private static final int INTERACTION_FOCUS_ACQUISITION_TICKS = 10;
    private static final long EQUIP_CONFIRMATION_TICKS = 20;
    private final ControlBridge bridge;
    private final EventListener listener;
    private final Runnable releaseOwnedControls;
    private ActionRequest active;
    record TemporalClockSample(String origin, long tick, double elapsedMs) {}
    private TemporalPlanWindow temporalWindow;
    private Supplier<TemporalClockSample> temporalClock;
    private long lastTemporalExecutionTick = -1;
    private String temporalStabilizationNode;
    private boolean temporalStabilizing;
    private TemporalPlanWindow temporalSuccessor;
    private String temporalSuccessorStabilizationNode;
    private long temporalResidentStartTick;
    private State state;
    private long sequence;
    private long elapsedTicks;
    private long actionTicks;
    private int jumpPulses;
    private int confirmedJumps;
    private long lastJumpPulseTick;
    private double jumpStartY;
    private boolean walkJumpArcOwned;
    private Double actionStartX;
    private Double actionStartY;
    private Double actionStartZ;
    private Float lookStartYaw;
    private Float lookStartPitch;
    private Float lookTargetYaw;
    private Float lookTargetPitch;
    private double navigationClosestDistance;
    private int navigationNoProgressTicks;
    private boolean oneShotAttempted;
    private int interactionAttemptCount;
    private HandObservation interactionHandBefore = HandObservation.unavailable();
    private String trackingTargetRef;
    private String trackingTargetTypeId;
    private long trackingSamples;
    private long trackingRetainedTicks;
    private long trackingLossTicks;
    private long trackingLineOfSightTicks;
    private long trackingReacquisitions;
    private long trackingParticleHandoffs;
    private int trackingConsecutiveLossTicks;
    private boolean trackingWasMissing;
    private double trackingErrorSum;
    private double trackingMaxError;
    private double trackingFinalYawError;
    private double trackingFinalPitchError;
    private long[] trackingErrorHistogram = new long[181];
    private int attackPulses;
    private int attackConfirmedTransitions;
    private int attackRejectedPulses;
    private boolean attackAwaitingTransition;
    private double attackHealthBeforePulse;
    private int attackHurtTimeBeforePulse;
    private long attackLastPulseTick;
    private Map<String, Object> lastMeasurements = Map.of();

    public PlayerActionController(ControlBridge bridge, EventListener listener) {
        this(bridge, listener, bridge::releaseAll);
    }

    PlayerActionController(
        ControlBridge bridge,
        EventListener listener,
        Runnable releaseOwnedControls
    ) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.releaseOwnedControls = Objects.requireNonNull(
            releaseOwnedControls,
            "releaseOwnedControls"
        );
    }

    public synchronized boolean start(ActionRequest request) {
        Objects.requireNonNull(request, "request");
        if (active != null && !terminal(state)) return false;
        active = request;
        temporalWindow = null;
        temporalClock = null;
        lastTemporalExecutionTick = -1;
        temporalStabilizationNode = null;
        temporalStabilizing = false;
        temporalSuccessor = null;
        temporalSuccessorStabilizationNode = null;
        state = State.RUNNING;
        sequence = 0;
        elapsedTicks = 0;
        actionTicks = 0;
        jumpPulses = 0;
        confirmedJumps = 0;
        lastJumpPulseTick = -1;
        jumpStartY = 0;
        walkJumpArcOwned = false;
        actionStartX = null;
        actionStartY = null;
        actionStartZ = null;
        lookStartYaw = null;
        lookStartPitch = null;
        lookTargetYaw = null;
        lookTargetPitch = null;
        navigationClosestDistance = Double.POSITIVE_INFINITY;
        navigationNoProgressTicks = 0;
        oneShotAttempted = false;
        interactionAttemptCount = 0;
        interactionHandBefore = HandObservation.unavailable();
        trackingTargetRef = null;
        trackingTargetTypeId = null;
        trackingSamples = 0;
        trackingRetainedTicks = 0;
        trackingLossTicks = 0;
        trackingLineOfSightTicks = 0;
        trackingReacquisitions = 0;
        trackingParticleHandoffs = 0;
        trackingConsecutiveLossTicks = 0;
        trackingWasMissing = false;
        trackingErrorSum = 0;
        trackingMaxError = 0;
        trackingFinalYawError = 0;
        trackingFinalPitchError = 0;
        trackingErrorHistogram = new long[181];
        attackPulses = 0;
        attackConfirmedTransitions = 0;
        attackRejectedPulses = 0;
        attackAwaitingTransition = false;
        attackHealthBeforePulse = 0;
        attackHurtTimeBeforePulse = 0;
        attackLastPulseTick = -1;
        lastMeasurements = Map.of();
        bridge.expectScreenOpen(false);
        bridge.beginWorkflow(request.actionKind(), request.arguments(), request.controlEngine());
        emit("workflow.started", 0.0, "The admitted player workflow started.", false, false);
        return true;
    }

    public synchronized void tick() {
        if (active == null || state != State.RUNNING) return;
        bridge.expectScreenOpen(
            "interact".equals(active.actionKind()) && oneShotAttempted
        );
        PlayerSnapshot snapshot = bridge.snapshot();
        if (!snapshot.connected()) {
            settle(
                State.CONNECTOR_OFFLINE,
                "workflow.failed",
                "The Minecraft client disconnected while the workflow was active."
            );
            return;
        }
        if (snapshot.manualInputDetected()) {
            handleManualOverride(snapshot.manualInputReason());
            return;
        }
        // A bounded walk consumes its final input tick at the duration ceiling.
        // Its next tick is observation-only: measure the resulting motion and
        // release controls before treating the workflow as timed out.
        if (++elapsedTicks > active.maxDurationTicks() && !walkCompletionPending()) {
            settle(
                State.TIMED_OUT,
                "workflow.timed_out",
                "The workflow reached its admitted duration ceiling."
            );
            return;
        }
        try {
            if (temporalWindow != null) {
                TemporalClockSample clock = temporalClock.get();
                if (temporalSuccessor != null && temporalWindow.handoffDue(clock.tick())) {
                    if (!temporalWindow.handoffAllowed(clock.origin(), clock.tick(), clock.elapsedMs())) {
                        settle(State.CANCELED, "workflow.canceled", "temporal_handoff_clock_or_deadline_invalid");
                        return;
                    }
                    temporalWindow = temporalSuccessor;
                    temporalStabilizationNode = temporalSuccessorStabilizationNode;
                    temporalSuccessor = null;
                    temporalSuccessorStabilizationNode = null;
                }
                TemporalPlanWindow.Observation window = temporalStabilizing
                    ? temporalWindow.observeStabilization(clock.origin(), clock.tick(), clock.elapsedMs())
                    : temporalWindow.observe(clock.origin(), clock.tick(), clock.elapsedMs());
                if (window.mustStabilize() || window.decisionRequired()) {
                    Map<String, Object> measurements = new LinkedHashMap<>(lastMeasurements);
                    measurements.put("temporal_window_reason", window.reason());
                    measurements.put("temporal_remaining_ticks", window.remainingTicks());
                    lastMeasurements = Map.copyOf(measurements);
                }
                if (window.mustStabilize()) {
                    TemporalPlanWindow.Observation stabilization = window;
                    if (!temporalStabilizing && "temporal_runway_exhausted".equals(window.reason()) &&
                        temporalStabilizationNode != null) {
                        stabilization = temporalWindow.observeStabilization(clock.origin(), clock.tick(), clock.elapsedMs());
                    }
                    if (!temporalStabilizing && "temporal_runway_exhausted".equals(window.reason()) &&
                        temporalStabilizationNode != null &&
                        !stabilization.mustStabilize() &&
                        bridge.enterWorkflowStabilization(active.actionKind(), temporalStabilizationNode)) {
                        temporalStabilizing = true;
                    } else {
                        String reason = stabilization.mustStabilize() ? stabilization.reason() : window.reason();
                        Map<String, Object> measurements = new LinkedHashMap<>(lastMeasurements);
                        measurements.put("temporal_window_reason", reason);
                        measurements.put("temporal_remaining_ticks", 0L);
                        lastMeasurements = Map.copyOf(measurements);
                        settle(State.CANCELED, "workflow.canceled", reason);
                        return;
                    }
                }
                if (window.decisionRequired()) emit("workflow.progress", 0.0,
                    "The admitted temporal window requires a new decision.", false, false);
                // A repeated sample can update deadline/stop checks, but cannot
                // execute the same native tick twice (including entry pulses).
                long residentTick = clock.tick() - temporalResidentStartTick;
                if (residentTick == lastTemporalExecutionTick) return;
                if (lastTemporalExecutionTick >= 0 && residentTick > lastTemporalExecutionTick + 1) {
                    Map<String, Object> measurements = new LinkedHashMap<>(lastMeasurements);
                    measurements.put("temporal_previous_executed_tick", lastTemporalExecutionTick);
                    measurements.put("temporal_observed_tick", residentTick);
                    measurements.put("temporal_missed_ticks", residentTick - lastTemporalExecutionTick - 1);
                    lastMeasurements = Map.copyOf(measurements);
                    settle(State.CANCELED, "workflow.canceled", "temporal_tick_gap");
                    return;
                }
                lastTemporalExecutionTick = residentTick;
                // The first admitted sample can arrive after the plan's source
                // tick. Keep the native sequence cursor on that source-relative
                // address; call count is not a substitute for the plan clock.
                actionTicks = residentTick;
            }
            actionTicks++;
            runAction(snapshot);
        } catch (IllegalArgumentException error) {
            settle(State.FAILED, "workflow.failed", error.getMessage());
        } catch (RuntimeException error) {
            java.util.List<String> failureTrace = new java.util.ArrayList<>();
            for (StackTraceElement frame : error.getStackTrace()) {
                if (!frame.getClassName().startsWith("com.casimirbot.")) continue;
                failureTrace.add(frame.toString());
                if (failureTrace.size() >= 4) break;
            }
            String failureSite = error.getStackTrace().length > 0
                ? error.getStackTrace()[0].toString()
                : "unavailable";
            lastMeasurements = Map.of(
                "executor_failure_class",
                error.getClass().getSimpleName(),
                "executor_failure_site",
                failureSite,
                "executor_failure_trace",
                java.util.List.copyOf(failureTrace)
            );
            settle(
                State.FAILED,
                "workflow.failed",
                "The native Fabric executor could not complete the admitted action (" +
                    error.getClass().getSimpleName() + ")."
            );
        }
    }

    /** Internal initial-window attachment only; never attaches to another action
     * or changes a running window. Runtime admission/delivery is not implemented
     * yet and must not advertise this as rolling-extension support. */
    synchronized boolean attachTemporalWindow(String actionRequestId, TemporalPlanWindow window,
                                              Supplier<TemporalClockSample> clock) {
        return attachTemporalWindow(actionRequestId, window, clock, null);
    }

    synchronized boolean attachTemporalWindow(String actionRequestId, TemporalPlanWindow window,
                                              Supplier<TemporalClockSample> clock, String admittedStabilizationNode) {
        if (active == null || state != State.RUNNING || actionTicks != 0 || temporalWindow != null ||
            !active.actionRequestId().equals(actionRequestId) ||
            !("execute_sequence".equals(active.actionKind()) || "execute_reactive_program".equals(active.actionKind()))) return false;
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(clock, "clock");
        temporalWindow = window;
        temporalResidentStartTick = window.sourceStartTick();
        temporalClock = clock;
        temporalStabilizationNode = admittedStabilizationNode;
        return true;
    }

    /** Internal, already-admitted serial successor. The runtime must verify
     * source/compiler/task/authority identity before this call. */
    synchronized boolean queueTemporalSuccessor(String actionRequestId, String predecessorSequenceId,
        String checkpointId, Map<String, Object> arguments, TemporalPlanWindow next, String stabilizationNode) {
        if (active == null || state != State.RUNNING || temporalWindow == null || temporalStabilizing ||
            temporalSuccessor != null || !active.actionRequestId().equals(actionRequestId) ||
            !"execute_sequence".equals(active.actionKind())) return false;
        TemporalClockSample clock = temporalClock.get();
        if (!temporalWindow.canReserve(next, clock.origin(), clock.tick(), clock.elapsedMs()) ||
            next.sourceStartTick() < temporalResidentStartTick) return false;
        if (!bridge.queueTemporalSequenceSuccessor(predecessorSequenceId, checkpointId, arguments,
            temporalWindow.committedTick() - temporalResidentStartTick, clock.tick() - temporalResidentStartTick,
            temporalWindow.stopTick() - temporalResidentStartTick, next.sourceStartTick() - temporalResidentStartTick)) return false;
        temporalWindow.reserveHandoff();
        temporalSuccessor = next;
        temporalSuccessorStabilizationNode = stabilizationNode;
        Map<String, Object> accepted = new LinkedHashMap<>(lastMeasurements);
        Map<String, Object> acceptanceTiming = new LinkedHashMap<>(Map.of(
            "sequence_id", String.valueOf(arguments.get("sequence_id")),
            "predecessor_sequence_id", predecessorSequenceId,
            "checkpoint_id", checkpointId,
            "accepted_client_tick", clock.tick(),
            "accepted_monotonic_elapsed_ms", clock.elapsedMs(),
            "committed_client_tick", temporalWindow.committedTick(),
            "lead_ticks", temporalWindow.committedTick() - clock.tick(),
            "queue_depth", 1,
            "execution_started", false,
            "terminal_eligible", false
        ));
        acceptanceTiming.put("stop_client_tick", temporalWindow.stopTick());
        accepted.put("temporal_successor_acceptance", Map.copyOf(acceptanceTiming));
        lastMeasurements = Map.copyOf(accepted);
        emit("workflow.progress", progress(), "The resident executor accepted one successor; execution has not started.", false, false);
        return true;
    }

    public synchronized boolean resume(String workflowId) {
        if (
            active == null ||
            state != State.PAUSED_MANUAL_OVERRIDE ||
            !active.workflowId().equals(workflowId)
        ) return false;
        state = State.RUNNING;
        emit("workflow.resumed", progress(), "The paused workflow resumed.", false, false);
        return true;
    }

    public synchronized void renderFrame(long frameNanos) {
        if (active == null || state != State.RUNNING) return;
        String manualInputReason = "track_target".equals(active.actionKind())
            ? bridge.renderCameraTrackingFrame(frameNanos)
            : "execute_reactive_program".equals(active.actionKind())
                ? bridge.renderReactiveProgramFrame(frameNanos)
                : null;
        if (manualInputReason != null && !manualInputReason.isBlank()) {
            handleManualOverride(manualInputReason);
        }
    }

    public synchronized boolean cancel(String workflowId, String reason) {
        if (active == null || terminal(state) || !active.workflowId().equals(workflowId)) {
            return false;
        }
        settle(
            State.CANCELED,
            "workflow.canceled",
            boundedReason(reason, "The workflow was canceled.")
        );
        return true;
    }

    public synchronized boolean emergencyStop(String reason) {
        releaseOwnedControls.run();
        if (active == null || terminal(state)) return false;
        settle(
            State.EMERGENCY_STOPPED,
            "workflow.emergency_stopped",
            boundedReason(reason, "Emergency stop released every client control.")
        );
        return true;
    }

    public synchronized boolean connectorOffline(String reason) {
        if (active == null || terminal(state)) return false;
        settle(
            State.CONNECTOR_OFFLINE,
            "workflow.failed",
            boundedReason(
                reason,
                "The Helix control plane became unreachable; the client released every control."
            )
        );
        return true;
    }

    public synchronized State state() {
        return state;
    }

    /** Tick-local measured progress for the owning serial interpreter. This is
     * not a public event, execution command, or terminal product. */
    synchronized Map<String, Object> measuredProgress() {
        return Map.copyOf(lastMeasurements);
    }

    public synchronized String activeWorkflowId() {
        return active == null || terminal(state) ? null : active.workflowId();
    }

    private void runAction(PlayerSnapshot snapshot) {
        switch (active.actionKind()) {
            case "navigate_to" -> navigate(snapshot);
            case "look_at" -> lookAt(snapshot);
            case "track_target" -> trackTarget(snapshot);
            case "walk" -> walk(snapshot);
            case "jump" -> jump(snapshot);
            case "interact" -> interact();
            case "attack" -> attack(snapshot);
            case "hotbar_select" -> selectHotbar();
            case "equip" -> equip();
            case "follow", "collect", "mine", "place", "craft", "consume", "inventory_transfer" ->
                reusableWorkflow();
            default -> reusableWorkflow();
        }
    }

    private void navigate(PlayerSnapshot snapshot) {
        if (
            "baritone".equals(active.controlEngine()) ||
            bridge.ownsNativeRoutePlanner()
        ) {
            reusableWorkflow();
            return;
        }
        if (actionStartX == null) {
            actionStartX = snapshot.x();
            actionStartY = snapshot.y();
            actionStartZ = snapshot.z();
        }
        Map<String, Object> destination = object(active.arguments().get("destination"));
        double x = number(destination, "x");
        double y = number(destination, "y");
        double z = number(destination, "z");
        double radius = number(active.arguments(), "arrival_radius");
        double dx = x - snapshot.x();
        double dy = y - snapshot.y();
        double dz = z - snapshot.z();
        double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance <= radius) {
            lastMeasurements = Map.of(
                "distance_blocks", distance,
                "arrival_radius", radius,
                "final_x", snapshot.x(),
                "final_y", snapshot.y(),
                "final_z", snapshot.z()
            );
            settle(
                State.SUCCEEDED,
                "workflow.succeeded",
                "The player reached the admitted destination radius."
            );
            return;
        }
        if (distance + 0.02 < navigationClosestDistance) {
            navigationClosestDistance = distance;
            navigationNoProgressTicks = 0;
        } else {
            navigationNoProgressTicks++;
        }
        if (actionTicks > 20 && navigationNoProgressTicks >= 40) {
            lastMeasurements = Map.of(
                "distance_blocks", distance,
                "closest_distance_blocks", navigationClosestDistance,
                "arrival_radius", radius,
                "no_progress_ticks", navigationNoProgressTicks,
                "final_x", snapshot.x(),
                "final_y", snapshot.y(),
                "final_z", snapshot.z()
            );
            settle(
                State.FAILED,
                "workflow.failed",
                "Native Fabric navigation stopped after measured non-progress instead of continuing to circle the destination."
            );
            return;
        }
        double horizontalDistance = Math.sqrt(dx * dx + dz * dz);
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float yawError = Math.abs(wrapDegrees(desiredYaw - snapshot.yaw()));
        bridge.lookAt(x, y, z, 18.0F);
        if (yawError > 12.0F || horizontalDistance <= radius) {
            bridge.applyMovement(MovementInput.released());
        } else {
            LocomotionSafetyEnvelope.Check safety = bridge.checkLocomotionSafety(
                x,
                z,
                6.0,
                false
            );
            if (!safety.decision().admitted()) {
                double traveledX = snapshot.x() - actionStartX;
                double traveledY = snapshot.y() - actionStartY;
                double traveledZ = snapshot.z() - actionStartZ;
                double traveledDistance = Math.sqrt(
                    traveledX * traveledX +
                    traveledY * traveledY +
                    traveledZ * traveledZ
                );
                Map<String, Object> measurements = new LinkedHashMap<>(
                    safety.measurements()
                );
                measurements.put("distance_blocks", traveledDistance);
                measurements.put("final_x", snapshot.x());
                measurements.put("final_y", snapshot.y());
                measurements.put("final_z", snapshot.z());
                measurements.put(
                    "player_motion_performed",
                    traveledDistance >= 0.01
                );
                lastMeasurements = Map.copyOf(measurements);
                settle(
                    State.FAILED,
                    "workflow.failed",
                    "Native navigation stopped before asserting forward control because the local safety envelope refused the next step."
                );
                return;
            }
            bridge.applyMovement(new MovementInput(
                true,
                false,
                false,
                false,
                snapshot.horizontalCollision() && snapshot.onGround(),
                bool(active.arguments(), "allow_sprint") &&
                    distance > Math.max(3.0, radius + 1.5)
            ));
        }
        if (actionTicks == 1 || actionTicks % 20 == 0) {
            double initialBound = Math.max(
                radius + 1.0,
                numberOr(active.arguments(), "initial_distance", distance)
            );
            emit(
                "workflow.progress",
                Math.max(0.0, Math.min(0.99, 1.0 - (distance / initialBound))),
                "The player is navigating toward the admitted destination.",
                false,
                false
            );
        }
    }

    private void lookAt(PlayerSnapshot snapshot) {
        Map<String, Object> target = object(active.arguments().get("target"));
        String targetKind = text(target, "target_kind");
        if ("current_focus".equals(targetKind)) {
            lastMeasurements = Map.of(
                "target_kind", "current_focus",
                "view_retained", true,
                "final_yaw", (double) snapshot.yaw(),
                "final_pitch", (double) snapshot.pitch()
            );
            settle(State.SUCCEEDED, "workflow.succeeded", "The current player focus was retained.");
            return;
        }
        if ("relative_rotation".equals(targetKind)) {
            relativeLook(snapshot, target);
            return;
        }
        if (!"position".equals(targetKind)) {
            throw new IllegalArgumentException(
                "The client needs a resolved position before it can look at this subject."
            );
        }
        Map<String, Object> position = object(target.get("position"));
        double x = number(position, "x");
        double y = number(position, "y");
        double z = number(position, "z");
        double dx = x - snapshot.x();
        double dy = y - snapshot.eyeY();
        double dz = z - snapshot.z();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double desiredYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double desiredPitch = -Math.toDegrees(Math.atan2(dy, horizontal));
        double yawError = Math.abs(wrapDegrees(desiredYaw - snapshot.yaw()));
        double pitchError = Math.abs(desiredPitch - snapshot.pitch());
        if (yawError <= 2.0 && pitchError <= 2.0) {
            lastMeasurements = Map.of(
                "target_kind", "position",
                "yaw_error_degrees", yawError,
                "pitch_error_degrees", pitchError,
                "target_yaw", desiredYaw,
                "target_pitch", desiredPitch,
                "final_yaw", (double) snapshot.yaw(),
                "final_pitch", (double) snapshot.pitch()
            );
            settle(
                State.SUCCEEDED,
                "workflow.succeeded",
                String.format(
                    java.util.Locale.ROOT,
                    "The player view reached the admitted target (yaw error %.2f°, pitch error %.2f°).",
                    yawError,
                    pitchError
                )
            );
            return;
        }
        bridge.lookAt(
            x,
            y,
            z,
            (float) number(active.arguments(), "max_turn_degrees_per_tick")
        );
        if (actionTicks == 1 || actionTicks % 5 == 0) {
            emit(
                "workflow.progress",
                null,
                "The player view is turning toward the admitted position.",
                false,
                false
            );
        }
    }

    private void relativeLook(PlayerSnapshot snapshot, Map<String, Object> target) {
        double requestedYawDelta = number(target, "yaw_delta_degrees");
        double requestedPitchDelta = number(target, "pitch_delta_degrees");
        if (lookTargetYaw == null || lookTargetPitch == null) {
            lookStartYaw = snapshot.yaw();
            lookStartPitch = snapshot.pitch();
            lookTargetYaw = (float) wrapDegrees(snapshot.yaw() + requestedYawDelta);
            lookTargetPitch = (float) clamp(
                snapshot.pitch() + requestedPitchDelta,
                -90.0,
                90.0
            );
        }
        double yawError = Math.abs(wrapDegrees(lookTargetYaw - snapshot.yaw()));
        double pitchError = Math.abs(lookTargetPitch - snapshot.pitch());
        if (yawError <= 0.5 && pitchError <= 0.5) {
            double appliedYawDelta = wrapDegrees(snapshot.yaw() - lookStartYaw);
            double appliedPitchDelta = snapshot.pitch() - lookStartPitch;
            lastMeasurements = Map.ofEntries(
                Map.entry("target_kind", "relative_rotation"),
                Map.entry("requested_yaw_delta_degrees", requestedYawDelta),
                Map.entry("requested_pitch_delta_degrees", requestedPitchDelta),
                Map.entry("initial_yaw", (double) lookStartYaw),
                Map.entry("initial_pitch", (double) lookStartPitch),
                Map.entry("target_yaw", (double) lookTargetYaw),
                Map.entry("target_pitch", (double) lookTargetPitch),
                Map.entry("final_yaw", (double) snapshot.yaw()),
                Map.entry("final_pitch", (double) snapshot.pitch()),
                Map.entry("applied_yaw_delta_degrees", appliedYawDelta),
                Map.entry("applied_pitch_delta_degrees", appliedPitchDelta),
                Map.entry("yaw_error_degrees", yawError),
                Map.entry("pitch_error_degrees", pitchError)
            );
            settle(
                State.SUCCEEDED,
                "workflow.succeeded",
                String.format(
                    java.util.Locale.ROOT,
                    "The player view completed the admitted relative rotation (yaw %.2f degrees, pitch %.2f degrees).",
                    appliedYawDelta,
                    appliedPitchDelta
                )
            );
            return;
        }
        bridge.lookTo(
            lookTargetYaw,
            lookTargetPitch,
            (float) number(active.arguments(), "max_turn_degrees_per_tick")
        );
        if (actionTicks == 1 || actionTicks % 5 == 0) {
            emit(
                "workflow.progress",
                null,
                "The player view is applying the admitted relative rotation.",
                false,
                false
            );
        }
    }

    private void trackTarget(PlayerSnapshot snapshot) {
        double stopBelowHealth = number(active.arguments(), "stop_below_health");
        if (snapshot.health() < stopBelowHealth) {
            lastMeasurements = Map.ofEntries(
                Map.entry("measured_health", (double) snapshot.health()),
                Map.entry("stop_below_health", stopBelowHealth),
                Map.entry("tracking_completed", false),
                Map.entry("safety_interrupted", true),
                Map.entry("interrupt_reason", "health_floor_crossed")
            );
            settle(
                State.SUCCEEDED,
                "workflow.succeeded",
                "Camera tracking safely interrupted because measured player health crossed the admitted floor."
            );
            return;
        }

        Map<String, Object> target = object(active.arguments().get("target"));
        String targetKind = text(target, "target_kind");
        String aimPoint = text(active.arguments(), "aim_point");
        double maxDistance = number(active.arguments(), "max_acquisition_distance");
        boolean requireLineOfSight = bool(active.arguments(), "require_line_of_sight");
        TargetObservation observation = bridge.observeTarget(
            target,
            trackingTargetRef,
            aimPoint,
            maxDistance,
            requireLineOfSight
        );
        trackingSamples++;

        if (!observation.available() || !observation.alive()) {
            bridge.clearCameraTrackingTarget();
            trackingLossTicks++;
            trackingConsecutiveLossTicks++;
            trackingWasMissing = true;
            if (trackingConsecutiveLossTicks > integer(active.arguments(), "reacquire_ticks")) {
                lastMeasurements = trackingMeasurements(targetKind, requireLineOfSight, false);
                settle(
                    State.FAILED,
                    "workflow.failed",
                    trackingTargetRef == null
                        ? "No matching " + trackingSubject(targetKind) +
                            " could be acquired inside the admitted tracking envelope."
                        : "The locked " + trackingSubject(targetKind) +
                            " left the admitted tracking envelope beyond its reacquisition grace."
                );
                return;
            }
        } else {
            if (trackingTargetRef == null) {
                trackingTargetRef = observation.targetRef();
                trackingTargetTypeId = observation.targetTypeId();
            } else if (!trackingTargetRef.equals(observation.targetRef())) {
                lastMeasurements = trackingMeasurements(targetKind, requireLineOfSight, false);
                settle(
                    State.FAILED,
                    "workflow.failed",
                    "The connector attempted to substitute a different " +
                        trackingSubject(targetKind) + " for the locked tracking target."
                );
                return;
            }
            trackingParticleHandoffs = Math.max(
                trackingParticleHandoffs,
                observation.handoffCount()
            );
            if (trackingWasMissing) trackingReacquisitions++;
            trackingWasMissing = false;
            trackingConsecutiveLossTicks = 0;
            trackingRetainedTicks++;
            if (observation.visible()) trackingLineOfSightTicks++;

            double predictionTicks = integer(active.arguments(), "prediction_ticks");
            double targetX = observation.x() + observation.velocityX() * predictionTicks;
            double targetY = observation.y() + observation.velocityY() * predictionTicks;
            double targetZ = observation.z() + observation.velocityZ() * predictionTicks;
            double dx = targetX - snapshot.x();
            double dy = targetY - snapshot.eyeY();
            double dz = targetZ - snapshot.z();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            double desiredYaw = Math.toDegrees(Math.atan2(-dx, dz));
            double desiredPitch = -Math.toDegrees(Math.atan2(dy, horizontal));
            trackingFinalYawError = Math.abs(wrapDegrees(desiredYaw - snapshot.yaw()));
            trackingFinalPitchError = Math.abs(desiredPitch - snapshot.pitch());
            double angularError = Math.min(
                180,
                Math.hypot(trackingFinalYawError, trackingFinalPitchError)
            );
            trackingErrorSum += angularError;
            trackingMaxError = Math.max(trackingMaxError, angularError);
            trackingErrorHistogram[(int) Math.ceil(angularError)]++;

            bridge.updateCameraTrackingTarget(
                targetX,
                targetY,
                targetZ,
                (float) number(active.arguments(), "max_turn_degrees_per_tick"),
                (float) number(
                    active.arguments(),
                    "max_angular_acceleration_degrees_per_tick_squared"
                ),
                (float) number(active.arguments(), "deadband_degrees")
            );
        }

        long durationTicks = Math.max(
            1,
            (long) Math.ceil(number(active.arguments(), "max_duration_ms") / 50.0)
        );
        if (actionTicks >= durationTicks) {
            if (trackingTargetRef == null || trackingRetainedTicks < 1) {
                lastMeasurements = trackingMeasurements(targetKind, requireLineOfSight, false);
                settle(
                    State.FAILED,
                    "workflow.failed",
                    "No matching " + trackingSubject(targetKind) +
                        " was measured during the admitted tracking interval."
                );
                return;
            }
            lastMeasurements = trackingMeasurements(targetKind, requireLineOfSight, true);
            settle(
                State.SUCCEEDED,
                "workflow.succeeded",
                String.format(
                    java.util.Locale.ROOT,
                    "The camera retained the locked %s target for %d of %d measured ticks (mean error %.2f degrees, p95 %.2f degrees).",
                    trackingTargetTypeId,
                    trackingRetainedTicks,
                    trackingSamples,
                    trackingRetainedTicks == 0 ? 0 : trackingErrorSum / trackingRetainedTicks,
                    trackingP95Error()
                )
            );
            return;
        }
        if (actionTicks == 1 || actionTicks % 20 == 0) {
            lastMeasurements = trackingMeasurements(targetKind, requireLineOfSight, false);
            emit(
                "workflow.progress",
                Math.min(0.99, (double) actionTicks / durationTicks),
                trackingTargetRef == null
                    ? "The camera tracker is acquiring the admitted entity target."
                    : "The camera tracker is retaining the locked entity target.",
                false,
                false
            );
        }
    }

    private void attack(PlayerSnapshot snapshot) {
        String targetRef = text(active.arguments(), "target_ref");
        String expectedTypeId = text(active.arguments(), "target_entity_type_id");
        if (!"hostile".equals(text(active.arguments(), "target_classification"))) {
            throw new IllegalArgumentException(
                "Combat v1 admits only a target explicitly classified as hostile."
            );
        }
        if (bool(active.arguments(), "friendly_fire")) {
            throw new IllegalArgumentException("Combat v1 requires friendly_fire=false.");
        }
        double stopBelowHealth = number(active.arguments(), "stop_below_health");
        if (snapshot.health() < stopBelowHealth) {
            lastMeasurements = attackMeasurements(
                targetRef,
                expectedTypeId,
                null,
                false,
                true,
                "health_floor_crossed"
            );
            settle(
                State.SUCCEEDED,
                "workflow.succeeded",
                "The exact-target attack safely stopped because measured player health crossed the admitted floor."
            );
            return;
        }

        double maxDistance = number(active.arguments(), "max_acquisition_distance");
        boolean requireLineOfSight = bool(active.arguments(), "require_line_of_sight");
        CombatTargetObservation observation = bridge.observeCombatTarget(
            targetRef,
            expectedTypeId,
            maxDistance,
            requireLineOfSight
        );
        if (!observation.available()) {
            lastMeasurements = attackMeasurements(
                targetRef,
                expectedTypeId,
                observation,
                false,
                false,
                "exact_target_unavailable"
            );
            settle(
                State.FAILED,
                "workflow.failed",
                "The locked combat target became stale, occluded, out of reach, or unavailable; no substitute target was selected."
            );
            return;
        }
        if (
            !targetRef.equals(observation.targetRef()) ||
            !expectedTypeId.equals(observation.targetTypeId())
        ) {
            lastMeasurements = attackMeasurements(
                targetRef,
                expectedTypeId,
                observation,
                false,
                false,
                "target_identity_mismatch"
            );
            settle(
                State.FAILED,
                "workflow.failed",
                "The bridge returned a different combat target incarnation or entity type."
            );
            return;
        }
        if (!observation.hostile()) {
            lastMeasurements = attackMeasurements(
                targetRef,
                expectedTypeId,
                observation,
                false,
                false,
                "target_not_hostile"
            );
            settle(
                State.FAILED,
                "workflow.failed",
                "The locked entity is no longer classified as hostile; the attack was refused."
            );
            return;
        }
        if (requireLineOfSight && !observation.visible()) {
            lastMeasurements = attackMeasurements(
                targetRef,
                expectedTypeId,
                observation,
                false,
                false,
                "line_of_sight_lost"
            );
            settle(State.FAILED, "workflow.failed", "Line of sight to the exact target was lost.");
            return;
        }

        if (attackAwaitingTransition && (
            !observation.alive() ||
            observation.health() < attackHealthBeforePulse ||
            observation.hurtTimeTicks() > attackHurtTimeBeforePulse
        )) {
            attackConfirmedTransitions++;
            attackAwaitingTransition = false;
        }
        if (!observation.alive()) {
            lastMeasurements = attackMeasurements(
                targetRef,
                expectedTypeId,
                observation,
                true,
                false,
                "target_dead"
            );
            settle(
                State.SUCCEEDED,
                "workflow.succeeded",
                "The admitted hostile target reached an observed death state after exact-target attacks."
            );
            return;
        }

        bridge.lookAt(observation.x(), observation.y(), observation.z(), 30.0F);
        double dx = observation.x() - snapshot.x();
        double dy = observation.y() - snapshot.eyeY();
        double dz = observation.z() - snapshot.z();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double targetPitch = -Math.toDegrees(Math.atan2(dy, horizontal));
        double angularError = Math.hypot(
            wrapDegrees(targetYaw - snapshot.yaw()),
            targetPitch - snapshot.pitch()
        );
        if (angularError > 8.0) {
            if (actionTicks == 1 || actionTicks % 5 == 0) {
                lastMeasurements = attackMeasurements(
                    targetRef,
                    expectedTypeId,
                    observation,
                    false,
                    false,
                    "aligning_exact_target"
                );
                emit(
                    "workflow.progress",
                    progress(),
                    "The player is aligning to the exact admitted hostile before attacking.",
                    false,
                    false
                );
            }
            return;
        }

        if (!observation.withinAttackRange()) {
            if (actionTicks == 1 || actionTicks % 5 == 0) {
                lastMeasurements = attackMeasurements(
                    targetRef,
                    expectedTypeId,
                    observation,
                    false,
                    false,
                    "waiting_for_vanilla_reach"
                );
                emit(
                    "workflow.progress",
                    progress(),
                    "The exact hostile remains acquired; the player is waiting for vanilla attack reach before pulsing.",
                    false,
                    false
                );
            }
            return;
        }

        int maximumPulses = integer(active.arguments(), "max_attack_pulses");
        if (attackPulses >= maximumPulses) {
            if (attackAwaitingTransition && actionTicks - attackLastPulseTick <= 12) return;
            lastMeasurements = attackMeasurements(
                targetRef,
                expectedTypeId,
                observation,
                false,
                false,
                "attack_pulse_budget_exhausted"
            );
            settle(
                State.FAILED,
                "workflow.failed",
                "The admitted attack pulse budget was exhausted while the exact hostile remained alive."
            );
            return;
        }
        double minimumCooldown = number(active.arguments(), "minimum_attack_cooldown");
        if (observation.attackCooldown() < minimumCooldown || attackAwaitingTransition) {
            return;
        }
        attackHealthBeforePulse = observation.health();
        attackHurtTimeBeforePulse = observation.hurtTimeTicks();
        if (!bridge.attackCombatTarget(targetRef)) {
            attackRejectedPulses++;
            lastMeasurements = attackMeasurements(
                targetRef,
                expectedTypeId,
                observation,
                false,
                false,
                "vanilla_attack_rejected"
            );
            settle(
                State.FAILED,
                "workflow.failed",
                "The vanilla client refused the exact-target attack; no retry against another entity was attempted."
            );
            return;
        }
        attackPulses++;
        attackLastPulseTick = actionTicks;
        attackAwaitingTransition = true;
        lastMeasurements = attackMeasurements(
            targetRef,
            expectedTypeId,
            observation,
            false,
            false,
            "attack_pulse_sent"
        );
        emit(
            "workflow.progress",
            Math.min(0.99, (double) attackPulses / maximumPulses),
            "The vanilla client sent one cooldown-admitted pulse to the exact hostile target.",
            false,
            false
        );
    }

    private Map<String, Object> attackMeasurements(
        String targetRef,
        String expectedTypeId,
        CombatTargetObservation observation,
        boolean targetDefeated,
        boolean safetyInterrupted,
        String reasonCode
    ) {
        Map<String, Object> measured = new LinkedHashMap<>();
        measured.put("target_ref", targetRef);
        measured.put("target_entity_type_id", expectedTypeId);
        measured.put("target_classification", "hostile");
        measured.put("friendly_fire", false);
        measured.put("attack_pulses", attackPulses);
        measured.put("confirmed_hurt_or_health_transitions", attackConfirmedTransitions);
        measured.put("rejected_attack_pulses", attackRejectedPulses);
        measured.put("target_defeated", targetDefeated);
        measured.put("safety_interrupted", safetyInterrupted);
        measured.put("reason_code", reasonCode);
        if (observation != null && observation.available()) {
            measured.put("target_alive", observation.alive());
            measured.put("target_hostile", observation.hostile());
            measured.put("line_of_sight", observation.visible());
            measured.put("within_attack_range", observation.withinAttackRange());
            measured.put("distance_blocks", observation.distance());
            measured.put("target_health", observation.health());
            measured.put("target_max_health", observation.maxHealth());
            measured.put("target_hurt_time_ticks", observation.hurtTimeTicks());
            measured.put("target_death_time_ticks", observation.deathTimeTicks());
            measured.put("attack_cooldown", observation.attackCooldown());
        }
        return Map.copyOf(measured);
    }

    private Map<String, Object> trackingMeasurements(
        String targetKind,
        boolean requireLineOfSight,
        boolean completed
    ) {
        Map<String, Object> measurements = new java.util.LinkedHashMap<>();
        measurements.put("tracking_completed", completed);
        measurements.put("target_kind", targetKind);
        if (trackingTargetRef != null) measurements.put("target_ref", trackingTargetRef);
        if (trackingTargetTypeId != null) {
            measurements.put(
                "particle_type".equals(targetKind)
                    ? "target_particle_type_id"
                    : "target_entity_type_id",
                trackingTargetTypeId
            );
        }
        if ("particle_type".equals(targetKind)) {
            Map<String, Object> target = object(active.arguments().get("target"));
            measurements.put("particle_continuity", text(target, "continuity"));
            measurements.put("particle_handoff_count", trackingParticleHandoffs);
            measurements.put("particle_max_handoffs", integer(target, "max_handoffs"));
        }
        measurements.put("duration_ticks", trackingSamples);
        measurements.put("sample_count", trackingSamples);
        measurements.put("retained_ticks", trackingRetainedTicks);
        measurements.put("target_loss_ticks", trackingLossTicks);
        measurements.put("line_of_sight_retained_ticks", trackingLineOfSightTicks);
        measurements.put("reacquisition_count", trackingReacquisitions);
        measurements.put(
            "mean_angular_error_degrees",
            trackingRetainedTicks == 0 ? 0 : trackingErrorSum / trackingRetainedTicks
        );
        measurements.put("p95_angular_error_degrees", trackingP95Error());
        measurements.put("max_angular_error_degrees", trackingMaxError);
        measurements.put("final_yaw_error_degrees", trackingFinalYawError);
        measurements.put("final_pitch_error_degrees", trackingFinalPitchError);
        measurements.put("line_of_sight_required", requireLineOfSight);
        return Map.copyOf(measurements);
    }

    private static String trackingSubject(String targetKind) {
        return "particle_type".equals(targetKind) ? "particle" : "entity";
    }

    private double trackingP95Error() {
        if (trackingRetainedTicks == 0) return 0;
        long threshold = (long) Math.ceil(trackingRetainedTicks * 0.95);
        long cumulative = 0;
        for (int error = 0; error < trackingErrorHistogram.length; error++) {
            cumulative += trackingErrorHistogram[error];
            if (cumulative >= threshold) {
                // Histogram buckets are upper bounds (ceil(error)). Keep the
                // reported percentile inside the exact observed range so the
                // evidence always preserves the mathematical p95 <= max
                // invariant, including when every sample is sub-degree.
                return Math.min(error, trackingMaxError);
            }
        }
        return 180;
    }

    private void handleManualOverride(String manualInputReason) {
        bridge.expectScreenOpen(false);
        // Interruption adds a causal observation; it must not erase execution
        // facts already collected for the resident result envelope.
        Map<String, Object> interruptedMeasurements = new LinkedHashMap<>(lastMeasurements);
        interruptedMeasurements.put("manual_input_reason", manualInputReason);
        interruptedMeasurements.put("action_ticks_before_override", actionTicks);
        lastMeasurements = Map.copyOf(interruptedMeasurements);
        releaseOwnedControls.run();
        if (active.manualOverridePolicy() == ManualOverridePolicy.PAUSE) {
            state = State.PAUSED_MANUAL_OVERRIDE;
            emit(
                "workflow.manual_override_detected",
                progress(),
                "Manual player input paused the workflow and released controls (reason: " +
                    manualInputReason + ").",
                true,
                true
            );
        } else {
            settle(
                State.CANCELED,
                "workflow.canceled",
                "Manual player input canceled the workflow and released controls (reason: " +
                    manualInputReason + ").",
                true
            );
        }
    }

    /** Observation-only boundary predicate. The next tick still performs all
     * manual/safety and measured-motion checks; it cannot apply another walk
     * input once this duration has been consumed. */
    synchronized boolean walkCompletionPending() {
        return active != null && state == State.RUNNING && "walk".equals(active.actionKind()) &&
            actionTicks >= Math.max(1L, (long) Math.ceil(number(active.arguments(), "duration_ms") / 50.0));
    }

    private void walk(PlayerSnapshot snapshot) {
        if (actionStartX == null) {
            actionStartX = snapshot.x();
            actionStartY = snapshot.y();
            actionStartZ = snapshot.z();
        }
        long durationTicks = Math.max(
            1,
            (long) Math.ceil(number(active.arguments(), "duration_ms") / 50.0)
        );
        if (actionTicks > durationTicks) {
            double dx = snapshot.x() - actionStartX;
            double dy = snapshot.y() - actionStartY;
            double dz = snapshot.z() - actionStartZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double minimumMotion = Math.min(0.1, Math.max(0.01, durationTicks * 0.005));
            if (distance < minimumMotion) {
                settle(
                    State.FAILED,
                    "workflow.failed",
                    "The bounded walk asserted controls but no measurable player motion occurred."
                );
                return;
            }
            lastMeasurements = Map.of(
                "distance_blocks", distance,
                "duration_ticks", durationTicks
            );
            settle(
                State.SUCCEEDED,
                "workflow.succeeded",
                String.format(
                    java.util.Locale.ROOT,
                    "The bounded walk completed with %.3f blocks of measured motion.",
                    distance
                )
            );
            return;
        }
        String direction = text(active.arguments(), "direction");
        double heading = Math.toRadians(snapshot.yaw() + switch (direction) {
            case "back" -> 180;
            case "left" -> -90;
            case "right" -> 90;
            default -> 0;
        });
        double targetX = snapshot.x() - Math.sin(heading);
        double targetZ = snapshot.z() + Math.cos(heading);
        boolean jumpRequested = Boolean.TRUE.equals(active.arguments().get("jump"));
        if (jumpRequested && snapshot.onGround()) {
            walkJumpArcOwned = true;
        }
        LocomotionSafetyEnvelope.Check safety = bridge.checkLocomotionSafety(
            targetX,
            targetZ,
            6.0,
            jumpRequested && walkJumpArcOwned
        );
        if (!safety.decision().admitted()) {
            lastMeasurements = safety.measurements();
            settle(
                State.FAILED,
                "workflow.failed",
                "The bounded walk stopped before asserting movement because the local safety envelope refused the next step."
            );
            return;
        }
        bridge.applyMovement(new MovementInput(
            "forward".equals(direction),
            "back".equals(direction),
            "left".equals(direction),
            "right".equals(direction),
            jumpRequested,
            bool(active.arguments(), "sprint")
        ));
    }

    private void jump(PlayerSnapshot snapshot) {
        int requested = integer(active.arguments(), "count");
        if (jumpPulses > confirmedJumps) {
            if (!snapshot.onGround() || snapshot.y() > jumpStartY + 0.05) {
                confirmedJumps++;
                if (confirmedJumps >= requested) {
                    lastMeasurements = Map.of(
                        "confirmed_jumps", confirmedJumps,
                        "requested_count", requested
                    );
                    settle(
                        State.SUCCEEDED,
                        "workflow.succeeded",
                        "The admitted jump sequence completed with every jump observed airborne."
                    );
                }
                return;
            }
            if (actionTicks - lastJumpPulseTick > 8) {
                settle(
                    State.FAILED,
                    "workflow.failed",
                    "The jump control was asserted but no airborne transition was observed."
                );
            }
            return;
        }
        if (snapshot.onGround()) {
            bridge.pulseJump();
            jumpPulses++;
            lastJumpPulseTick = actionTicks;
            jumpStartY = snapshot.y();
        }
    }

    private void interact() {
        String target = text(active.arguments(), "target");
        String hand = text(active.arguments(), "hand");
        String interaction = text(active.arguments(), "interaction");
        if (!oneShotAttempted) {
            interactionAttemptCount++;
            HandObservation beforeAttempt = bridge.observeHand(hand);
            if (!bridge.interact(target, hand, interaction)) {
                if (interactionAttemptCount >= INTERACTION_FOCUS_ACQUISITION_TICKS) {
                    lastMeasurements = Map.of(
                        "interaction_accepted", false,
                        "target", target,
                        "hand", hand,
                        "interaction", interaction,
                        "focus_acquisition_pending", true,
                        "interaction_attempt_count", interactionAttemptCount,
                        "post_interaction_observed", false
                    );
                    throw new IllegalArgumentException(
                        "No compatible block or entity became available during the bounded focus acquisition window."
                    );
                }
                lastMeasurements = Map.of(
                    "interaction_accepted", false,
                    "target", target,
                    "hand", hand,
                    "interaction", interaction,
                    "focus_acquisition_pending", true,
                    "interaction_attempt_count", interactionAttemptCount,
                    "post_interaction_observed", false
                );
                return;
            }
            interactionHandBefore = beforeAttempt;
            oneShotAttempted = true;
            Map<String, Object> accepted = new LinkedHashMap<>();
            accepted.put("interaction_accepted", true);
            accepted.put("target", target);
            accepted.put("hand", hand);
            accepted.put("interaction", interaction);
            accepted.put("focus_acquisition_pending", false);
            accepted.put("interaction_attempt_count", interactionAttemptCount);
            accepted.put("post_interaction_observed", false);
            lastMeasurements = Map.copyOf(accepted);
            return;
        }

        HandObservation after = bridge.observeHand(hand);
        Map<String, Object> measured = new LinkedHashMap<>();
        measured.put("interaction_accepted", true);
        measured.put("target", target);
        measured.put("hand", hand);
        measured.put("interaction", interaction);
        measured.put("focus_acquisition_pending", false);
        measured.put("interaction_attempt_count", interactionAttemptCount);
        boolean observed = interactionHandBefore.available() && after.available();
        measured.put("post_interaction_observed", observed);
        if (observed) {
            int delta = after.count() - interactionHandBefore.count();
            boolean changed = delta != 0 ||
                !after.itemId().equals(interactionHandBefore.itemId()) ||
                after.damage() != interactionHandBefore.damage();
            int consumed = after.itemId().equals(interactionHandBefore.itemId()) ||
                after.itemId().isBlank()
                ? Math.max(0, -delta)
                : 0;
            measured.put("held_item_id_before", interactionHandBefore.itemId());
            measured.put("held_item_id_after", after.itemId());
            measured.put("held_item_count_before", interactionHandBefore.count());
            measured.put("held_item_count_after", after.count());
            measured.put("held_item_damage_before", interactionHandBefore.damage());
            measured.put("held_item_damage_after", after.damage());
            measured.put("held_item_count_delta", delta);
            measured.put("consumed_item_count", consumed);
            measured.put("inventory_mutations_performed", changed ? 1 : 0);
        } else {
            measured.put("consumed_item_count", 0);
            measured.put("inventory_mutations_performed", 0);
        }
        lastMeasurements = Map.copyOf(measured);
        settle(
            State.SUCCEEDED,
            "workflow.succeeded",
            observed
                ? "The game accepted the interaction and the used hand postcondition was observed."
                : "The game accepted the interaction; no hand postcondition provider was available."
        );
    }

    private void selectHotbar() {
        int slot = integer(active.arguments(), "slot");
        if (!bridge.selectHotbar(slot)) {
            throw new IllegalArgumentException("The requested hotbar slot is unavailable.");
        }
        lastMeasurements = Map.of("selected_slot", slot, "selection_matches", true);
        settle(State.SUCCEEDED, "workflow.succeeded", "The admitted hotbar slot was selected.");
    }

    private void equip() {
        String itemId = text(active.arguments(), "item_id");
        String destination = text(active.arguments(), "destination");
        boolean equipped = actionTicks == 1
            ? bridge.equip(itemId, destination)
            : bridge.equipmentMatches(itemId, destination);
        if (!equipped && actionTicks < EQUIP_CONFIRMATION_TICKS) {
            return;
        }
        if (!equipped) {
            throw new IllegalArgumentException(
                "The requested item could not be equipped in the requested destination."
            );
        }
        lastMeasurements = Map.of(
            "item_id", itemId,
            "destination", destination,
            "equipment_matches", true
        );
        settle(State.SUCCEEDED, "workflow.succeeded", "The requested item was equipped.");
    }

    private void reusableWorkflow() {
        WorkflowStep step = bridge.runWorkflowStep(
            active.actionKind(),
            active.arguments(),
            active.controlEngine(),
            temporalWindow == null ? actionTicks : Math.addExact(lastTemporalExecutionTick, 1)
        );
        boolean residentHandoff = "execute_sequence".equals(active.actionKind()) &&
            step.measurements().get("resident_handoff_count") instanceof Number currentHandoffs &&
            currentHandoffs.longValue() > (lastMeasurements.get("resident_handoff_count") instanceof Number previousHandoffs
                ? previousHandoffs.longValue() : 0L);
        lastMeasurements = step.measurements();
        switch (step.status()) {
            case SUCCEEDED -> {
                if (temporalStabilizing) {
                    settle(State.CANCELED, "workflow.canceled", step.summary());
                } else {
                    settle(State.SUCCEEDED, "workflow.succeeded", step.summary());
                }
            }
            case FAILED -> settle(State.FAILED, "workflow.failed", step.summary());
            case RUNNING -> {
                if (residentHandoff || actionTicks == 1 || actionTicks % 20 == 0) {
                    emit(
                        "workflow.progress",
                        step.progressFraction(),
                        residentHandoff ? "The resident executor began the admitted successor sequence." : step.summary(),
                        false,
                        false
                    );
                }
            }
        }
    }

    private void settle(State next, String eventType, String summary) {
        settle(next, eventType, summary, false);
    }

    private void settle(
        State next,
        String eventType,
        String summary,
        boolean manualOverrideDetected
    ) {
        if (!(next == State.SUCCEEDED && "interact".equals(active.actionKind()))) {
            bridge.expectScreenOpen(false);
        }
        releaseOwnedControls.run();
        state = next;
        emit(
            eventType,
            next == State.SUCCEEDED ? 1.0 : progress(),
            summary,
            manualOverrideDetected,
            true
        );
    }

    private void emit(
        String eventType,
        Double progressFraction,
        String summary,
        boolean manualOverrideDetected,
        boolean controlsReleased
    ) {
        // Queue acceptance belongs to this event only. Clear it before invoking
        // the listener, which can synchronously cancel on evidence backpressure.
        Map<String, Object> eventMeasurements = lastMeasurements;
        if (lastMeasurements.containsKey("temporal_successor_acceptance")) {
            Map<String, Object> retained = new LinkedHashMap<>(lastMeasurements);
            retained.remove("temporal_successor_acceptance");
            lastMeasurements = Map.copyOf(retained);
        }
        listener.onEvent(new WorkflowEvent(
            active.actionRequestId(),
            active.workflowId(),
            sequence++,
            eventType,
            state,
            progressFraction,
            summary,
            eventMeasurements,
            manualOverrideDetected,
            controlsReleased
        ));
    }

    private double progress() {
        if (active == null) return 0.0;
        return Math.min(0.99, (double) elapsedTicks / (double) active.maxDurationTicks());
    }

    private static boolean terminal(State state) {
        return state == State.CANCELED ||
            state == State.SUCCEEDED ||
            state == State.FAILED ||
            state == State.TIMED_OUT ||
            state == State.EMERGENCY_STOPPED ||
            state == State.CONNECTOR_OFFLINE;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("The action arguments are incomplete.");
        }
        return (Map<String, Object>) map;
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("The action argument " + key + " is required.");
        }
        return text;
    }

    private static double number(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("The numeric action argument " + key + " is required.");
        }
        return number.doubleValue();
    }

    private static double numberOr(Map<String, Object> map, String key, double fallback) {
        Object value = map.get(key);
        return value instanceof Number number && Double.isFinite(number.doubleValue())
            ? number.doubleValue()
            : fallback;
    }

    private static int integer(Map<String, Object> map, String key) {
        double value = number(map, key);
        if (Math.rint(value) != value) {
            throw new IllegalArgumentException("The action argument " + key + " must be an integer.");
        }
        return (int) value;
    }

    private static boolean bool(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Boolean bool)) {
            throw new IllegalArgumentException("The boolean action argument " + key + " is required.");
        }
        return bool;
    }

    private static String boundedReason(String reason, String fallback) {
        if (reason == null || reason.isBlank()) return fallback;
        return reason.length() <= 1_000 ? reason : reason.substring(0, 1_000);
    }

    private static float wrapDegrees(float value) {
        float wrapped = value % 360.0F;
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }

    private static double wrapDegrees(double value) {
        double wrapped = value % 360.0;
        if (wrapped >= 180.0) wrapped -= 360.0;
        if (wrapped < -180.0) wrapped += 360.0;
        return wrapped;
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
