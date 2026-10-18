package com.casimirbot.helixplayer.fabric;

import com.casimirbot.helixplayer.fabric.PlayerActionWorkflow.WorkflowStep;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finite concurrent scheduler for an already-admitted reactive program.
 *
 * <p>This class does not interpret user intent or choose Minecraft strategy.
 * It arbitrates code-owned resources, evaluates typed conditions through the
 * supplied runtime, and advances the exact graph authored by the reasoning
 * layer.</p>
 */
final class ConcurrentReactiveScheduler {
    static final String COVERAGE_UNSAFE_LANDING = "unsafe_landing_recovery";
    static final String COVERAGE_FIRE = "fire_recovery";
    enum ActionStatus { RUNNING, SUCCEEDED, FAILED, TIMED_OUT }

    record ActionStep(
        ActionStatus status,
        String summary,
        Map<String, Object> measurements
    ) {
        ActionStep {
            if (status == null) throw new IllegalArgumentException("Action status is required.");
            if (summary == null || summary.isBlank()) {
                throw new IllegalArgumentException("Action summary is required.");
            }
            measurements = measurements == null ? Map.of() : Map.copyOf(measurements);
        }

        static ActionStep running(String summary) {
            return new ActionStep(ActionStatus.RUNNING, summary, Map.of());
        }

        static ActionStep succeeded(String summary) {
            return new ActionStep(ActionStatus.SUCCEEDED, summary, Map.of());
        }

        static ActionStep failed(String summary) {
            return new ActionStep(ActionStatus.FAILED, summary, Map.of());
        }
    }

    interface Runtime {
        boolean evaluateCondition(Map<String, Object> condition);

        ActionStep stepAction(
            String laneId,
            Map<String, Object> action,
            int iteration,
            long actionTicks
        );

        void cancelAction(String laneId, String reason);

        void releaseResources(String laneId, Set<String> resources);

        default String renderFrame(long frameNanos) {
            return null;
        }
    }

    private enum LaneState {
        DORMANT,
        READY,
        WAITING_FOR_RESOURCES,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELED,
        TIMED_OUT
    }

    private static final int MAX_TRANSITIONS_PER_LANE_TICK = 32;
    private static final Set<String> TERMINAL_STATES = Set.of(
        "SUCCEEDED",
        "FAILED",
        "CANCELED",
        "TIMED_OUT"
    );

    private final Runtime runtime;
    private final Map<String, Lane> lanes = new LinkedHashMap<>();
    private final Map<String, String> resourceOwners = new LinkedHashMap<>();
    private final List<Map<String, Object>> races = new ArrayList<>();
    private final List<Interrupt> interrupts = new ArrayList<>();
    private final List<Map<String, Object>> conditionObservations = new ArrayList<>();
    private final List<Map<String, Object>> raceOutcomes = new ArrayList<>();
    private final List<Map<String, Object>> placementPredictions = new ArrayList<>();
    private final List<Map<String, Object>> actionReceipts = new ArrayList<>();
    private final Set<String> settledRaceIds = new HashSet<>();
    private final Set<String> satisfiedCheckpoints = new LinkedHashSet<>();
    private final List<Map<String, Object>> checkpointSettlements = new ArrayList<>();
    private long startedNanos;
    private final Map<String, String> nodeOutcomes = new LinkedHashMap<>();
    private final Map<String, Boolean> lastConditionValues = new HashMap<>();
    private String programId = "";
    private String completionMode = "all_required";
    private Map<String, Object> mutationScope = Map.of();
    private long maxTotalTicks;
    private long currentTick;
    private int resourceConflictCount;
    private int interruptCount;
    private int executedActionCount;
    private int worldMutationsPerformed;
    private int inventoryMutationsPerformed;
    private int collectedCount;
    private int producedCount;
    private int transferredCount;
    private int consumedItemCount;
    private int placementActionSuccessCount;
    private int placementMutationSuccessCount;
    private int maxConcurrentLaneCount;
    private int parallelTickCount;
    private long lastParallelCountedTick = -1;
    private boolean playerMotionPerformed;
    private boolean playerInteractionPerformed;
    private boolean begun;
    private boolean settled;
    private boolean controlsReleased;

    ConcurrentReactiveScheduler(Runtime runtime) {
        this.runtime = runtime;
    }

    static void validate(Map<String, Object> program) {
        ConcurrentReactiveScheduler scheduler = new ConcurrentReactiveScheduler(
            new Runtime() {
                @Override
                public boolean evaluateCondition(Map<String, Object> condition) {
                    return false;
                }

                @Override
                public ActionStep stepAction(
                    String laneId,
                    Map<String, Object> action,
                    int iteration,
                    long actionTicks
                ) {
                    throw new IllegalStateException("Validation must not execute actions.");
                }

                @Override
                public void cancelAction(String laneId, String reason) {}

                @Override
                public void releaseResources(String laneId, Set<String> resources) {}
            }
        );
        scheduler.begin(program);
    }

    static Set<String> residentGuardianCoverage(Map<String, Object> program) {
        return Set.copyOf(strings(program.get("resident_guardian_coverage")));
    }

    void begin(Map<String, Object> program) {
        cancelAll("program_replaced");
        lanes.clear();
        resourceOwners.clear();
        races.clear();
        interrupts.clear();
        conditionObservations.clear();
        raceOutcomes.clear();
        placementPredictions.clear();
        actionReceipts.clear();
        settledRaceIds.clear();
        satisfiedCheckpoints.clear();
        checkpointSettlements.clear();
        startedNanos = System.nanoTime();
        nodeOutcomes.clear();
        lastConditionValues.clear();
        programId = text(program, "program_id");
        if (programId.isBlank()) throw new IllegalArgumentException("program_id is required");
        if (!"helix.minecraft.reactive_program.v1".equals(text(program, "program_schema"))) {
            throw new IllegalArgumentException("Unsupported reactive program schema.");
        }
        if (!"survival_tas".equals(text(program, "ruleset")) ||
            !"player_embodiment".equals(text(program, "execution_plane")) ||
            !"native_fabric_concurrent".equals(text(program, "scheduler_engine"))) {
            throw new IllegalArgumentException("Reactive program authority or scheduler is incompatible.");
        }
        maxTotalTicks = boundedLong(program, "max_total_ticks", 1, 36_000);
        completionMode = text(object(program.get("completion_policy")), "mode");
        if (!Set.of("all_required", "first_success").contains(completionMode)) {
            throw new IllegalArgumentException("Unknown reactive completion policy.");
        }
        mutationScope = Map.copyOf(object(program.get("mutation_scope")));

        for (Map<String, Object> laneRecord : objects(program.get("lanes"))) {
            Lane lane = new Lane(laneRecord);
            if (lanes.putIfAbsent(lane.id, lane) != null) {
                throw new IllegalArgumentException("Reactive lane identifiers must be unique.");
            }
        }
        if (lanes.isEmpty() || lanes.size() > 8) {
            throw new IllegalArgumentException("Reactive programs require one to eight lanes.");
        }
        for (Map<String, Object> race : objects(program.get("races"))) races.add(race);
        for (Map<String, Object> interrupt : objects(program.get("interrupts"))) {
            interrupts.add(new Interrupt(interrupt));
        }
        validateResidentGuardianCoverage(program);
        validateReferences();
        currentTick = 0;
        resourceConflictCount = 0;
        interruptCount = 0;
        executedActionCount = 0;
        worldMutationsPerformed = 0;
        inventoryMutationsPerformed = 0;
        collectedCount = 0;
        producedCount = 0;
        transferredCount = 0;
        consumedItemCount = 0;
        placementActionSuccessCount = 0;
        placementMutationSuccessCount = 0;
        maxConcurrentLaneCount = 0;
        parallelTickCount = 0;
        lastParallelCountedTick = -1;
        playerMotionPerformed = false;
        playerInteractionPerformed = false;
        begun = true;
        settled = false;
        controlsReleased = false;
    }

