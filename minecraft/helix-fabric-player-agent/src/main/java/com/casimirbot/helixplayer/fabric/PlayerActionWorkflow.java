package com.casimirbot.helixplayer.fabric;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PlayerActionWorkflow {
    private PlayerActionWorkflow() {}

    public enum State {
        QUEUED,
        RUNNING,
        PAUSED_MANUAL_OVERRIDE,
        CANCELED,
        SUCCEEDED,
        FAILED,
        TIMED_OUT,
        EMERGENCY_STOPPED,
        CONNECTOR_OFFLINE
    }

    public enum ManualOverridePolicy {
        PAUSE,
        CANCEL;

        static ManualOverridePolicy fromWire(String value) {
            return "pause".equals(value) ? PAUSE : CANCEL;
        }
    }

    public enum WorkflowStepStatus {
        RUNNING,
        SUCCEEDED,
        FAILED
    }

    public record ActionRequest(
        String actionRequestId,
        String workflowId,
        String actionKind,
        Map<String, Object> arguments,
        long maxDurationTicks,
        ManualOverridePolicy manualOverridePolicy,
        String controlEngine
    ) {
        public ActionRequest {
            requireIdentifier(actionRequestId, "actionRequestId");
            requireIdentifier(workflowId, "workflowId");
            requireIdentifier(actionKind, "actionKind");
            arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments"));
            if (maxDurationTicks < 1 || maxDurationTicks > 36_000) {
                throw new IllegalArgumentException("maxDurationTicks must be 1-36000");
            }
            Objects.requireNonNull(manualOverridePolicy, "manualOverridePolicy");
            requireIdentifier(controlEngine, "controlEngine");
        }
    }

    public record PlayerSnapshot(
        boolean connected,
        double x,
        double y,
        double eyeY,
        double z,
        float yaw,
        float pitch,
        float health,
        boolean onGround,
        boolean horizontalCollision,
        boolean manualInputDetected,
        String manualInputReason
    ) {
        public PlayerSnapshot {
            if (!manualInputDetected) manualInputReason = null;
            if (manualInputDetected && (manualInputReason == null || manualInputReason.isBlank())) {
                manualInputReason = "unspecified_manual_input";
            }
        }
    }

    /**
     * One narrow postcondition sample for the hand used by an admitted action.
     * This deliberately exposes only held identity, count and durability, not
     * the player's full inventory or any native client object.
     */
    public record HandObservation(
        boolean available,
        String itemId,
        int count,
        int damage
    ) {
        public HandObservation(boolean available, String itemId, int count) {
            this(available, itemId, count, 0);
        }

        public HandObservation {
            itemId = itemId == null ? "" : itemId;
            if (count < 0 || damage < 0) {
                throw new IllegalArgumentException(
                    "Held item count and damage cannot be negative."
                );
            }
            if (!available) {
                itemId = "";
                count = 0;
                damage = 0;
            }
        }

        public static HandObservation unavailable() {
            return new HandObservation(false, "", 0, 0);
        }
    }

    public record MovementInput(
        boolean forward,
        boolean back,
        boolean left,
        boolean right,
        boolean jump,
        boolean sprint
    ) {
        public static MovementInput released() {
            return new MovementInput(false, false, false, false, false, false);
        }
    }

    /**
     * One bounded, connector-local target sample. targetRef is deliberately an
     * opaque per-client reference; native entity identities never cross the
     * connector contract.
     */
    public record TargetObservation(
        boolean available,
        boolean alive,
        boolean visible,
        String targetRef,
        String targetTypeId,
        double x,
        double y,
        double z,
        double velocityX,
        double velocityY,
        double velocityZ,
        double distance,
        long handoffCount
    ) {
        public TargetObservation(
            boolean available,
            boolean alive,
            boolean visible,
            String targetRef,
            String targetTypeId,
            double x,
            double y,
            double z,
            double velocityX,
            double velocityY,
            double velocityZ,
            double distance
        ) {
            this(
                available, alive, visible, targetRef, targetTypeId,
                x, y, z, velocityX, velocityY, velocityZ, distance, 0
            );
        }

        public TargetObservation {
            if (handoffCount < 0) {
                throw new IllegalArgumentException("Particle handoff count cannot be negative.");
            }
            if (!available) {
                alive = false;
                visible = false;
                targetRef = targetRef == null ? "" : targetRef;
                targetTypeId = targetTypeId == null ? "" : targetTypeId;
            } else {
                requireIdentifier(targetRef, "targetRef");
                requireIdentifier(targetTypeId, "targetTypeId");
                for (double value : new double[] {
                    x, y, z, velocityX, velocityY, velocityZ, distance
                }) {
                    if (!Double.isFinite(value)) {
                        throw new IllegalArgumentException(
                            "Target observations require finite coordinates and velocity."
                        );
                    }
                }
            }
        }

        public static TargetObservation unavailable(String targetRef) {
            return new TargetObservation(
                false, false, false, targetRef, "",
                0, 0, 0, 0, 0, 0, 0, 0
            );
        }
    }

    /**
     * A combat-specific sample for one previously locked entity incarnation.
     * The bridge must never substitute a nearest or crosshair entity for this
     * reference.
     */
    public record CombatTargetObservation(
        boolean available,
        boolean alive,
        boolean hostile,
        boolean visible,
        boolean withinAttackRange,
        String targetRef,
        String targetTypeId,
        double x,
        double y,
        double z,
        double distance,
        double health,
        double maxHealth,
        int hurtTimeTicks,
        int deathTimeTicks,
        double attackCooldown
    ) {
        public CombatTargetObservation {
            targetRef = targetRef == null ? "" : targetRef;
            targetTypeId = targetTypeId == null ? "" : targetTypeId;
            if (!available) {
                alive = false;
                hostile = false;
                visible = false;
                withinAttackRange = false;
                health = 0;
                maxHealth = 0;
                hurtTimeTicks = 0;
                deathTimeTicks = 0;
                attackCooldown = 0;
            } else {
                requireIdentifier(targetRef, "targetRef");
                requireIdentifier(targetTypeId, "targetTypeId");
                for (double value : new double[] {
                    x, y, z, distance, health, maxHealth, attackCooldown
                }) {
                    if (!Double.isFinite(value)) {
                        throw new IllegalArgumentException(
                            "Combat target observations require finite measurements."
                        );
                    }
                }
                if (
                    distance < 0 || health < 0 || maxHealth < 0 ||
                    hurtTimeTicks < 0 || deathTimeTicks < 0 ||
                    attackCooldown < 0 || attackCooldown > 1
                ) {
                    throw new IllegalArgumentException(
                        "Combat target observations contain an out-of-range measurement."
                    );
                }
            }
        }

        public static CombatTargetObservation unavailable(String targetRef) {
            return new CombatTargetObservation(
                false, false, false, false, false, targetRef, "",
                0, 0, 0, 0, 0, 0, 0, 0, 0
            );
        }
    }

    public record WorkflowEvent(
        String actionRequestId,
        String workflowId,
        long sequence,
        String eventType,
        State state,
        Double progressFraction,
        String summary,
        Map<String, Object> measurements,
        boolean manualOverrideDetected,
        boolean controlsReleased
    ) {
        public WorkflowEvent {
            measurements = Map.copyOf(Objects.requireNonNull(measurements, "measurements"));
        }
    }

    public record WorkflowStep(
        WorkflowStepStatus status,
        Double progressFraction,
        String summary,
        Map<String, Object> measurements
    ) {
        public WorkflowStep {
            Objects.requireNonNull(status, "status");
            if (progressFraction != null &&
                (!Double.isFinite(progressFraction) || progressFraction < 0 || progressFraction > 1)) {
                throw new IllegalArgumentException("progressFraction must be null or 0-1");
            }
            if (summary == null || summary.isBlank() || summary.length() > 4_000) {
                throw new IllegalArgumentException("summary must be bounded");
            }
            measurements = Map.copyOf(Objects.requireNonNull(measurements, "measurements"));
        }

        public static WorkflowStep running(
            Double progressFraction,
            String summary,
            Map<String, Object> measurements
        ) {
            return new WorkflowStep(
                WorkflowStepStatus.RUNNING,
                progressFraction,
                summary,
                measurements
            );
        }

        public static WorkflowStep succeeded(String summary, Map<String, Object> measurements) {
            return new WorkflowStep(WorkflowStepStatus.SUCCEEDED, 1.0, summary, measurements);
        }

        public static WorkflowStep failed(String summary, Map<String, Object> measurements) {
            return new WorkflowStep(WorkflowStepStatus.FAILED, null, summary, measurements);
        }
    }

    @FunctionalInterface
    public interface EventListener {
        void onEvent(WorkflowEvent event);
    }

    public interface ControlBridge {
        PlayerSnapshot snapshot();

        void applyMovement(MovementInput movement);

        void lookAt(double x, double y, double z, float maxDegreesPerTick);

        void lookTo(float yaw, float pitch, float maxDegreesPerTick);

        default TargetObservation observeTarget(
            Map<String, Object> target,
            String lockedTargetRef,
            String aimPoint,
            double maxDistance,
            boolean requireLineOfSight
        ) {
            return TargetObservation.unavailable(lockedTargetRef);
        }

        default CombatTargetObservation observeCombatTarget(
            String targetRef,
            String expectedEntityTypeId,
            double maxDistance,
            boolean requireLineOfSight
        ) {
            return CombatTargetObservation.unavailable(targetRef);
        }

        default boolean attackCombatTarget(String targetRef) {
            return false;
        }

        default void updateCameraTrackingTarget(
            double x,
            double y,
            double z,
            float maxDegreesPerTick,
            float maxAccelerationDegreesPerTickSquared,
            float deadbandDegrees
        ) {
            lookAt(x, y, z, maxDegreesPerTick);
        }

        default String renderCameraTrackingFrame(long frameNanos) {
            return null;
        }

        default String renderReactiveProgramFrame(long frameNanos) {
            return null;
        }

        default void clearCameraTrackingTarget() {}

        void pulseJump();

        boolean interact(String target, String hand, String interaction);

        default HandObservation observeHand(String hand) {
            return HandObservation.unavailable();
        }

        boolean selectHotbar(int slot);

        boolean equip(String itemId, String destination);

        default boolean equipmentMatches(String itemId, String destination) {
            return false;
        }

        default boolean supportsControlEngine(String controlEngine) {
            return "native_fabric".equals(controlEngine);
        }

        default boolean ownsNativeRoutePlanner() {
            return false;
        }

        default void expectScreenOpen(boolean expected) {}

        default LocomotionSafetyEnvelope.Check checkLocomotionSafety(
            double targetX,
            double targetZ,
            double minimumHealth,
            boolean controlledJumpArc
        ) {
            return new LocomotionSafetyEnvelope.Check(
                LocomotionSafetyEnvelope.Decision.admit(),
                Map.of("reason_code", "locomotion_safety_not_required_by_bridge")
            );
        }

        default void beginWorkflow(
            String actionKind,
            Map<String, Object> arguments,
            String controlEngine
        ) {}

        default boolean enterWorkflowStabilization(String actionKind, String admittedNodeId) { return false; }
        default boolean queueTemporalSequenceSuccessor(String predecessorSequenceId, String checkpointId,
            Map<String, Object> arguments, long boundaryTick, long receivedTick, long stopTick, long sourceOriginTick) { return false; }

        default WorkflowStep runWorkflowStep(
            String actionKind,
            Map<String, Object> arguments,
            String controlEngine,
            long actionTicks
        ) {
            return WorkflowStep.failed(
                "The client companion does not advertise action kind " + actionKind + ".",
                Map.of("action_kind", actionKind)
            );
        }

        default boolean evaluateFluidWorldCondition(Map<String, Object> condition) {
            return false;
        }

        /** Read-only item-count sensor. Unavailable is not an empty inventory. */
        default InventoryCountObservation inventoryCountObservation() {
            return InventoryCountObservation.unavailable();
        }

        default Map<String, Object> compactFluidState() {
            return Map.of();
        }

        default void releaseResources(Set<String> resources) {
            releaseAll();
        }

        void releaseAll();
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 320) {
            throw new IllegalArgumentException(name + " must be a bounded identifier");
        }
    }
}