    private static void validateResidentGuardianCoverage(
        Map<String, Object> program
    ) {
        List<String> declared = strings(program.get("resident_guardian_coverage"));
        Set<String> coverage = Set.copyOf(declared);
        if (coverage.size() != declared.size() || !Set.of(
            COVERAGE_UNSAFE_LANDING,
            COVERAGE_FIRE
        ).containsAll(coverage)) {
            throw new IllegalArgumentException(
                "Resident guardian coverage must use unique supported invariants."
            );
        }
        if (coverage.isEmpty()) return;

        List<Map<String, Object>> qualifyingLanes = objects(program.get("lanes"))
            .stream()
            .filter(lane -> bool(lane, "required"))
            .filter(lane -> "immediate".equals(text(lane, "activation")))
            .toList();
        if (coverage.contains(COVERAGE_UNSAFE_LANDING)) {
            boolean validFallLane = qualifyingLanes.stream().anyMatch(lane -> {
                List<Map<String, Object>> nodes = objects(lane.get("nodes"));
                boolean fallingObserved = nodes.stream().anyMatch(node ->
                    conditionKind(node, "vertical_velocity_at_most")
                );
                boolean landingObserved = nodes.stream().anyMatch(node ->
                    conditionKind(node, "predicted_collision_within")
                );
                boolean boundedWaterClutch = nodes.stream().anyMatch(node -> {
                    Map<String, Object> action = object(node.get("action"));
                    Map<String, Object> binding = object(action.get("position_binding"));
                    return "place".equals(text(action, "action_kind")) &&
                        "minecraft:water".equals(text(action, "block_id")) &&
                        "item_use".equals(text(action, "placement_method")) &&
                        "minecraft:water_bucket".equals(text(action, "source_item_id")) &&
                        "predicted_collision_cell".equals(text(binding, "binding_kind")) &&
                        bool(binding, "require_replaceable") &&
                        bool(action, "cleanup_after_landing");
                });
                return fallingObserved && landingObserved && boundedWaterClutch;
            });
            Map<String, Object> mutation = object(program.get("mutation_scope"));
            boolean boundedMutation = bool(mutation, "world_mutation_allowed") &&
                integer(mutation, "max_block_mutations") >= 2 &&
                integer(mutation, "max_inventory_transfers") >= 2 &&
                strings(mutation.get("allowed_block_ids")).contains("minecraft:water");
            if (!validFallLane || !boundedMutation) {
                throw new IllegalArgumentException(
                    "Unsafe-landing coverage requires a required immediate predicted-collision water-bucket rescue with cleanup and a two-mutation water scope."
                );
            }
        }
        if (coverage.contains(COVERAGE_FIRE)) {
            boolean validFireLane = qualifyingLanes.stream().anyMatch(lane -> {
                List<Map<String, Object>> nodes = objects(lane.get("nodes"));
                boolean fireTransitionObserved = matchedBooleanTransition(
                    nodes,
                    "on_fire_is"
                );
                boolean lavaTransitionObserved = matchedBooleanTransition(
                    nodes,
                    "in_lava_is"
                );
                boolean boundedEscape = nodes.stream().anyMatch(node -> {
                    String actionKind = text(object(node.get("action")), "action_kind");
                    return "walk".equals(actionKind) || "navigate_to".equals(actionKind);
                });
                return (fireTransitionObserved || lavaTransitionObserved) && boundedEscape;
            });
            if (!validFireLane) {
                throw new IllegalArgumentException(
                    "Fire/lava coverage requires one required immediate lane that observes a matched hazard onset and clearance and performs bounded locomotion."
                );
            }
        }
        if (!hasHealthFloorInterrupt(program)) {
            throw new IllegalArgumentException(
                "Resident guardian coverage requires an independent health-floor interrupt."
            );
        }
    }

    private static boolean conditionKind(
        Map<String, Object> node,
        String expectedKind
    ) {
        return expectedKind.equals(text(object(node.get("condition")), "condition_kind"));
    }

    private static boolean matchedBooleanTransition(
        List<Map<String, Object>> nodes,
        String conditionKind
    ) {
        boolean observed = nodes.stream().anyMatch(node ->
            conditionKind(node, conditionKind) &&
                bool(object(node.get("condition")), "expected")
        );
        boolean cleared = nodes.stream().anyMatch(node ->
            conditionKind(node, conditionKind) &&
                !bool(object(node.get("condition")), "expected")
        );
        return observed && cleared;
    }

    private static boolean hasHealthFloorInterrupt(Map<String, Object> program) {
        return objects(program.get("interrupts")).stream().anyMatch(interrupt ->
            "health_at_least".equals(text(
                object(interrupt.get("condition")),
                "condition_kind"
            )) &&
                "not_satisfied".equals(text(interrupt, "trigger_when")) &&
                !strings(interrupt.get("cancel_lane_ids")).isEmpty()
        );
    }

    WorkflowStep step(long tickIndex) {
        if (!begun) {
            return WorkflowStep.failed(
                "No reactive program is active.",
                Map.of("reason_code", "reactive_program_not_started")
            );
        }
        if (settled) {
            return WorkflowStep.failed(
                "The reactive program is already settled.",
                measurements("reactive_program_already_settled")
            );
        }
        if (tickIndex < currentTick) {
            settleAll("reactive_tick_regressed");
            return WorkflowStep.failed(
                "The reactive scheduler tick regressed.",
                measurements("reactive_tick_regressed")
            );
        }
        currentTick = tickIndex;
        if (currentTick >= maxTotalTicks) {
            for (Lane lane : lanes.values()) {
                if (!lane.terminal()) timeoutLane(lane, "program_timeout");
            }
            settled = true;
            controlsReleased = true;
            return WorkflowStep.failed(
                "The reactive program reached its admitted tick ceiling.",
                measurements("reactive_program_timeout")
            );
        }

        evaluateInterrupts();
        List<Lane> ordered = lanes.values().stream()
            .filter(lane -> lane.state != LaneState.DORMANT && !lane.terminal())
            .sorted(Comparator.comparingInt((Lane lane) -> lane.priority).reversed()
                .thenComparing(lane -> lane.id))
            .toList();
        int scheduledLaneCount = ordered.size();
        maxConcurrentLaneCount = Math.max(maxConcurrentLaneCount, scheduledLaneCount);
        if (scheduledLaneCount > 1 && lastParallelCountedTick != currentTick) {
            parallelTickCount++;
            lastParallelCountedTick = currentTick;
        }
        for (Lane lane : ordered) advanceLane(lane);
        resolveRaces();

        WorkflowStep completion = completionStep();
        if (completion != null) return completion;
        long activeCount = lanes.values().stream()
            .filter(lane -> lane.state == LaneState.RUNNING ||
                lane.state == LaneState.READY ||
                lane.state == LaneState.WAITING_FOR_RESOURCES)
            .count();
        return WorkflowStep.running(
            Math.min(0.99, (double) currentTick / Math.max(1, maxTotalTicks)),
            "The admitted reactive lanes are advancing under resource arbitration.",
            measurements("reactive_program_running", "active_lane_count", activeCount)
        );
    }

    void cancelAll(String reason) {
        if (!begun || settled) return;
        for (Lane lane : lanes.values()) {
            if (!lane.terminal()) cancelLane(lane, reason);
        }
        settled = true;
        controlsReleased = true;
    }

    String renderFrame(long frameNanos) {
        if (!begun || settled) return null;
        return runtime.renderFrame(frameNanos);
    }

    private void evaluateInterrupts() {
        List<Interrupt> ordered = interrupts.stream()
            .filter(interrupt -> !interrupt.activated)
            .sorted(Comparator.comparingInt((Interrupt interrupt) -> interrupt.priority).reversed()
                .thenComparing(interrupt -> interrupt.id))
            .toList();
        for (Interrupt interrupt : ordered) {
            boolean evaluated = observeCondition(
                interrupt.id,
                interrupt.condition
            );
            boolean triggered = "not_satisfied".equals(interrupt.triggerWhen)
                ? !evaluated
                : evaluated;
            interrupt.consecutive = triggered ? interrupt.consecutive + 1 : 0;
            if (interrupt.consecutive < interrupt.debounceTicks) continue;
            interrupt.activated = true;
            interruptCount++;
            for (String laneId : interrupt.cancelLaneIds) {
                Lane canceled = lanes.get(laneId);
                if (canceled != null && !canceled.terminal()) {
                    cancelLane(canceled, "interrupt:" + interrupt.id);
                }
            }
            Lane activated = lanes.get(interrupt.activateLaneId);
            if (activated != null && activated.state == LaneState.DORMANT) {
                activated.state = LaneState.READY;
                activated.enteredTick = currentTick;
            }
        }
    }

    private void advanceLane(Lane lane) {
        for (int transition = 0; transition < MAX_TRANSITIONS_PER_LANE_TICK; transition++) {
            Map<String, Object> node = lane.nodes.get(lane.nodeId);
            if (node == null) {
                failLane(lane, "reactive_node_missing");
                return;
            }
            long earliest = longOr(node, "earliest_tick", 0);
            if (currentTick < earliest) {
                lane.state = LaneState.READY;
                return;
            }
            String kind = text(node, "node_kind");
            switch (kind) {
                case "terminal" -> {
                    terminalLane(lane, text(node, "terminal_outcome"));
                    return;
                }
                case "branch" -> {
                    transition(
                        lane,
                        text(node, observeCondition(
                            lane.nodeId,
                            object(node.get("condition"))
                        )
                            ? "on_true"
                            : "on_false")
                    );
                    continue;
                }
                case "event" -> {
                    boolean evaluated = observeCondition(
                        lane.nodeId,
                        object(node.get("condition"))
                    );
                    boolean triggered = "not_satisfied".equals(text(node, "trigger_when"))
                        ? !evaluated
                        : evaluated;
                    lane.conditionDebounce = triggered ? lane.conditionDebounce + 1 : 0;
                    if (lane.conditionDebounce >= longOr(node, "debounce_ticks", 1)) {
                        transition(lane, text(node, "on_event"));
                        continue;
                    }
                    if (elapsed(lane) >= longOr(node, "wait_up_to_ticks", 0)) {
                        transition(lane, text(node, "on_timeout"), "timed_out");
                        continue;
                    }
                    lane.state = LaneState.RUNNING;
                    return;
                }
                case "checkpoint" -> {
                    if (observeCondition(
                        lane.nodeId,
                        object(node.get("condition"))
                    )) {
                        satisfiedCheckpoints.add(text(node, "checkpoint_id"));
                        checkpointSettlements.add(Map.of(
                            "checkpoint_id", text(node, "checkpoint_id"),
                            "node_id", lane.nodeId,
                            "lane_id", lane.id,
                            "tick_index", currentTick,
                            "monotonic_elapsed_ns", Math.max(0L, System.nanoTime() - startedNanos)
                        ));
                        transition(lane, text(node, "on_satisfied"));
                        continue;
                    }
                    if (elapsed(lane) >= longOr(node, "wait_up_to_ticks", 0)) {
                        transition(lane, text(node, "on_timeout"), "timed_out");
                        continue;
                    }
                    lane.state = LaneState.RUNNING;
                    return;
                }
                case "action", "repeat", "maintain" -> {
                    if (advanceActionNode(lane, node, kind)) continue;
                    return;
                }
                default -> {
                    failLane(lane, "reactive_node_kind_unknown");
                    return;
                }
            }
        }
        failLane(lane, "reactive_transition_ceiling_exceeded");
    }

    /** Return true when the lane transitioned and may continue this tick. */
    private boolean advanceActionNode(
        Lane lane,
        Map<String, Object> node,
        String kind
    ) {
        // Rechecked while waiting for resources, but never a duration limit
        // on an action that already began inside its start window.
        if ("action".equals(kind) && lane.actionTicks == 0 &&
            currentTick > longOr(node, "latest_start_tick", Long.MAX_VALUE)) {
            captureActionEvidence(lane.id, lane.iteration, object(node.get("action")),
                new ActionStep(ActionStatus.TIMED_OUT, "The action start window expired before execution.",
                    Map.of("timeout_reason", "action_start_deadline_missed", "effect_prevented", true)));
            releaseLaneAction(lane, "action_start_deadline_missed");
            transition(lane, text(node, "on_timeout"), "timed_out");
            return true;
        }
        if ("maintain".equals(kind)) {
            boolean maintain = observeCondition(
                lane.nodeId,
                object(node.get("while_condition"))
            );
            if (!maintain) {
                releaseLaneAction(lane, "maintain_condition_false");
                transition(lane, text(node, "on_condition_false"), "canceled");
                return true;
            }
            if (elapsed(lane) >= longOr(node, "max_duration_ticks", 1)) {
                captureActionTimeoutEvidence(lane, node, "maintain_timeout");
                releaseLaneAction(lane, "maintain_timeout");
                transition(lane, text(node, "on_timeout"), "timed_out");
                return true;
            }
        } else if (elapsed(lane) >= longOr(node, "timeout_ticks", 1)) {
            captureActionTimeoutEvidence(lane, node, "action_timeout");
            releaseLaneAction(lane, "action_timeout");
            transition(lane, text(node, "on_timeout"), "timed_out");
            return true;
        }

        Map<String, Object> action = object(node.get("action"));
        Set<String> resources = resourcesFor(action);
        if (!lane.resourceCeiling.containsAll(resources)) {
            failLane(lane, "reactive_resource_ceiling_mismatch");
            return false;
        }
        if (!lane.heldResources.containsAll(resources)) {
            if (!acquire(lane, resources)) {
                lane.state = LaneState.WAITING_FOR_RESOURCES;
                resourceConflictCount++;
                return false;
            }
        }
        lane.state = LaneState.RUNNING;
        ActionStep actionStep = runtime.stepAction(
            lane.id,
            actionForRuntime(action),
            lane.iteration,
            lane.actionTicks
        );
        lane.actionTicks++;
        lane.lastActionStep = actionStep;
        if (actionStep.status() == ActionStatus.RUNNING) return false;
        captureActionEvidence(lane.id, lane.iteration, action, actionStep);
        lane.lastActionStep = null;
        releaseLaneAction(lane, "action_settled");
        if (actionStep.status() != ActionStatus.SUCCEEDED) {
            transition(lane, text(node, "on_failure"), "failed");
            return true;
        }

        if ("repeat".equals(kind)) {
            lane.iteration++;
            Object until = node.get("until_condition");
            boolean conditionSatisfied = until instanceof Map<?, ?> &&
                observeCondition(
                    lane.nodeId,
                    object(until)
                );
            if (conditionSatisfied || lane.iteration >= longOr(node, "max_iterations", 1)) {
                transition(lane, text(node, "on_complete"));
                return true;
            }
            lane.actionTicks = 0;
            return false;
        }
        if ("maintain".equals(kind)) {
            lane.iteration++;
            if (lane.iteration > longOr(node, "max_restarts", 0)) {
                transition(lane, text(node, "on_timeout"), "timed_out");
                return true;
            }
            lane.actionTicks = 0;
            return false;
        }
        transition(lane, text(node, "on_success"));
        return true;
    }

    private void resolveRaces() {
        for (Map<String, Object> race : races) {
            String raceId = text(race, "race_id");
            if (settledRaceIds.contains(raceId)) continue;
            List<Lane> members = strings(race.get("lane_ids")).stream()
                .map(lanes::get)
                .filter(java.util.Objects::nonNull)
                .toList();
            String settleOn = text(race, "settle_on");
            Lane winner = members.stream().filter(lane ->
                "first_succeeded".equals(settleOn)
                    ? lane.state == LaneState.SUCCEEDED
                    : lane.terminal()
            ).findFirst().orElse(null);
            if (winner == null) continue;
            List<String> canceledLaneIds = new ArrayList<>();
            for (Lane member : members) {
                if (member != winner && !member.terminal()) {
                    cancelLane(member, "race_lost:" + raceId);
                    canceledLaneIds.add(member.id);
                }
            }
            settledRaceIds.add(raceId);
            raceOutcomes.add(Map.of(
                "race_id", raceId,
                "winner_lane_id", winner.id,
                "settle_on", settleOn,
                "settled_tick", currentTick,
                "canceled_lane_ids", List.copyOf(canceledLaneIds)
            ));
        }
    }

    private WorkflowStep completionStep() {
        List<Lane> required = lanes.values().stream().filter(lane -> lane.required).toList();
        Interrupt handledInterrupt = handledInterrupt(required);
        if (handledInterrupt != null) {
            settleRemaining("handled_interrupt:" + handledInterrupt.id);
            return WorkflowStep.succeeded(
                "An admitted interrupt safely settled the required reactive lanes.",
                measurements(
                    "reactive_program_interrupted",
                    "settled_interrupt_id",
                    handledInterrupt.id
                )
            );
        }
        if (handledInterruptPending(required)) return null;

        if ("first_success".equals(completionMode)) {
            Lane succeeded = lanes.values().stream()
                .filter(lane -> lane.state == LaneState.SUCCEEDED)
                .findFirst()
                .orElse(null);
            if (succeeded != null) {
                settleRemaining("first_success:" + succeeded.id);
                return WorkflowStep.succeeded(
                    "The first-success reactive completion policy settled the program.",
                    measurements("reactive_program_succeeded")
                );
            }
            if (lanes.values().stream().allMatch(Lane::terminal)) {
                settleAll("reactive_program_no_success");
                return WorkflowStep.failed(
                    "Every reactive lane settled without a successful result.",
                    measurements("reactive_program_failed")
                );
            }
            return null;
        }

        Lane failed = required.stream().filter(lane ->
            lane.state == LaneState.FAILED ||
            lane.state == LaneState.CANCELED ||
            lane.state == LaneState.TIMED_OUT
        ).findFirst().orElse(null);
        if (failed != null) {
            settleRemaining("required_lane_failed:" + failed.id);
            return WorkflowStep.failed(
                "A required reactive lane failed or was canceled.",
                measurements("reactive_required_lane_failed", "failed_lane_id", failed.id)
            );
        }
        if (!required.isEmpty() && required.stream().allMatch(lane ->
            lane.state == LaneState.SUCCEEDED)) {
            settleRemaining("all_required_succeeded");
            return WorkflowStep.succeeded(
                "Every required reactive lane satisfied its terminal contract.",
                measurements("reactive_program_succeeded")
            );
        }
        return null;
    }

    private Interrupt handledInterrupt(List<Lane> required) {
        for (Interrupt interrupt : interrupts) {
            if (!interrupt.activated) continue;
            Lane target = lanes.get(interrupt.activateLaneId);
            if (!handledInterruptTarget(target)) continue;
            boolean canceledRequired = required.stream().anyMatch(lane ->
                lane.state == LaneState.CANCELED &&
                ("interrupt:" + interrupt.id).equals(lane.cancellationReason)
            );
            if (!canceledRequired) continue;
            boolean everyRequiredHandled = required.stream().allMatch(lane ->
                lane.state == LaneState.SUCCEEDED ||
                (lane.state == LaneState.CANCELED &&
                    ("interrupt:" + interrupt.id).equals(lane.cancellationReason))
            );
            if (everyRequiredHandled) return interrupt;
        }
        return null;
    }

    private boolean handledInterruptPending(List<Lane> required) {
        for (Interrupt interrupt : interrupts) {
            if (!interrupt.activated) continue;
            String cancellationReason = "interrupt:" + interrupt.id;
            boolean canceledRequired = required.stream().anyMatch(lane ->
                lane.state == LaneState.CANCELED &&
                cancellationReason.equals(lane.cancellationReason)
            );
            if (!canceledRequired) continue;
            boolean irrecoverableRequired = required.stream().anyMatch(lane ->
                lane.state == LaneState.FAILED ||
                lane.state == LaneState.TIMED_OUT ||
                (lane.state == LaneState.CANCELED &&
                    !cancellationReason.equals(lane.cancellationReason))
            );
            Lane target = lanes.get(interrupt.activateLaneId);
            if (!irrecoverableRequired && target != null && !target.terminal()) {
                return true;
            }
        }
        return false;
    }

    private boolean handledInterruptTarget(Lane target) {
        if (
            target == null ||
            (target.state != LaneState.SUCCEEDED && target.state != LaneState.CANCELED)
        ) return false;
        Map<String, Object> node = target.nodes.get(target.nodeId);
        if (node == null || !"terminal".equals(text(node, "node_kind"))) return false;
        return Set.of("succeeded", "canceled").contains(text(node, "terminal_outcome"));
    }

    private boolean acquire(Lane lane, Set<String> resources) {
        for (String resource : resources) {
            String owner = resourceOwners.get(resource);
            if (owner != null && !owner.equals(lane.id)) return false;
        }
        for (String resource : resources) resourceOwners.put(resource, lane.id);
        lane.heldResources.addAll(resources);
        return true;
    }

    private void releaseLaneAction(Lane lane, String reason) {
        if (lane.heldResources.isEmpty()) return;
        runtime.cancelAction(lane.id, reason);
        runtime.releaseResources(lane.id, Set.copyOf(lane.heldResources));
        for (String resource : lane.heldResources) {
            resourceOwners.remove(resource, lane.id);
        }
        lane.heldResources.clear();
    }

    private Map<String, Object> actionForRuntime(Map<String, Object> action) {
        if (
            !"place".equals(text(action, "action_kind")) ||
            object(action.get("position_binding")).isEmpty() ||
            mutationScope.isEmpty()
        ) return action;
        Map<String, Object> runtimeAction = new LinkedHashMap<>(action);
        runtimeAction.put("_helix_admitted_mutation_scope", mutationScope);
        return Map.copyOf(runtimeAction);
    }

    private void captureActionTimeoutEvidence(
        Lane lane,
        Map<String, Object> node,
        String reason
    ) {
        if (lane.lastActionStep == null) return;
        Map<String, Object> measurements = new LinkedHashMap<>(
            lane.lastActionStep.measurements()
        );
        measurements.put("timeout_reason", reason);
        measurements.put("action_ticks", lane.actionTicks);
        measurements.put("last_runtime_summary", lane.lastActionStep.summary());
        captureActionEvidence(
            lane.id,
            lane.iteration,
            object(node.get("action")),
            new ActionStep(
                ActionStatus.TIMED_OUT,
                "The bounded lane action timed out after the last measured runtime state: " +
                    lane.lastActionStep.summary(),
                measurements
            )
        );
        lane.lastActionStep = null;
    }

    private void transition(Lane lane, String nextNodeId) {
        transition(lane, nextNodeId, "succeeded");
    }

    private void transition(Lane lane, String nextNodeId, String outcome) {
        releaseLaneAction(lane, "lane_transition");
        nodeOutcomes.put(lane.nodeId, outcome);
        lane.nodeId = nextNodeId;
        lane.enteredTick = currentTick;
        lane.actionTicks = 0;
        lane.lastActionStep = null;
        lane.iteration = 0;
        lane.conditionDebounce = 0;
        lane.cancellationReason = "";
        lane.state = LaneState.READY;
    }

    private void terminalLane(Lane lane, String outcome) {
        releaseLaneAction(lane, "lane_terminal");
        nodeOutcomes.put(lane.nodeId, outcome);
        lane.state = switch (outcome) {
            case "succeeded" -> LaneState.SUCCEEDED;
            case "canceled" -> LaneState.CANCELED;
            default -> LaneState.FAILED;
        };
        lane.cancellationReason = "terminal:" + outcome;
        lane.controlsReleased = true;
    }

    private void cancelLane(Lane lane, String reason) {
        releaseLaneAction(lane, reason);
        nodeOutcomes.put(lane.nodeId, "canceled");
        lane.state = LaneState.CANCELED;
        lane.cancellationReason = reason;
        lane.controlsReleased = true;
    }

    private void timeoutLane(Lane lane, String reason) {
        Map<String, Object> node = lane.nodes.get(lane.nodeId);
        if (
            node != null &&
            Set.of("action", "repeat", "maintain").contains(text(node, "node_kind"))
        ) {
            captureActionTimeoutEvidence(lane, node, reason);
        }
        releaseLaneAction(lane, reason);
        nodeOutcomes.put(lane.nodeId, "timed_out");
        lane.state = LaneState.TIMED_OUT;
        lane.controlsReleased = true;
    }

    private void failLane(Lane lane, String reason) {
        releaseLaneAction(lane, reason);
        nodeOutcomes.put(lane.nodeId, "failed");
        lane.state = LaneState.FAILED;
        lane.controlsReleased = true;
    }

    private void settleRemaining(String reason) {
        for (Lane lane : lanes.values()) {
            if (!lane.terminal()) cancelLane(lane, reason);
        }
        settled = true;
        controlsReleased = true;
    }

    private void settleAll(String reason) {
        settleRemaining(reason);
    }

    private long elapsed(Lane lane) {
        return Math.max(0, currentTick - lane.enteredTick);
    }

    private boolean observeCondition(
        String observationId,
        Map<String, Object> condition
    ) {
        boolean satisfied = switch (text(condition, "condition_kind")) {
            case "tick_at_least" ->
                currentTick >= longOr(condition, "tick_index", Long.MAX_VALUE);
            case "checkpoint_satisfied" ->
                satisfiedCheckpoints.contains(text(condition, "checkpoint_id"));
            case "node_outcome_is" ->
                text(condition, "outcome").equals(
                    nodeOutcomes.get(text(condition, "node_id"))
                );
            default -> runtime.evaluateCondition(condition);
        };
        Boolean previous = lastConditionValues.put(observationId, satisfied);
        if (
            conditionObservations.size() < 512 &&
            (previous == null || previous.booleanValue() != satisfied)
        ) {
            Map<String, Object> observation = new LinkedHashMap<>();
            observation.put("node_id", observationId);
            observation.put("tick_index", currentTick);
            observation.put("condition_kind", text(condition, "condition_kind"));
            observation.put("satisfied", satisfied);
            copyConditionSubject(condition, observation, "item_id", "subject_item_id");
            copyConditionSubject(
                condition,
                observation,
                "output_item_id",
                "subject_output_item_id"
            );
            copyConditionSubject(
                condition,
                observation,
                "dimension",
                "subject_dimension"
            );
            copyConditionSubject(
                condition,
                observation,
                "destination",
                "subject_destination"
            );
            copyConditionSubject(
                condition,
                observation,
                "portal_kind",
                "subject_portal_kind"
            );
            copyConditionSubject(
                condition,
                observation,
                "checkpoint_id",
                "subject_checkpoint_id"
            );
            copyConditionSubject(
                condition,
                observation,
                "node_id",
                "subject_node_id"
            );
            conditionObservations.add(Map.copyOf(observation));
        }
        return satisfied;
    }

    private static void copyConditionSubject(
        Map<String, Object> condition,
        Map<String, Object> observation,
        String sourceKey,
        String destinationKey
    ) {
        Object value = condition.get(sourceKey);
        if (value instanceof String text && !text.isBlank()) {
            observation.put(destinationKey, text);
        }
    }

    private void captureActionEvidence(
        String laneId,
        int iteration,
        Map<String, Object> action,
        ActionStep actionStep
    ) {
        String actionKind = text(action, "action_kind");
        Map<String, Object> measurements = actionStep.measurements();
        int observedWorldMutations = nonnegativeMeasurement(
            measurements,
            "world_mutations_performed"
        );
        int observedInventoryMutations = nonnegativeMeasurement(
            measurements,
            "inventory_mutations_performed"
        );
        if (Boolean.TRUE.equals(measurements.get("safety_interrupted"))) {
            interruptCount++;
        }
        worldMutationsPerformed += observedWorldMutations;
        inventoryMutationsPerformed += observedInventoryMutations;
        collectedCount += nonnegativeMeasurement(measurements, "collected_count");
        producedCount += nonnegativeMeasurement(measurements, "produced_count");
        transferredCount += nonnegativeMeasurement(measurements, "transferred_count");
        int observedConsumedItems = nonnegativeMeasurement(measurements, "consumed_item_count");
        if (observedConsumedItems == 0) {
            observedConsumedItems = nonnegativeMeasurement(measurements, "consumed_count");
        }
        consumedItemCount += observedConsumedItems;
        if (actionReceipts.size() < 256) {
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("lane_id", laneId);
            receipt.put("action_kind", actionKind);
            receipt.put("iteration", iteration);
            receipt.put("tick_index", currentTick);
            receipt.put(
                "outcome",
                actionStep.status().name().toLowerCase(java.util.Locale.ROOT)
            );
            receipt.put("summary", actionStep.summary());
            for (String key : List.of(
                "interaction_accepted", "target", "hand", "interaction",
                "post_interaction_observed", "held_item_id_before",
                "held_item_id_after", "held_item_count_before",
                "held_item_count_after", "held_item_count_delta",
                "consumed_item_count", "consumed_count", "inventory_mutations_performed",
                "health_before", "health_after", "health_delta", "use_ticks",
                "minimum_health_during_use", "observed_health_loss_during_use",
                "health_loss_event_count_during_use",
                "world_mutations_performed", "collected_count", "produced_count",
                "transferred_count", "placement_method", "source_item_id",
                "block_id", "verified_positions", "requested_positions",
                "target_position", "position_binding_kind", "timeout_reason",
                "action_ticks", "last_runtime_summary", "tracking_completed",
                "safety_interrupted", "interrupt_reason", "measured_health",
                "stop_below_health", "target_ref", "target_entity_type_id",
                "target_particle_type_id", "duration_ticks", "sample_count",
                "retained_ticks", "target_loss_ticks",
                "line_of_sight_retained_ticks", "reacquisition_count",
                "mean_angular_error_degrees", "p95_angular_error_degrees",
                "max_angular_error_degrees", "final_yaw_error_degrees",
                "final_pitch_error_degrees", "combat_mode", "attack_pulses",
                "minimum_hostile_distance", "safe_separation_reached",
                "visible_hostile_count", "eligible_hostile_count"
            )) {
                Object value = measurements.get(key);
                if (value != null) receipt.put(key, value);
            }
            actionReceipts.add(Map.copyOf(receipt));
        }
        Object placementPrediction = measurements.get("placement_prediction");
        if (
            placementPrediction instanceof Map<?, ?> prediction &&
            placementPredictions.size() < 256
        ) {
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("lane_id", laneId);
            receipt.put("action_kind", actionKind);
            for (Map.Entry<?, ?> entry : prediction.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() != null) {
                    receipt.put(key, entry.getValue());
                }
            }
            placementPredictions.add(Map.copyOf(receipt));
        }
        if (actionStep.status() != ActionStatus.SUCCEEDED) {
            if (observedWorldMutations > 0 || observedInventoryMutations > 0) {
                playerInteractionPerformed = true;
            }
            return;
        }

        executedActionCount++;
        if ("place".equals(actionKind)) {
            placementActionSuccessCount++;
            if (observedWorldMutations > 0) placementMutationSuccessCount++;
        }
        if (Set.of(
            "navigate_to", "look_at", "track_target", "walk", "jump",
            "follow", "collect", "mine", "place"
        ).contains(actionKind)) playerMotionPerformed = true;
        if (Set.of(
            "interact", "mine", "place", "craft", "inventory_transfer"
        ).contains(actionKind)) playerInteractionPerformed = true;
        if (
            Set.of("hotbar_select", "equip").contains(actionKind) &&
            observedInventoryMutations == 0
        ) inventoryMutationsPerformed++;
    }

    private static int nonnegativeMeasurement(
        Map<String, Object> measurements,
        String key
    ) {
        Object value = measurements.get(key);
        if (!(value instanceof Number number)) return 0;
        return Math.max(0, number.intValue());
    }

    private Map<String, Object> measurements(String reasonCode, Object... extra) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("program_schema", "helix.minecraft.reactive_program.v1");
        result.put("program_id", programId);
        result.put(
            "reactive_program_completed",
            "reactive_program_succeeded".equals(reasonCode) ||
                "reactive_program_interrupted".equals(reasonCode)
        );
        result.put("reason_code", reasonCode);
        result.put("tick_index", currentTick);
        result.put("active_lane_count", lanes.values().stream().filter(lane ->
            lane.state == LaneState.RUNNING ||
            lane.state == LaneState.READY ||
            lane.state == LaneState.WAITING_FOR_RESOURCES
        ).count());
        result.put("resource_conflict_count", resourceConflictCount);
        result.put("interrupt_count", interruptCount);
        result.put("controls_released", controlsReleased);
        result.put("executed_action_count", executedActionCount);
        result.put("condition_observations", List.copyOf(conditionObservations));
        result.put("condition_observation_count", conditionObservations.size());
        result.put("satisfied_checkpoint_ids", List.copyOf(satisfiedCheckpoints));
        result.put("checkpoint_settlements", List.copyOf(checkpointSettlements));
        result.put("node_outcomes", Map.copyOf(nodeOutcomes));
        result.put("world_mutations_performed", worldMutationsPerformed);
        result.put("inventory_mutations_performed", inventoryMutationsPerformed);
        result.put("collected_count", collectedCount);
        result.put("produced_count", producedCount);
        result.put("transferred_count", transferredCount);
        result.put("consumed_item_count", consumedItemCount);
        result.put("action_receipts", List.copyOf(actionReceipts));
        result.put("action_receipt_count", actionReceipts.size());
        result.put("max_concurrent_lane_count", maxConcurrentLaneCount);
        result.put("parallel_tick_count", parallelTickCount);
        result.put("race_outcomes", List.copyOf(raceOutcomes));
        result.put("race_outcome_count", raceOutcomes.size());
        result.put("placement_predictions", List.copyOf(placementPredictions));
        result.put("placement_prediction_count", placementPredictions.size());
        result.put("placement_action_success_count", placementActionSuccessCount);
        result.put("placement_mutation_success_count", placementMutationSuccessCount);
        result.put("player_motion_performed", playerMotionPerformed);
        result.put("player_interaction_performed", playerInteractionPerformed);
        List<Map<String, Object>> laneMeasurements = new ArrayList<>();
        for (Lane lane : lanes.values()) {
            Map<String, Object> laneMeasurement = new LinkedHashMap<>();
            laneMeasurement.put("lane_id", lane.id);
            laneMeasurement.put("lane_kind", lane.kind);
            laneMeasurement.put(
                "state",
                lane.state.name().toLowerCase(java.util.Locale.ROOT)
            );
            laneMeasurement.put("node_id", lane.nodeId);
            laneMeasurement.put("held_resources", List.copyOf(lane.heldResources));
            laneMeasurement.put("iteration", lane.iteration);
            laneMeasurement.put("tick_index", currentTick);
            laneMeasurement.put("controls_released", lane.controlsReleased);
            laneMeasurements.add(Map.copyOf(laneMeasurement));
        }
        result.put("lanes", List.copyOf(laneMeasurements));
        for (int index = 0; index + 1 < extra.length; index += 2) {
            result.put(String.valueOf(extra[index]), extra[index + 1]);
        }
        return Map.copyOf(result);
    }

    private void validateReferences() {
        if (lanes.values().stream().noneMatch(lane -> "immediate".equals(lane.activation))) {
            throw new IllegalArgumentException("A reactive program requires an immediate lane.");
        }
        Set<String> globalObservationIds = new HashSet<>();
        for (Lane lane : lanes.values()) {
            if (lane.required && !"immediate".equals(lane.activation)) {
                throw new IllegalArgumentException("Required lanes must activate immediately.");
            }
            if ("safety".equals(lane.kind) && !lane.resourceCeiling.contains("safety")) {
                throw new IllegalArgumentException("Safety lanes must declare the safety resource.");
            }
            if (!lane.nodes.containsKey(lane.nodeId)) {
                throw new IllegalArgumentException("Lane start node is missing.");
            }
            if (lane.nodes.values().stream().noneMatch(node ->
                "terminal".equals(text(node, "node_kind")))) {
                throw new IllegalArgumentException("Every reactive lane requires a terminal node.");
            }
            for (Map<String, Object> node : lane.nodes.values()) {
                if (!globalObservationIds.add(text(node, "node_id"))) {
                    throw new IllegalArgumentException("Reactive observation identifiers must be globally unique.");
                }
                for (String target : transitions(node)) {
                    if (!lane.nodes.containsKey(target)) {
                        throw new IllegalArgumentException("Lane transition target is missing.");
                    }
                }
                String nodeKind = text(node, "node_kind");
                if (Set.of("action", "repeat", "maintain").contains(nodeKind)) {
                    Map<String, Object> action = object(node.get("action"));
                    Set<String> resources = resourcesFor(action);
                    if (!lane.resourceCeiling.containsAll(resources)) {
                        throw new IllegalArgumentException("Reactive action exceeds its lane resource ceiling.");
                    }
                    String actionKind = text(action, "action_kind");
                    if ("follow".equals(actionKind)) {
                        throw new IllegalArgumentException("Nested subject-bound follow is not admitted.");
                    }
                    if ("look_at".equals(actionKind) &&
                        "environment_subject".equals(text(object(action.get("target")), "target_kind"))) {
                        throw new IllegalArgumentException("Nested subject-bound look is not admitted.");
                    }
                }
            }
            validateAcyclicReachability(lane);
        }
        for (Interrupt interrupt : interrupts) {
            if (!globalObservationIds.add(interrupt.id)) {
                throw new IllegalArgumentException("Reactive observation identifiers must be globally unique.");
            }
            Lane activated = lanes.get(interrupt.activateLaneId);
            if (activated == null || activated.state != LaneState.DORMANT) {
                throw new IllegalArgumentException("Interrupt target must be a dormant lane.");
            }
            for (String laneId : interrupt.cancelLaneIds) {
                if (!lanes.containsKey(laneId)) {
                    throw new IllegalArgumentException("Interrupt cancellation lane is missing.");
                }
            }
        }
        for (Map<String, Object> race : races) {
            for (String laneId : strings(race.get("lane_ids"))) {
                if (!lanes.containsKey(laneId)) {
                    throw new IllegalArgumentException("Race lane is missing.");
                }
            }
        }
    }

    private static void validateAcyclicReachability(Lane lane) {
        Set<String> visited = new HashSet<>();
        Set<String> active = new HashSet<>();
        visitNode(lane, lane.nodeId, visited, active);
        if (visited.size() != lane.nodes.size()) {
            throw new IllegalArgumentException("Every reactive node must be reachable.");
        }
    }

    private static void visitNode(
        Lane lane,
        String nodeId,
        Set<String> visited,
        Set<String> active
    ) {
        if (active.contains(nodeId)) {
            throw new IllegalArgumentException("Reactive lane graphs must be acyclic.");
        }
        if (visited.contains(nodeId)) return;
        Map<String, Object> node = lane.nodes.get(nodeId);
        if (node == null) return;
        active.add(nodeId);
        visited.add(nodeId);
        for (String target : transitions(node)) visitNode(lane, target, visited, active);
        active.remove(nodeId);
    }

    private static List<String> transitions(Map<String, Object> node) {
        return switch (text(node, "node_kind")) {
            case "action" -> List.of(
                text(node, "on_success"),
                text(node, "on_failure"),
                text(node, "on_timeout")
            );
            case "repeat" -> List.of(
                text(node, "on_complete"),
                text(node, "on_failure"),
                text(node, "on_timeout")
            );
            case "maintain" -> List.of(
                text(node, "on_condition_false"),
                text(node, "on_failure"),
                text(node, "on_timeout")
            );
            case "event" -> List.of(text(node, "on_event"), text(node, "on_timeout"));
            case "checkpoint" -> List.of(
                text(node, "on_satisfied"),
                text(node, "on_timeout")
            );
            case "branch" -> List.of(text(node, "on_true"), text(node, "on_false"));
            default -> List.of();
        };
    }

    static Set<String> resourcesFor(Map<String, Object> action) {
        String kind = text(action, "action_kind");
        return switch (kind) {
            case "navigate_to" -> Set.of("camera", "locomotion");
            case "look_at", "track_target" -> Set.of("camera");
            case "walk", "jump" -> Set.of("locomotion");
            case "interact" -> "off_hand".equals(text(action, "hand"))
                ? Set.of("off_hand")
                : Set.of("main_hand");
            case "attack" -> Set.of("camera", "main_hand");
            case "combat_guard" -> Set.of(
                "camera", "locomotion", "main_hand", "off_hand", "native_workflow"
            );
            case "hotbar_select" -> Set.of("hotbar");
            case "equip" -> Set.of("hotbar", "main_hand", "off_hand", "inventory");
            case "follow" -> Set.of("camera", "locomotion", "native_workflow");
            case "collect" -> Set.of(
                "camera", "locomotion", "inventory", "native_workflow"
            );
            case "mine" -> Set.of(
                "camera", "locomotion", "main_hand", "world", "native_workflow"
            );
            case "place" -> placementResources(action);
            case "craft", "inventory_transfer" -> Set.of("inventory", "native_workflow");
            case "consume" -> Set.of(
                "hotbar", "main_hand", "inventory", "native_workflow"
            );
            default -> throw new IllegalArgumentException("Unsupported reactive action kind " + kind);
        };
    }

    private static Set<String> placementResources(Map<String, Object> action) {
        Set<String> resources = new LinkedHashSet<>(Set.of(
            "camera", "locomotion", "inventory", "world", "native_workflow"
        ));
        if (
            "item_use".equals(text(action, "placement_method")) &&
            "off_hand".equals(text(action, "hand"))
        ) {
            resources.add("off_hand");
        } else {
            resources.add("hotbar");
            resources.add("main_hand");
        }
        return Set.copyOf(resources);
    }

    private final class Lane {
        private final String id;
        private final String kind;
        private final int priority;
        private final boolean required;
        private final String activation;
        private final Set<String> resourceCeiling;
        private final Map<String, Map<String, Object>> nodes = new LinkedHashMap<>();
        private String nodeId;
        private LaneState state;
        private long enteredTick;
        private long actionTicks;
        private ActionStep lastActionStep;
        private int iteration;
        private int conditionDebounce;
        private String cancellationReason = "";
        private boolean controlsReleased;
        private final Set<String> heldResources = new LinkedHashSet<>();

        private Lane(Map<String, Object> lane) {
            id = text(lane, "lane_id");
            kind = text(lane, "lane_kind");
            priority = integer(lane, "priority");
            required = bool(lane, "required");
            List<String> declaredResources = strings(lane.get("resource_ceiling"));
            resourceCeiling = Set.copyOf(declaredResources);
            if (resourceCeiling.size() != declaredResources.size()) {
                throw new IllegalArgumentException("Reactive lane resources must be unique.");
            }
            nodeId = text(lane, "start_node_id");
            activation = text(lane, "activation");
            state = "interrupt_only".equals(activation)
                ? LaneState.DORMANT
                : LaneState.READY;
            for (Map<String, Object> node : objects(lane.get("nodes"))) {
                String nodeId = text(node, "node_id");
                if (nodes.putIfAbsent(nodeId, node) != null) {
                    throw new IllegalArgumentException("Lane node identifiers must be unique.");
                }
            }
        }

        private boolean terminal() {
            return TERMINAL_STATES.contains(state.name());
        }
    }

    private static final class Interrupt {
        private final String id;
        private final int priority;
        private final Map<String, Object> condition;
        private final String triggerWhen;
        private final int debounceTicks;
        private final String activateLaneId;
        private final List<String> cancelLaneIds;
        private int consecutive;
        private boolean activated;

        private Interrupt(Map<String, Object> interrupt) {
            id = text(interrupt, "interrupt_id");
            priority = integer(interrupt, "priority");
            condition = object(interrupt.get("condition"));
            triggerWhen = text(interrupt, "trigger_when");
            debounceTicks = Math.max(1, integer(interrupt, "debounce_ticks"));
            activateLaneId = text(interrupt, "activate_lane_id");
            cancelLaneIds = strings(interrupt.get("cancel_lane_ids"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        return value instanceof Map<?, ?> map
            ? (Map<String, Object>) map
            : Map.of();
    }

    private static List<Map<String, Object>> objects(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : list) result.add(object(entry));
        return List.copyOf(result);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object entry : list) {
            if (entry instanceof String text && !text.isBlank()) result.add(text);
        }
        return List.copyOf(result);
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String text ? text : "";
    }

    private static int integer(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static boolean bool(Map<String, Object> map, String key) {
        return Boolean.TRUE.equals(map.get(key));
    }

    private static long boundedLong(
        Map<String, Object> map,
        String key,
        long minimum,
        long maximum
    ) {
        long value = longOr(map, key, -1);
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(key + " is outside its admitted bounds.");
        }
        return value;
    }

    private static long longOr(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        return value instanceof Number number ? number.longValue() : fallback;
    }
}
