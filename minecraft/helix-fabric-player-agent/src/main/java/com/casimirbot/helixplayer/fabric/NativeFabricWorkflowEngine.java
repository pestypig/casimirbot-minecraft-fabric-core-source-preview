package com.casimirbot.helixplayer.fabric;

import static com.casimirbot.helixplayer.fabric.PlayerActionWorkflow.*;

import com.casimirbot.helixsensor.navigation.BoundedNavigationFrontier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.recipebook.RecipeCollection;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedItemContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.display.RecipeDisplayEntry;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Tick-sensitive mechanics for already-admitted goals. This class never samples a model. */
final class NativeFabricWorkflowEngine {
    private static final int NATIVE_BLOCK_SEARCH_RADIUS_CEILING = 32;
    private static final int MAX_EXACT_TRANSFER_PER_STEP = 64;
    private static final int MINING_NAVIGATION_NO_PROGRESS_TICKS = 200;
    private static final int MAX_SENSING_TIMING_SAMPLES = 512;
    private static final int NATIVE_NAVIGATION_RADIUS = 7;
    private static final int NATIVE_NAVIGATION_VERTICAL_RADIUS = 6;
    private static final int NATIVE_NAVIGATION_MAX_REPLANS = 4;
    private static final int NATIVE_NAVIGATION_NO_PROGRESS_TICKS = 40;

    private final Minecraft minecraft;
    private final NativeFabricControlBridge bridge;
    private final BaritoneFacade baritone;
    private String actionKind = "";
    private Map<String, Object> arguments = Map.of();
    private String controlEngine = "native_fabric";
    private int initialInventoryCount;
    private int completedCount;
    private int worldMutationCount;
    private int placeIndex;
    private int noTargetTicks;
    private int pendingTicks;
    private double initialDistance = -1;
    private Double workflowStartX;
    private Double workflowStartY;
    private Double workflowStartZ;
    private Double navigationLastX;
    private Double navigationLastY;
    private Double navigationLastZ;
    private double navigationPathDistance;
    private boolean nativeNavigationOwnedJumpArc;
    private BlockPos blockTarget;
    private Direction blockTargetFace;
    private MiningTargetAffordance.ApproachPose blockApproach;
    private long maxSensorCaptureDurationNanos;
    private long maxAffordanceDeriveDurationNanos;
    private final long[] sensingTimingSamplesNanos =
        new long[MAX_SENSING_TIMING_SAMPLES];
    private int sensingTimingSampleCount;
    private boolean blockActionStarted;
    private boolean containerOpenRequested;
    private boolean inventoryActionIssued;
    private int inventoryCountBeforeIssued;
    private boolean baritoneGoalOwned;
    private boolean baritoneFinalApproach;
    private BoundedNavigationFrontier.GoalPlan nativeNavigationPlan;
    private int nativeNavigationStepIndex;
    private int nativeNavigationReplanCount;
    private int nativeNavigationNoProgressTicks;
    private double nativeNavigationClosestStepDistance = Double.POSITIVE_INFINITY;
    private String nativeNavigationLastReplanReason = "initial_plan";
    private Map<String, Object> latestPlacementForecast = Map.of();
    private Map<String, Object> dynamicPlacementBindingEvidence = Map.of();
    private BlockPos dynamicPlacementTarget;
    private int placementInventoryMutationCount;
    private HandObservation placementHandBefore = HandObservation.unavailable();
    private HandObservation placementHandAfter = HandObservation.unavailable();
    private boolean landingCleanupEligible;
    private boolean landingCleanupIssued;
    private int landingCleanupPendingTicks;
    private boolean consumeBaselineCaptured;
    private int initialFoodLevel;
    private float initialSaturationLevel;
    private float initialHealth;
    private int initialRemainderCount;
    private int consumeUseTicks;
    private int consumeStartWaitTicks;
    private boolean consumeUseStarted;
    private float consumeMinimumHealth;
    private float consumePreviousHealth;
    private float consumeObservedHealthLoss;
    private int consumeHealthLossEventCount;
    private final Set<Long> completedBlockTargets = new HashSet<>();

    static boolean usesReusableWorkflowEngine(String actionKind) {
        return Set.of(
            "navigate_to", "follow", "collect", "mine", "place", "craft", "consume",
            "inventory_transfer"
        ).contains(actionKind);
    }

    NativeFabricWorkflowEngine(
        Minecraft minecraft,
        NativeFabricControlBridge bridge,
        BaritoneFacade baritone
    ) {
        this.minecraft = minecraft;
        this.bridge = bridge;
        this.baritone = baritone;
    }

    void begin(String actionKind, Map<String, Object> arguments, String controlEngine) {
        cancel();
        this.actionKind = actionKind;
        this.arguments = arguments;
        this.controlEngine = controlEngine;
        LocalPlayer player = minecraft.player;
        if (player != null) {
            workflowStartX = player.getX();
            workflowStartY = player.getY();
            workflowStartZ = player.getZ();
            navigationLastX = player.getX();
            navigationLastY = player.getY();
            navigationLastZ = player.getZ();
        }
        String countedItem = switch (actionKind) {
            case "collect" -> text(arguments, "item_or_block_id");
            case "craft" -> text(arguments, "output_item_id");
            case "consume" -> text(arguments, "item_id");
            case "inventory_transfer" -> text(arguments, "item_id");
            default -> "";
        };
        initialInventoryCount = countedItem.isBlank() ? 0 : inventoryCount(countedItem);
    }

    WorkflowStep step(String requestedKind, long actionTicks) {
        if (!requestedKind.equals(actionKind)) {
            return WorkflowStep.failed(
                "The active native workflow identity changed unexpectedly.",
                Map.of("requested_action_kind", requestedKind, "active_action_kind", actionKind)
            );
        }
        return switch (requestedKind) {
            case "navigate_to" -> navigate(actionTicks);
            case "follow" -> follow(actionTicks);
            case "collect" -> collect(actionTicks);
            case "mine" -> mine(actionTicks);
            case "place" -> place(actionTicks);
            case "craft" -> craft(actionTicks);
            case "consume" -> consume(actionTicks);
            case "inventory_transfer" -> inventoryTransfer(actionTicks);
            default -> WorkflowStep.failed(
                "The client companion does not advertise action kind " + requestedKind + ".",
                Map.of("action_kind", requestedKind)
            );
        };
    }

    private WorkflowStep navigate(long actionTicks) {
        WorkflowStep budgetStop = checkNavigationTravelBudget();
        if (budgetStop != null) return budgetStop;
        return "baritone".equals(controlEngine)
            ? baritoneNavigate(actionTicks)
            : nativeNavigate(actionTicks);
    }

    private WorkflowStep checkNavigationTravelBudget() {
        LocalPlayer player = requirePlayer();
        if (navigationLastX != null && navigationLastY != null && navigationLastZ != null) {
            navigationPathDistance += distance(
                navigationLastX, navigationLastY, navigationLastZ,
                player.getX(), player.getY(), player.getZ()
            );
        }
        navigationLastX = player.getX();
        navigationLastY = player.getY();
        navigationLastZ = player.getZ();
        Object rawBudget = arguments.get("max_distance_blocks");
        if (!(rawBudget instanceof Number budgetNumber)) return null;
        double maxDistance = budgetNumber.doubleValue();
        Map<String, Object> destination = object(arguments.get("destination"));
        double goalDistance = distance(
            player.getX(), player.getY(), player.getZ(),
            number(destination, "x"), number(destination, "y"), number(destination, "z")
        );
        double arrivalRadius = number(arguments, "arrival_radius");
        // Stop asserting controls before consuming the final half block. A
        // measured external impulse may still exceed the budget, but cannot
        // authorize further player input from this workflow.
        if (navigationTravelBudgetAllowsInput(
            navigationPathDistance, maxDistance, goalDistance, arrivalRadius
        )) {
            return null;
        }
        bridge.applyMovement(MovementInput.released());
        boolean safeCancel = !baritoneGoalOwned || baritone.cancel();
        baritoneGoalOwned = false;
        LinkedHashMap<String, Object> measurements = new LinkedHashMap<>();
        measurements.put("reason_code", "navigation_travel_budget_reached");
        measurements.put("navigation_path_distance_blocks", navigationPathDistance);
        measurements.put("max_distance_blocks", maxDistance);
        measurements.put("distance_blocks", goalDistance);
        measurements.put("arrival_radius", arrivalRadius);
        measurements.put("controls_released", safeCancel);
        addWorkflowMotionMeasurements(measurements, player);
        return WorkflowStep.failed(
            "Navigation stopped at its admitted travel budget with controls released.",
            measurements
        );
    }

    static boolean navigationTravelBudgetAllowsInput(
        double traveledPath,
        double maxDistance,
        double goalDistance,
        double arrivalRadius
    ) {
        if (!Double.isFinite(traveledPath) || !Double.isFinite(maxDistance) ||
            !Double.isFinite(goalDistance) || !Double.isFinite(arrivalRadius) ||
            traveledPath < 0 || maxDistance < 1 || arrivalRadius < 0 ||
            traveledPath > maxDistance) return false;
        if (goalDistance <= arrivalRadius) return true;
        // A goal whose straight-line lower bound exceeds the admitted path
        // budget cannot be reached; decline before the first movement tick.
        if (traveledPath == 0 && goalDistance - arrivalRadius > maxDistance) return false;
        return traveledPath < maxDistance - 0.5;
    }

    private WorkflowStep nativeNavigate(long actionTicks) {
        LocalPlayer player = requirePlayer();
        if (player.onGround()) nativeNavigationOwnedJumpArc = false;
        Map<String, Object> destination = object(arguments.get("destination"));
        double x = number(destination, "x");
        double y = number(destination, "y");
        double z = number(destination, "z");
        double radius = number(arguments, "arrival_radius");
        double distance = distance(player.getX(), player.getY(), player.getZ(), x, y, z);
        if (initialDistance < 0) initialDistance = distance;
        if (distance <= radius) {
            bridge.applyMovement(MovementInput.released());
            LinkedHashMap<String, Object> measurements = nativeNavigationMeasurements(
                distance,
                radius,
                "native_navigation_arrived"
            );
            measurements.put("world_mutations_performed", 0);
            measurements.put("inventory_mutations_performed", 0);
            measurements.put("breaking_allowed", false);
            measurements.put("placement_allowed", false);
            return WorkflowStep.succeeded(
                "The CasimirBot-native route follower reached the admitted destination radius.",
                measurements
            );
        }

        BlockPos admittedGoal = BlockPos.containing(x, y, z);
        if (player.blockPosition().equals(admittedGoal)) {
            WorkflowStep safetyFailure = navigateToward(
                x,
                y,
                z,
                radius,
                false
            );
            if (safetyFailure != null) return safetyFailure;
            LinkedHashMap<String, Object> progress = nativeNavigationMeasurements(
                distance,
                radius,
                "native_navigation_precise_arrival"
            );
            progress.put("planner_status", "already_at_goal");
            progress.put("route_step_index", 0);
            progress.put("route_step_target", position(admittedGoal));
            return WorkflowStep.running(
                progressFromDistance(distance, radius),
                "The CasimirBot-native route follower is refining arrival inside the admitted goal block.",
                progress
            );
        }

        if (nativeNavigationPlan == null) {
            WorkflowStep planningFailure = replanNativeNavigation(
                admittedGoal,
                distance,
                radius,
                "initial_plan",
                false
            );
            if (planningFailure != null) return planningFailure;
        }

        List<BoundedNavigationFrontier.Step> steps =
            nativeNavigationPlan.route().steps();
        BlockPos currentBlock = player.blockPosition();
        while (
            nativeNavigationStepIndex < steps.size() &&
            currentBlock.equals(blockPosition(
                steps.get(nativeNavigationStepIndex).to()
            ))
        ) {
            nativeNavigationStepIndex++;
            nativeNavigationClosestStepDistance = Double.POSITIVE_INFINITY;
            nativeNavigationNoProgressTicks = 0;
        }
        if (nativeNavigationStepIndex >= steps.size()) {
            WorkflowStep planningFailure = replanNativeNavigation(
                admittedGoal,
                distance,
                radius,
                "route_exhausted_before_precise_arrival",
                true
            );
            if (planningFailure != null) return planningFailure;
            steps = nativeNavigationPlan.route().steps();
        }

        BoundedNavigationFrontier.Step step = steps.get(nativeNavigationStepIndex);
        BlockPos expectedFrom = blockPosition(step.from());
        BlockPos stepTarget = blockPosition(step.to());
        if (
            chebyshevDistance(currentBlock, expectedFrom) > 1 &&
            !currentBlock.equals(stepTarget)
        ) {
            WorkflowStep planningFailure = replanNativeNavigation(
                admittedGoal,
                distance,
                radius,
                "route_deviation",
                true
            );
            if (planningFailure != null) return planningFailure;
            step = nativeNavigationPlan.route().steps().get(0);
            stepTarget = blockPosition(step.to());
        }

        BoundedNavigationFrontier.GoalPlan stepCheck =
            BoundedNavigationFrontier.planTo(
                frontierPosition(navigationStepValidationOrigin(
                    currentBlock, expectedFrom, player.onGround()
                )),
                step.to(),
                2,
                2,
                this::nativeNavigationCell
            );
        if (
            stepCheck.status() != BoundedNavigationFrontier.GoalPlanStatus.ROUTE_FOUND &&
            stepCheck.status() != BoundedNavigationFrontier.GoalPlanStatus.ALREADY_AT_GOAL
        ) {
            WorkflowStep planningFailure = replanNativeNavigation(
                admittedGoal,
                distance,
                radius,
                "next_foothold_invalidated",
                true
            );
            if (planningFailure != null) return planningFailure;
            step = nativeNavigationPlan.route().steps().get(0);
            stepTarget = blockPosition(step.to());
        }

        double stepX = stepTarget.getX() + 0.5;
        double stepY = stepTarget.getY();
        double stepZ = stepTarget.getZ() + 0.5;
        double stepDistance = distance(
            player.getX(), player.getY(), player.getZ(),
            stepX, stepY, stepZ
        );
        if (stepDistance + 0.02 < nativeNavigationClosestStepDistance) {
            nativeNavigationClosestStepDistance = stepDistance;
            nativeNavigationNoProgressTicks = 0;
        } else {
            nativeNavigationNoProgressTicks++;
        }
        if (nativeNavigationNoProgressTicks >= NATIVE_NAVIGATION_NO_PROGRESS_TICKS) {
            WorkflowStep planningFailure = replanNativeNavigation(
                admittedGoal,
                distance,
                radius,
                "bounded_non_progress",
                true
            );
            if (planningFailure != null) return planningFailure;
            step = nativeNavigationPlan.route().steps().get(0);
            stepTarget = blockPosition(step.to());
            stepX = stepTarget.getX() + 0.5;
            stepY = stepTarget.getY();
            stepZ = stepTarget.getZ() + 0.5;
        }

        boolean jumpNow = step.kind() == BoundedNavigationFrontier.MoveKind.ASCEND &&
            player.horizontalCollision && player.onGround();
        WorkflowStep safetyFailure = navigateToward(
            stepX,
            stepY,
            stepZ,
            0.35,
            bool(arguments, "allow_sprint") &&
                step.kind() != BoundedNavigationFrontier.MoveKind.ASCEND,
            step.kind() == BoundedNavigationFrontier.MoveKind.ASCEND,
            nativeNavigationOwnedJumpArc
        );
        if (safetyFailure != null) return safetyFailure;
        nativeNavigationOwnedJumpArc = retainOwnedJumpArc(
            nativeNavigationOwnedJumpArc, player.onGround(), jumpNow
        );

        LinkedHashMap<String, Object> progress = nativeNavigationMeasurements(
            distance,
            radius,
            "native_navigation_following"
        );
        progress.put("route_step_index", nativeNavigationStepIndex);
        progress.put("route_step_movement", step.kind().name().toLowerCase());
        progress.put("route_step_target", position(stepTarget));
        progress.put("route_step_distance_blocks", stepDistance);
        return WorkflowStep.running(
            progressFromDistance(distance, radius),
            "The CasimirBot-native route follower is executing a typed foothold step.",
            progress
        );
    }

    private WorkflowStep replanNativeNavigation(
        BlockPos goal,
        double distance,
        double radius,
        String reason,
        boolean countsAsReplan
    ) {
        bridge.applyMovement(MovementInput.released());
        if (countsAsReplan) nativeNavigationReplanCount++;
        nativeNavigationLastReplanReason = reason;
        if (nativeNavigationReplanCount > NATIVE_NAVIGATION_MAX_REPLANS) {
            return WorkflowStep.failed(
                "Native navigation exhausted its bounded reroute budget with controls released.",
                nativeNavigationMeasurements(distance, radius, "native_replan_budget_exhausted")
            );
        }
        BlockPos origin = requirePlayer().blockPosition();
        nativeNavigationPlan = BoundedNavigationFrontier.planTo(
            frontierPosition(origin),
            frontierPosition(goal),
            NATIVE_NAVIGATION_RADIUS,
            NATIVE_NAVIGATION_VERTICAL_RADIUS,
            this::nativeNavigationCell
        );
        nativeNavigationStepIndex = 0;
        nativeNavigationNoProgressTicks = 0;
        nativeNavigationClosestStepDistance = Double.POSITIVE_INFINITY;
        if (
            nativeNavigationPlan.status() ==
                BoundedNavigationFrontier.GoalPlanStatus.ROUTE_FOUND
        ) return null;
        LinkedHashMap<String, Object> measurements = nativeNavigationMeasurements(
            distance,
            radius,
            "native_plan_unavailable"
        );
        measurements.put(
            "planner_status",
            nativeNavigationPlan.status().name().toLowerCase()
        );
        measurements.put(
            "planner_evidence_complete",
            nativeNavigationPlan.evidenceComplete()
        );
        measurements.put(
            "planner_reachable_foothold_count",
            nativeNavigationPlan.reachableFootholdCount()
        );
        return WorkflowStep.failed(
            "The CasimirBot-native planner could not prove a bounded route to the admitted waypoint.",
            measurements
        );
    }

    private LinkedHashMap<String, Object> nativeNavigationMeasurements(
        double distance,
        double radius,
        String reasonCode
    ) {
        LinkedHashMap<String, Object> measurements = new LinkedHashMap<>();
        measurements.put("reason_code", reasonCode);
        measurements.put("distance_blocks", distance);
        measurements.put("arrival_radius", radius);
        measurements.put("control_engine", "native_fabric");
        measurements.put("planner", "casimirbot_native_bounded_dijkstra");
        measurements.put("replan_count", nativeNavigationReplanCount);
        measurements.put("last_replan_reason", nativeNavigationLastReplanReason);
        measurements.put("controls_released_on_replan", true);
        measurements.put("mutation_policy", "movement_only");
        measurements.put("navigation_path_distance_blocks", navigationPathDistance);
        if (arguments.get("max_distance_blocks") instanceof Number budget) {
            measurements.put("max_distance_blocks", budget.doubleValue());
        }
        addWorkflowMotionMeasurements(measurements, minecraft.player);
        if (nativeNavigationPlan != null) {
            measurements.put(
                "planner_status",
                nativeNavigationPlan.status().name().toLowerCase()
            );
            measurements.put(
                "planner_evidence_complete",
                nativeNavigationPlan.evidenceComplete()
            );
            measurements.put(
                "planner_reachable_foothold_count",
                nativeNavigationPlan.reachableFootholdCount()
            );
            measurements.put(
                "route_step_count",
                nativeNavigationPlan.route() == null
                    ? 0
                    : nativeNavigationPlan.route().steps().size()
            );
        }
        return measurements;
    }

    private void addWorkflowMotionMeasurements(
        Map<String, Object> measurements,
        LocalPlayer player
    ) {
        if (
            player == null || workflowStartX == null ||
            workflowStartY == null || workflowStartZ == null
        ) return;
        double dx = player.getX() - workflowStartX;
        double dy = player.getY() - workflowStartY;
        double dz = player.getZ() - workflowStartZ;
        double displacement = Math.sqrt(dx * dx + dy * dy + dz * dz);
        measurements.put("workflow_displacement_blocks", displacement);
        measurements.put("final_x", player.getX());
        measurements.put("final_y", player.getY());
        measurements.put("final_z", player.getZ());
        measurements.put("player_motion_performed", displacement >= 0.01);
    }

    private WorkflowStep baritoneNavigate(long actionTicks) {
        if (!"baritone".equals(controlEngine) || !baritone.available()) {
            return WorkflowStep.failed(
                "The requested Baritone navigation engine is not installed in this client.",
                Map.of("control_engine", controlEngine, "engine_available", false)
            );
        }
        LocalPlayer player = requirePlayer();
        Map<String, Object> destination = object(arguments.get("destination"));
        double x = number(destination, "x");
        double y = number(destination, "y");
        double z = number(destination, "z");
        double radius = number(arguments, "arrival_radius");
        double distance = distance(player.getX(), player.getY(), player.getZ(), x, y, z);
        if (initialDistance < 0) initialDistance = distance;
        if (distance <= radius) {
            boolean usedNativeFinalApproach = baritoneFinalApproach;
            boolean safeCancel = !baritoneGoalOwned || baritone.cancel();
            baritoneGoalOwned = false;
            LinkedHashMap<String, Object> measurements = new LinkedHashMap<>(Map.ofEntries(
                Map.entry("distance_blocks", distance),
                Map.entry("arrival_radius", radius),
                Map.entry(
                    "control_engine",
                    usedNativeFinalApproach ? "baritone_then_native_fabric" : "baritone"
                ),
                Map.entry("engine_version", baritone.version()),
                Map.entry("safe_cancel", safeCancel),
                Map.entry("mutation_policy", "movement_only"),
                Map.entry("breaking_allowed", false),
                Map.entry("placement_allowed", false),
                Map.entry("inventory_mutation_allowed", false),
                Map.entry("world_mutations_performed", 0),
                Map.entry("inventory_mutations_performed", 0),
                Map.entry("native_final_approach_used", usedNativeFinalApproach)
            ));
            measurements.put("navigation_path_distance_blocks", navigationPathDistance);
            if (arguments.get("max_distance_blocks") instanceof Number budget) {
                measurements.put("max_distance_blocks", budget.doubleValue());
            }
            addWorkflowMotionMeasurements(measurements, player);
            return WorkflowStep.succeeded(
                "Baritone reached the admitted destination radius.",
                measurements
            );
        }
        if (baritoneFinalApproach) {
            if (distance < initialDistance - 0.05) {
                initialDistance = distance;
                noTargetTicks = 0;
            } else if (miningNavigationStalled(++noTargetTicks)) {
                bridge.applyMovement(MovementInput.released());
                return WorkflowStep.failed(
                    "The native final approach after Baritone settlement made no bounded progress.",
                    Map.of(
                        "reason_code", "baritone_native_final_approach_stalled",
                        "distance_blocks", distance,
                        "arrival_radius", radius,
                        "control_engine", "baritone_then_native_fabric",
                        "no_progress_ticks", noTargetTicks,
                        "mutation_policy", "movement_only"
                    )
                );
            }
            WorkflowStep safetyFailure = navigateToward(x, y, z, radius, false);
            if (safetyFailure != null) return safetyFailure;
            return WorkflowStep.running(
                progressFromDistance(distance, radius),
                "Native Fabric is closing the final measured gap after Baritone approach settlement.",
                Map.of(
                    "reason_code", "baritone_native_final_approach_running",
                    "distance_blocks", distance,
                    "arrival_radius", radius,
                    "control_engine", "baritone_then_native_fabric",
                    "mutation_policy", "movement_only"
                )
            );
        }
        if (actionTicks == 1) {
            baritoneGoalOwned = baritone.start(
                floor(x), floor(y), floor(z),
                Math.max(1, (int) Math.ceil(radius))
            );
            if (!baritoneGoalOwned) {
                BaritoneFacade.Status status = baritone.status();
                return WorkflowStep.failed(
                    "Baritone was installed but rejected the admitted navigation goal.",
                    baritoneMeasurements(distance, radius, status, "baritone_goal_rejected")
                );
            }
        }
        BaritoneFacade.Status status = baritone.status();
        if (status.pathState() == BaritoneFacade.PathState.POLICY_VIOLATION ||
            status.pathState() == BaritoneFacade.PathState.ERROR) {
            // Keep ownership until the controller's terminal release invokes
            // cancel(). Clearing it here can strand the facade's goal lease
            // and make the next admitted route fail as already active.
            return WorkflowStep.failed(
                "Baritone failed closed before the destination postcondition was satisfied.",
                baritoneMeasurements(distance, radius, status, "baritone_policy_or_status_failure")
            );
        }
        if (actionTicks > 20 && status.pathState() == BaritoneFacade.PathState.IDLE) {
            if (distance <= radius + 2.5) {
                boolean safeCancel = baritone.cancel();
                baritoneGoalOwned = false;
                if (!safeCancel) {
                    return WorkflowStep.failed(
                        "Baritone reached the handoff neighborhood but could not cancel at a safe segment boundary.",
                        baritoneMeasurements(distance, radius, status, "baritone_final_handoff_unsafe")
                    );
                }
                baritoneFinalApproach = true;
                initialDistance = distance;
                noTargetTicks = 0;
                WorkflowStep safetyFailure = navigateToward(x, y, z, radius, false);
                if (safetyFailure != null) return safetyFailure;
                return WorkflowStep.running(
                    progressFromDistance(distance, radius),
                    "Baritone settled near the destination and handed off to exact native final approach.",
                    baritoneMeasurements(distance, radius, status, "baritone_final_handoff_started")
                );
            }
            // Baritone may be idle before the exact Helix postcondition is
            // satisfied. The terminal release must still cancel our goal and
            // restore its movement-only settings before another route starts.
            return WorkflowStep.failed(
                "Baritone stopped before the measured destination postcondition was satisfied.",
                baritoneMeasurements(distance, radius, status, "baritone_stopped_before_arrival")
            );
        }
        return WorkflowStep.running(
            progressFromDistance(distance, radius),
            "Baritone is navigating toward the admitted destination.",
            baritoneMeasurements(distance, radius, status, "baritone_navigation_running")
        );
    }

    private static Map<String, Object> baritoneMeasurements(
        double distance,
        double radius,
        BaritoneFacade.Status status,
        String reasonCode
    ) {
        LinkedHashMap<String, Object> measurements = new LinkedHashMap<>();
        measurements.put("reason_code", reasonCode);
        measurements.put("distance_blocks", distance);
        measurements.put("arrival_radius", radius);
        measurements.put("control_engine", "baritone");
        measurements.put("engine_version", status.version());
        measurements.put("path_state", status.pathState().name().toLowerCase());
        measurements.put("goal_owned", status.goalOwned());
        measurements.put("process_active", status.processActive());
        measurements.put("mutation_policy", "movement_only");
        measurements.put("mutation_policy_intact", status.mutationPolicyIntact());
        measurements.put("safe_cancel_last_result", status.safeCancelLastResult());
        if (status.estimatedTicksToGoal() != null) {
            measurements.put("estimated_ticks_to_goal", status.estimatedTicksToGoal());
        }
        if (status.lastError() != null && !status.lastError().isBlank()) {
            measurements.put("engine_error", status.lastError());
        }
        return Map.copyOf(measurements);
    }

    private WorkflowStep follow(long actionTicks) {
        LocalPlayer player = requirePlayer();
        String nativeId = text(arguments, "target_subject_native_id");
        AbstractClientPlayer target = findPlayer(nativeId);
        if (target == null) {
            return WorkflowStep.failed(
                "The exact followed player is not present in the current client world.",
                Map.of("target_present", false)
            );
        }
        double stopHealth = number(arguments, "stop_below_health");
        if (player.getHealth() < stopHealth) {
            return WorkflowStep.failed(
                "Following stopped because the paired player's measured health crossed the admitted floor.",
                Map.of("health", player.getHealth(), "stop_below_health", stopHealth)
            );
        }
        double desired = number(arguments, "distance");
        double current = player.distanceTo(target);
        long durationTicks = Math.max(20, integer(arguments, "max_duration_ms") / 50L);
        if (actionTicks >= durationTicks) {
            bridge.applyMovement(MovementInput.released());
            return WorkflowStep.succeeded(
                "The bounded follow interval completed with the target still observed.",
                Map.of(
                    "target_present", true,
                    "final_distance_blocks", current,
                    "requested_distance_blocks", desired,
                    "duration_ticks", actionTicks
                )
            );
        }
        if (current > desired) {
            WorkflowStep safetyFailure = navigateToward(
                target.getX(), target.getY(), target.getZ(), desired, true
            );
            if (safetyFailure != null) return safetyFailure;
        } else {
            bridge.applyMovement(MovementInput.released());
            bridge.lookAt(target.getX(), target.getEyeY(), target.getZ(), 18.0F);
        }
        return WorkflowStep.running(
            Math.min(0.99, (double) actionTicks / durationTicks),
            current > desired
                ? "The paired player is closing the admitted gap to the followed player."
                : "The paired player is maintaining the admitted follow distance.",
            Map.of(
                "target_present", true,
                "distance_blocks", current,
                "requested_distance_blocks", desired,
                "health", player.getHealth()
            )
        );
    }

    private WorkflowStep collect(long actionTicks) {
        LocalPlayer player = requirePlayer();
        String itemId = text(arguments, "item_or_block_id");
        int requested = integer(arguments, "count");
        int now = inventoryCount(itemId);
        int collected = Math.max(0, now - initialInventoryCount);
        if (collected >= requested) {
            return WorkflowStep.succeeded(
                "The requested dropped items were collected and verified in the player inventory.",
                Map.of("item_id", itemId, "requested_count", requested, "collected_count", collected)
            );
        }
        double radius = number(arguments, "search_radius");
        AABB bounds = player.getBoundingBox().inflate(radius);
        ItemEntity target = minecraft.level.getEntitiesOfClass(
            ItemEntity.class,
            bounds,
            entity -> entity.isAlive() && matches(entity.getItem(), itemId)
        ).stream().min(Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
        if (target == null) {
            bridge.applyMovement(MovementInput.released());
            noTargetTicks++;
            if (noTargetTicks > 40) {
                return WorkflowStep.failed(
                    "No matching dropped item remained in the loaded admitted search radius.",
                    Map.of("item_id", itemId, "collected_count", collected, "search_radius", radius)
                );
            }
        } else {
            noTargetTicks = 0;
            WorkflowStep safetyFailure = navigateToward(
                target.getX(), target.getY(), target.getZ(), 0.75, true
            );
            if (safetyFailure != null) return safetyFailure;
        }
        return WorkflowStep.running(
            Math.min(0.99, (double) collected / requested),
            target == null
                ? "The client is waiting briefly for a matching dropped item in loaded range."
                : "The paired player is moving toward a matching dropped item.",
            Map.of(
                "item_id", itemId,
                "requested_count", requested,
                "collected_count", collected,
                "target_present", target != null
            )
        );
    }

    private WorkflowStep mine(long actionTicks) {
        LocalPlayer player = requirePlayer();
        String blockId = text(arguments, "block_id");
        int requested = integer(arguments, "count");
        int radius = integer(arguments, "search_radius");
        BlockPos exactTarget = optionalBlockPosition(arguments.get("target_position"));
        if (exactTarget != null && requested != 1) {
            return WorkflowStep.failed(
                "Exact-target mining requires exactly one admitted block removal.",
                Map.of("block_id", blockId, "requested_count", requested)
            );
        }
        if (radius > NATIVE_BLOCK_SEARCH_RADIUS_CEILING) {
            return WorkflowStep.failed(
                "Native Fabric mining accepts a loaded search radius of at most 32 blocks; request a smaller radius or a declared pathing engine.",
                Map.of("requested_radius", radius, "native_radius_ceiling", NATIVE_BLOCK_SEARCH_RADIUS_CEILING)
            );
        }
        if (completedCount >= requested) {
            LinkedHashMap<String, Object> success = new LinkedHashMap<>();
            success.put("block_id", blockId);
            success.put("requested_count", requested);
            success.put("removed_count", completedCount);
            success.put("world_mutations_performed", worldMutationCount);
            success.put("max_sensor_capture_duration_nanos", maxSensorCaptureDurationNanos);
            success.put("max_affordance_derive_duration_nanos", maxAffordanceDeriveDurationNanos);
            success.put(
                "max_sensing_total_duration_nanos",
                maxSensorCaptureDurationNanos + maxAffordanceDeriveDurationNanos
            );
            success.put("sensing_timing_sample_count", sensingTimingSampleCount);
            success.put("p95_sensing_total_duration_nanos", p95SensingDurationNanos());
            if (exactTarget != null) success.put("target_position", position(exactTarget));
            return WorkflowStep.succeeded(
                "The requested block removals were verified in the client world.",
                Map.copyOf(success)
            );
        }
        if (blockTarget != null && !blockMatches(blockTarget, blockId)) {
            completedBlockTargets.add(blockTarget.asLong());
            completedCount++;
            worldMutationCount++;
            blockTarget = null;
            blockTargetFace = null;
            blockApproach = null;
            blockActionStarted = false;
            pendingTicks = 0;
            noTargetTicks = 0;
            initialDistance = -1;
            minecraft.gameMode.stopDestroyBlock();
            if (completedCount >= requested) return mine(actionTicks);
        }
        if (blockTarget == null) {
            if (exactTarget != null) {
                BlockPos center = player.blockPosition();
                boolean insideRadius =
                    Math.abs(exactTarget.getX() - center.getX()) <= radius &&
                    Math.abs(exactTarget.getY() - center.getY()) <= radius &&
                    Math.abs(exactTarget.getZ() - center.getZ()) <= radius;
                if (!insideRadius) {
                    return WorkflowStep.failed(
                        "The exact mining target is outside the admitted loaded-client search radius.",
                        Map.of(
                            "reason_code", "exact_mining_target_outside_radius",
                            "block_id", blockId,
                            "target_position", position(exactTarget),
                            "search_radius", radius
                        )
                    );
                }
                if (!minecraft.level.hasChunkAt(exactTarget)) {
                    return WorkflowStep.failed(
                        "The exact mining target is not currently loaded in the paired client world.",
                        Map.of(
                            "reason_code", "exact_mining_target_unloaded",
                            "block_id", blockId,
                            "target_position", position(exactTarget)
                        )
                    );
                }
                if (!blockMatches(exactTarget, blockId)) {
                    return WorkflowStep.failed(
                        "The exact mining target no longer matches the admitted block identifier.",
                        Map.of(
                            "reason_code", "exact_mining_target_mismatch",
                            "block_id", blockId,
                            "target_position", position(exactTarget)
                        )
                    );
                }
                blockTarget = exactTarget.immutable();
            } else {
                blockTarget = findNearestBlock(player.blockPosition(), blockId, radius);
            }
            if (blockTarget == null) {
                return WorkflowStep.failed(
                    "No matching loaded block was found inside the admitted native mining radius.",
                    Map.of("block_id", blockId, "removed_count", completedCount, "search_radius", radius)
                );
            }
            blockTargetFace = nearestExposedMiningFace(
                blockTarget,
                player.getEyePosition(),
                this::isOpenMiningNeighbor
            );
            if (blockTargetFace == null) {
                blockTarget = null;
                return WorkflowStep.failed(
                    "No legitimately exposed face was available on the selected mining target.",
                    Map.of("block_id", blockId, "removed_count", completedCount)
                );
            }
        }
        PlayerSensorFrame frame = bridge.sensorFrame();
        long affordanceStartedNanos = System.nanoTime();
        MiningTargetAffordance affordance = MiningTargetAffordance.derive(
            frame,
            blockTarget,
            player.blockInteractionRange(),
            this::isOpenMiningNeighbor,
            this::isMiningSupport
        );
        long affordanceDurationNanos = Math.max(
            0,
            System.nanoTime() - affordanceStartedNanos
        );
        maxSensorCaptureDurationNanos = Math.max(
            maxSensorCaptureDurationNanos,
            frame.captureDurationNanos()
        );
        maxAffordanceDeriveDurationNanos = Math.max(
            maxAffordanceDeriveDurationNanos,
            affordanceDurationNanos
        );
        recordSensingDuration(
            frame.captureDurationNanos() + affordanceDurationNanos
        );
        if (blockApproach == null) blockApproach = affordance.approach();
        if (blockTargetFace == null) blockTargetFace = affordance.face();
        double distance = affordance.targetDistance();
        if (distance > player.blockInteractionRange()) {
            if (blockApproach == null) {
                bridge.applyMovement(MovementInput.released());
                return WorkflowStep.failed(
                    "No bounded legitimately standable approach pose was available for the selected mining target.",
                    Map.of(
                        "reason_code", "mining_no_approach_pose",
                        "block_id", blockId,
                        "removed_count", completedCount,
                        "target_position", position(blockTarget),
                        "target_distance_blocks", distance,
                        "sensor_world_revision", frame.worldRevision(),
                        "sensor_game_tick", frame.gameTick()
                    )
                );
            }
            if (initialDistance < 0 || distance < initialDistance - 0.1) {
                initialDistance = distance;
                noTargetTicks = 0;
            } else if (miningNavigationStalled(++noTargetTicks)) {
                bridge.applyMovement(MovementInput.released());
                return WorkflowStep.failed(
                    "The selected mining target remained outside interaction range after bounded measured non-progress.",
                    Map.of(
                        "block_id", blockId,
                        "removed_count", completedCount,
                        "target_position", position(blockTarget),
                        "target_distance_blocks", distance,
                        "closest_target_distance_blocks", initialDistance,
                        "no_progress_ticks", noTargetTicks,
                        "reason_code", "mining_approach_stalled"
                    )
                );
            }
            WorkflowStep safetyFailure = navigateToward(
                blockApproach.x(),
                blockApproach.y(),
                blockApproach.z(),
                0.4,
                false
            );
            if (safetyFailure != null) return safetyFailure;
        } else {
            bridge.applyMovement(MovementInput.released());
            Vec3 faceCenter = affordance.faceCenter();
            bridge.lookAt(faceCenter.x, faceCenter.y, faceCenter.z, 18.0F);
            if (!blockActionStarted) {
                if (!affordance.focused()) {
                    pendingTicks++;
                    if (miningFocusNeedsApproach(pendingTicks)) {
                        if (initialDistance < 0 || distance < initialDistance - 0.1) {
                            initialDistance = distance;
                            noTargetTicks = 0;
                        } else if (miningNavigationStalled(++noTargetTicks)) {
                            bridge.applyMovement(MovementInput.released());
                            return WorkflowStep.failed(
                                "The selected exposed mining target did not become focusable after bounded measured repositioning.",
                                Map.of(
                                    "block_id", blockId,
                                    "target_position", position(blockTarget),
                                    "target_distance_blocks", distance,
                                    "closest_target_distance_blocks", initialDistance,
                                    "focus_reachable", false,
                                    "no_progress_ticks", noTargetTicks,
                                    "reason_code", "mining_focus_stalled",
                                    "sensor_world_revision", frame.worldRevision(),
                                    "sensor_game_tick", frame.gameTick()
                                )
                            );
                        }
                        if (blockApproach == null) {
                            return WorkflowStep.failed(
                                "The target was in nominal range but had no standable pose from which focus could be reacquired.",
                                Map.of(
                                    "reason_code", "mining_focus_no_approach_pose",
                                    "block_id", blockId,
                                    "target_position", position(blockTarget),
                                    "sensor_world_revision", frame.worldRevision(),
                                    "sensor_game_tick", frame.gameTick()
                                )
                            );
                        }
                        WorkflowStep safetyFailure = navigateToward(
                            blockApproach.x(), blockApproach.y(), blockApproach.z(), 0.4, false
                        );
                        if (safetyFailure != null) return safetyFailure;
                    } else if (pendingTicks > MINING_NAVIGATION_NO_PROGRESS_TICKS) {
                        return WorkflowStep.failed(
                            "The selected exposed mining target did not become legitimately reachable from the player's measured focus.",
                            Map.of(
                                "block_id", blockId,
                                "target_position", position(blockTarget),
                                "target_distance_blocks", distance,
                                "focus_reachable", false,
                                "reason_code", "mining_focus_unreachable"
                            )
                        );
                    }
                } else {
                    noTargetTicks = 0;
                    initialDistance = distance;
                    Direction focusedFace = direction(frame.focus().face());
                    if (focusedFace != null) blockTargetFace = focusedFace;
                    blockActionStarted = minecraft.gameMode.startDestroyBlock(
                        blockTarget,
                        blockTargetFace
                    );
                    pendingTicks = 0;
                }
            } else {
                minecraft.gameMode.continueDestroyBlock(blockTarget, blockTargetFace);
            }
        }
        LinkedHashMap<String, Object> miningEvidence = new LinkedHashMap<>();
        miningEvidence.put("block_id", blockId);
        miningEvidence.put("requested_count", requested);
        miningEvidence.put("removed_count", completedCount);
        miningEvidence.put("world_mutations_performed", worldMutationCount);
        miningEvidence.put("target_position", position(blockTarget));
        miningEvidence.put("target_distance_blocks", distance);
        miningEvidence.put("target_face", blockTargetFace == null ? "" : blockTargetFace.getSerializedName());
        miningEvidence.put("focus_matches_target", affordance.focused());
        miningEvidence.put("sensor_world_revision", frame.worldRevision());
        miningEvidence.put("sensor_game_tick", frame.gameTick());
        miningEvidence.put("sensor_capture_duration_nanos", frame.captureDurationNanos());
        miningEvidence.put("affordance_derive_duration_nanos", affordanceDurationNanos);
        miningEvidence.put(
            "sensing_total_duration_nanos",
            frame.captureDurationNanos() + affordanceDurationNanos
        );
        miningEvidence.put("horizontal_collision", frame.horizontalCollision());
        miningEvidence.put("vertical_velocity", frame.velocityY());
        if (blockApproach != null) {
            miningEvidence.put("approach_position", Map.of(
                "x", blockApproach.x(), "y", blockApproach.y(), "z", blockApproach.z()
            ));
        }
        return WorkflowStep.running(
            Math.min(0.99, (double) completedCount / requested),
            distance > player.blockInteractionRange()
                ? "The paired player is moving within legitimate block interaction range."
                : "The client is mining the selected matching block through the normal game-mode controller.",
            miningEvidence
        );
    }

    private WorkflowStep place(long actionTicks) {
        LocalPlayer player = requirePlayer();
        String blockId = text(arguments, "block_id");
        String placementMethod = text(arguments, "placement_method");
        if (placementMethod.isBlank()) placementMethod = "block_item";
        String sourceItemId = "item_use".equals(placementMethod)
            ? text(arguments, "source_item_id")
            : blockId;
        String handName = "item_use".equals(placementMethod)
            ? text(arguments, "hand")
            : "main_hand";
        Map<String, Object> positionBinding = optionalObject(
            arguments.get("position_binding")
        );
        boolean cleanupAfterLanding = bool(arguments, "cleanup_after_landing");
        List<BlockPos> positions;
        if (!positionBinding.isEmpty()) {
            if (!"predicted_collision_cell".equals(text(positionBinding, "binding_kind"))) {
                return WorkflowStep.failed(
                    "The requested placement binding kind is not supported by this client.",
                    Map.of("position_binding_kind", text(positionBinding, "binding_kind"))
                );
            }
            int horizonTicks = integer(positionBinding, "horizon_ticks");
            double maximumDistance = number(positionBinding, "max_distance_blocks");
            boolean requireReplaceable = bool(
                positionBinding,
                "require_replaceable"
            );
            if (dynamicPlacementTarget == null) {
                latestPlacementForecast = bridge.predictedCollisionPlacementForecast(
                    player,
                    horizonTicks,
                    maximumDistance,
                    requireReplaceable
                );
                Map<String, Object> resolved = optionalObject(
                    latestPlacementForecast.get("target_position")
                );
                if (
                    latestPlacementForecast.get("predicted_reachable") != Boolean.TRUE ||
                    resolved.isEmpty()
                ) {
                    return WorkflowStep.running(
                        0.0,
                        "The bounded placement binding is waiting for a reachable predicted collision cell.",
                        withPlacementForecast(Map.of(
                            "block_id", blockId,
                            "verified_positions", completedCount,
                            "requested_positions", 1,
                            "world_mutations_performed", worldMutationCount
                        ))
                    );
                }
                dynamicPlacementTarget = new BlockPos(
                    integer(resolved, "x"),
                    integer(resolved, "y"),
                    integer(resolved, "z")
                );
                dynamicPlacementBindingEvidence = latestPlacementForecast;
                if (!dynamicPlacementTargetAdmitted(dynamicPlacementTarget, blockId)) {
                    return WorkflowStep.failed(
                        "The resolved predicted collision cell is outside the admitted mutation scope.",
                        withPlacementForecast(Map.of(
                            "block_id", blockId,
                            "target_position", position(dynamicPlacementTarget),
                            "position_binding_kind", "predicted_collision_cell"
                        ))
                    );
                }
            }
            positions = List.of(dynamicPlacementTarget);
        } else {
            positions = positions(arguments.get("positions"));
        }
        // Cleanup is a dominant post-placement state. Once the temporary
        // source has been observed, an empty target means cleanup succeeded;
        // it must not fall through to the generic placement branch and place
        // the recovered water again.
        if (landingCleanupEligible && dynamicPlacementTarget != null) {
            return cleanupLandingWater(
                player,
                dynamicPlacementTarget,
                handName
            );
        }
        while (placeIndex < positions.size() && blockMatches(positions.get(placeIndex), blockId)) {
            if (blockActionStarted) {
                placementHandAfter = bridge.observeHand(handName);
                if (handChanged(placementHandBefore, placementHandAfter)) {
                    placementInventoryMutationCount++;
                }
                worldMutationCount += "minecraft:nether_portal".equals(blockId)
                    ? Math.max(1, connectedMatchingBlockCount(
                        positions.get(placeIndex),
                        blockId,
                        512
                    ))
                    : 1;
                landingCleanupEligible = cleanupAfterLanding;
            }
            if (landingCleanupEligible && dynamicPlacementTarget != null) {
                blockActionStarted = false;
                pendingTicks = 0;
                return cleanupLandingWater(
                    player,
                    dynamicPlacementTarget,
                    handName
                );
            }
            placeIndex++;
            completedCount++;
            blockActionStarted = false;
            pendingTicks = 0;
        }
        if (placeIndex >= positions.size()) {
            return WorkflowStep.succeeded(
                "Every admitted placement position now matches the requested block.",
                withPlacementForecast(Map.of(
                    "block_id", blockId,
                    "verified_positions", completedCount,
                    "requested_positions", positions.size(),
                    "world_mutations_performed", worldMutationCount,
                    "inventory_mutations_performed", placementInventoryMutationCount
                ))
            );
        }
        BlockPos target = positions.get(placeIndex);
        latestPlacementForecast = dynamicPlacementTarget == null
            ? bridge.placementForecast(player, target, 10)
            : mergePlacementForecast(
                bridge.placementForecast(
                    player,
                    target,
                    integer(positionBinding, "horizon_ticks")
                ),
                dynamicPlacementBindingEvidence
            );
        BlockState existing = minecraft.level.getBlockState(target);
        if (!existing.canBeReplaced()) {
            return WorkflowStep.failed(
                "An admitted placement position is occupied by a different non-replaceable block.",
                withPlacementForecast(Map.of(
                    "block_id", blockId,
                    "target_position", position(target),
                    "existing_block", blockId(existing)
                ))
            );
        }
        HandObservation held = bridge.observeHand(handName);
        boolean sourceAvailable =
            (held.available() && sourceItemId.equals(held.itemId())) ||
            inventoryCount(sourceItemId) > 0;
        if (!sourceAvailable) {
            return WorkflowStep.failed(
                "The paired player does not hold the declared placement source item.",
                withPlacementForecast(Map.of(
                    "block_id", blockId,
                    "source_item_id", sourceItemId,
                    "remaining_positions", positions.size() - placeIndex
                ))
            );
        }
        double distance = placementInteractionDistance(player, target);
        if (!Double.isFinite(distance)) {
            return WorkflowStep.failed(
                "No valid support face exists for the declared placement target.",
                withPlacementForecast(Map.of(
                    "block_id", blockId,
                    "target_position", position(target)
                ))
            );
        }
        if (distance > player.blockInteractionRange()) {
            if (dynamicPlacementTarget != null && !player.onGround()) {
                // Gravity is already moving the player toward the predicted
                // collision cell. Adding horizontal navigation here changes
                // that prediction and can move the admitted target out from
                // under the player before a clutch is reachable.
                bridge.applyMovement(MovementInput.released());
            } else {
                WorkflowStep safetyFailure = navigateToward(
                    target.getX() + 0.5,
                    target.getY(),
                    target.getZ() + 0.5,
                    3.5,
                    false
                );
                if (safetyFailure != null) return safetyFailure;
            }
        } else {
            bridge.applyMovement(MovementInput.released());
            bridge.lookAt(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5, 18.0F);
            if (!blockActionStarted) {
                if (!bridge.equip(sourceItemId, handName)) {
                    return WorkflowStep.failed(
                        "The declared placement source item could not be equipped in the admitted hand.",
                        withPlacementForecast(Map.of(
                            "block_id", blockId,
                            "source_item_id", sourceItemId,
                            "hand", handName,
                            "target_position", position(target)
                        ))
                    );
                }
                placementHandBefore = bridge.observeHand(handName);
                blockActionStarted = usePlacementItem(
                    target,
                    handName,
                    placementMethod
                );
                if (!blockActionStarted) {
                    return WorkflowStep.failed(
                        "No valid support face accepted the declared placement item use.",
                        withPlacementForecast(Map.of(
                            "block_id", blockId,
                            "source_item_id", sourceItemId,
                            "hand", handName,
                            "target_position", position(target)
                        ))
                    );
                }
            }
            if (++pendingTicks > 20 && !blockMatches(target, blockId)) {
                return WorkflowStep.failed(
                    "The server did not confirm the requested block placement.",
                    withPlacementForecast(Map.of(
                        "block_id", blockId,
                        "target_position", position(target)
                    ))
                );
            }
        }
        return WorkflowStep.running(
            Math.min(0.99, (double) placeIndex / positions.size()),
            distance > player.blockInteractionRange()
                ? "The paired player is moving within legitimate placement range."
                : "The client submitted one admitted block placement and is awaiting measured world state.",
            withPlacementForecast(Map.of(
                "block_id", blockId,
                "verified_positions", completedCount,
                "requested_positions", positions.size(),
                "world_mutations_performed", worldMutationCount,
                "inventory_mutations_performed", placementInventoryMutationCount,
                "target_position", position(target)
            ))
        );
    }

    private WorkflowStep cleanupLandingWater(
        LocalPlayer player,
        BlockPos target,
        String handName
    ) {
        if (!blockMatches(target, "minecraft:water")) {
            placementHandAfter = bridge.observeHand(handName);
            placementInventoryMutationCount++;
            worldMutationCount++;
            placeIndex++;
            completedCount++;
            landingCleanupEligible = false;
            return WorkflowStep.succeeded(
                "The water-bucket rescue landed and recovered its temporary water source.",
                withPlacementForecast(Map.of(
                    "block_id", "minecraft:water",
                    "verified_positions", completedCount,
                    "requested_positions", 1,
                    "world_mutations_performed", worldMutationCount,
                    "cleanup_after_landing", true,
                    "cleanup_verified", true,
                    "controls_released", true,
                    "target_position", position(target)
                ))
            );
        }
        boolean landingSettled =
            player.onGround() ||
            (player.isInWater() && player.getDeltaMovement().y >= -0.3);
        if (!landingSettled) {
            return WorkflowStep.running(
                0.9,
                "The temporary water source is active while measured landing settles.",
                withPlacementForecast(Map.of(
                    "block_id", "minecraft:water",
                    "world_mutations_performed", worldMutationCount,
                    "cleanup_after_landing", true,
                    "cleanup_verified", false,
                    "target_position", position(target)
                ))
            );
        }
        if (!landingCleanupIssued) {
            if (!bridge.equip("minecraft:bucket", handName)) {
                return WorkflowStep.failed(
                    "The rescue landed, but the resulting empty bucket could not be equipped for cleanup.",
                    withPlacementForecast(Map.of(
                        "block_id", "minecraft:water",
                        "world_mutations_performed", worldMutationCount,
                        "cleanup_after_landing", true,
                        "cleanup_verified", false,
                        "target_position", position(target)
                    ))
                );
            }
            bridge.lookAt(
                target.getX() + 0.5,
                target.getY() + 0.5,
                target.getZ() + 0.5,
                180.0F
            );
            placementHandBefore = bridge.observeHand(handName);
            // Give the normal client movement packet one tick to publish the
            // measured camera orientation before the first bucket use. The
            // server may otherwise evaluate the interaction against the prior
            // look direction even though local rendering already moved.
            landingCleanupIssued = true;
            landingCleanupPendingTicks = 0;
            return WorkflowStep.running(
                0.92,
                "The client aligned with the temporary water source before bounded cleanup.",
                withPlacementForecast(Map.of(
                    "block_id", "minecraft:water",
                    "world_mutations_performed", worldMutationCount,
                    "cleanup_after_landing", true,
                    "cleanup_verified", false,
                    "target_position", position(target)
                ))
            );
        }
        bridge.lookAt(
            target.getX() + 0.5,
            target.getY() + 0.5,
            target.getZ() + 0.5,
            180.0F
        );
        if (landingCleanupPendingTicks % 4 == 0) {
            InteractionHand hand = "off_hand".equals(handName)
                ? InteractionHand.OFF_HAND
                : InteractionHand.MAIN_HAND;
            minecraft.gameMode.useItem(player, hand);
        }
        if (++landingCleanupPendingTicks > 20) {
            return WorkflowStep.failed(
                "The server did not confirm removal of the temporary water source.",
                withPlacementForecast(Map.of(
                    "block_id", "minecraft:water",
                    "world_mutations_performed", worldMutationCount,
                    "cleanup_after_landing", true,
                    "cleanup_verified", false,
                    "target_position", position(target)
                ))
            );
        }
        return WorkflowStep.running(
            0.95,
            "The client submitted the admitted water cleanup and is awaiting measured world state.",
            withPlacementForecast(Map.of(
                "block_id", "minecraft:water",
                "world_mutations_performed", worldMutationCount,
                "cleanup_after_landing", true,
                "cleanup_verified", false,
                "target_position", position(target)
            ))
        );
    }

    private WorkflowStep craft(long actionTicks) {
        LocalPlayer player = requirePlayer();
        String outputId = text(arguments, "output_item_id");
        int requested = integer(arguments, "count");
        int produced = Math.max(0, inventoryCount(outputId) - initialInventoryCount);
        if (produced >= requested) {
            return WorkflowStep.succeeded(
                "The requested crafted output was verified in the player inventory.",
                Map.of("output_item_id", outputId, "requested_count", requested, "produced_count", produced)
            );
        }
        Object exactRecipe = arguments.get("recipe_id");
        if (exactRecipe instanceof String recipe && !recipe.isBlank()) {
            return WorkflowStep.failed(
                "This client protocol cannot prove a resource-key recipe identity from the 1.21.8 display-only recipe book; retry with recipe_id null or add an exact recipe mapping.",
                Map.of("output_item_id", outputId, "exact_recipe_mapping_available", false)
            );
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (!(menu instanceof InventoryMenu) && !(menu instanceof CraftingMenu)) {
            return WorkflowStep.failed(
                "Crafting requires the player inventory grid or an already-open crafting table.",
                Map.of("output_item_id", outputId, "crafting_menu_available", false)
            );
        }
        Slot resultSlot = menu.getSlot(0);
        if (matches(resultSlot.getItem(), outputId)) {
            ItemStack output = resultSlot.getItem();
            int destination = -1;
            for (int index = 1; index < menu.slots.size(); index++) {
                Slot slot = menu.getSlot(index);
                if (slot.container != player.getInventory() || !slot.mayPlace(output) ||
                    (!slot.getItem().isEmpty() && !ItemStack.isSameItemSameComponents(slot.getItem(), output))) continue;
                int capacity = slot.getMaxStackSize(output) - slot.getItem().getCount();
                if (craftResultFits(requested, produced, output.getCount(), capacity, menu.getCarried().isEmpty())) {
                    destination = index;
                    break;
                }
            }
            if (destination < 0) {
                return WorkflowStep.failed("One crafting result cannot fit the remaining admitted count, empty cursor and inventory space.",
                    Map.of("output_item_id", outputId, "produced_count", produced, "requested_count", requested,
                        "result_batch_count", output.getCount(), "reason_code", "craft_result_budget_or_space_unavailable"));
            }
            // QUICK_MOVE can craft repeatedly from the grid. PICKUP takes one
            // displayed batch, then deposits it into the prechecked player slot.
            minecraft.gameMode.handleInventoryMouseClick(
                menu.containerId,
                0,
                0,
                ClickType.PICKUP,
                player
            );
            minecraft.gameMode.handleInventoryMouseClick(menu.containerId, destination, 0, ClickType.PICKUP, player);
            pendingTicks = 0;
            return WorkflowStep.running(
                Math.min(0.99, (double) produced / requested),
                "The client took one server-presented crafting result and is verifying inventory state.",
                Map.of("output_item_id", outputId, "produced_count", produced, "result_slot_present", true)
            );
        }
        RecipeDisplayEntry recipe = findCraftableRecipe(player, outputId, menu instanceof CraftingMenu);
        if (recipe == null) {
            return WorkflowStep.failed(
                "No known recipe for the requested output is craftable from the current inventory and crafting grid.",
                Map.of("output_item_id", outputId, "produced_count", produced, "craftable_recipe_found", false)
            );
        }
        if (!inventoryActionIssued) {
            minecraft.gameMode.handlePlaceRecipe(menu.containerId, recipe.id(), false);
            inventoryActionIssued = true;
            pendingTicks = 0;
        } else if (++pendingTicks > 20) {
            return WorkflowStep.failed(
                "The server did not materialize the selected recipe result in the active crafting menu.",
                Map.of("output_item_id", outputId, "recipe_display_id", recipe.id().index())
            );
        }
        if (actionTicks % 5 == 0 && resultSlot.getItem().isEmpty()) {
            inventoryActionIssued = false;
        }
        return WorkflowStep.running(
            Math.min(0.99, (double) produced / requested),
            "The client selected a craftable recipe and is awaiting the server-presented result slot.",
            Map.of(
                "output_item_id", outputId,
                "produced_count", produced,
                "recipe_display_id", recipe.id().index(),
                "crafting_table_menu", menu instanceof CraftingMenu
            )
        );
    }

    static boolean craftResultFits(int requested, int produced, int batch, int capacity, boolean cursorEmpty) {
        return cursorEmpty && requested > 0 && produced >= 0 && batch > 0 && capacity >= batch &&
            (long) produced + batch <= requested;
    }

    private WorkflowStep consume(long actionTicks) {
        LocalPlayer player = requirePlayer();
        String itemId = text(arguments, "item_id");
        int requested = integer(arguments, "count");
        double stopBelowHealth = number(arguments, "stop_below_health");
        int minimumFoodGain = integer(arguments, "minimum_food_gain");
        String expectedRemainder = arguments.get("expected_remainder_item_id") instanceof String value
            ? value
            : "";
        if (!consumeBaselineCaptured) {
            consumeBaselineCaptured = true;
            initialFoodLevel = player.getFoodData().getFoodLevel();
            initialSaturationLevel = player.getFoodData().getSaturationLevel();
            initialHealth = player.getHealth();
            consumeMinimumHealth = initialHealth;
            consumePreviousHealth = initialHealth;
            initialRemainderCount = expectedRemainder.isBlank()
                ? 0
                : inventoryCount(expectedRemainder);
        }
        int currentItemCount = inventoryCount(itemId);
        int consumed = Math.max(0, initialInventoryCount - currentItemCount);
        int foodAfter = player.getFoodData().getFoodLevel();
        float saturationAfter = player.getFoodData().getSaturationLevel();
        float healthAfter = player.getHealth();
        boolean withinUseWindow = consumeUseStarted || player.isUsingItem();
        if (withinUseWindow) {
            consumeUseStarted = true;
            consumeMinimumHealth = Math.min(consumeMinimumHealth, healthAfter);
            if (healthAfter + 0.001F < consumePreviousHealth) {
                consumeObservedHealthLoss += consumePreviousHealth - healthAfter;
                consumeHealthLossEventCount++;
            }
        }
        consumePreviousHealth = healthAfter;
        int remainderDelta = expectedRemainder.isBlank()
            ? 0
            : Math.max(0, inventoryCount(expectedRemainder) - initialRemainderCount);
        LinkedHashMap<String, Object> measured = new LinkedHashMap<>();
        measured.put("item_id", itemId);
        measured.put("requested_count", requested);
        measured.put("consumed_count", consumed);
        measured.put("hand", "main_hand");
        measured.put("food_before", initialFoodLevel);
        measured.put("food_after", foodAfter);
        measured.put("food_gain", Math.max(0, foodAfter - initialFoodLevel));
        measured.put("saturation_before", initialSaturationLevel);
        measured.put("saturation_after", saturationAfter);
        measured.put("health_before", initialHealth);
        measured.put("health_after", healthAfter);
        measured.put("health_delta", healthAfter - initialHealth);
        measured.put("minimum_health_during_use", consumeMinimumHealth);
        measured.put("observed_health_loss_during_use", consumeObservedHealthLoss);
        measured.put("health_loss_event_count_during_use", consumeHealthLossEventCount);
        measured.put("use_ticks", consumeUseTicks);
        measured.put("expected_remainder_item_id", expectedRemainder.isBlank() ? "none" : expectedRemainder);
        measured.put("remainder_item_delta", remainderDelta);
        measured.put("world_mutations_performed", 0);
        measured.put("inventory_mutations_performed", consumed);

        if (healthAfter <= stopBelowHealth) {
            bridge.releaseItemUse();
            measured.put("reason_code", "consume_health_floor_reached");
            return WorkflowStep.failed(
                "Consumption stopped because measured health crossed the admitted hard floor.",
                measured
            );
        }
        if (consumed >= requested) {
            bridge.releaseItemUse();
            if (foodAfter - initialFoodLevel < minimumFoodGain) {
                measured.put("reason_code", "minimum_food_gain_not_met");
                return WorkflowStep.failed(
                    "The admitted item was consumed, but the measured food gain did not meet the requested postcondition.",
                    measured
                );
            }
            if (!expectedRemainder.isBlank() && remainderDelta < consumed) {
                measured.put("reason_code", "expected_remainder_not_observed");
                return WorkflowStep.failed(
                    "The consumed item did not produce the admitted remainder-item postcondition.",
                    measured
                );
            }
            measured.put("reason_code", "consume_postconditions_satisfied");
            return WorkflowStep.succeeded(
                "The exact admitted consumable and its food/inventory postconditions were measured.",
                measured
            );
        }
        if (currentItemCount < 1) {
            bridge.releaseItemUse();
            measured.put("reason_code", "consume_item_unavailable");
            return WorkflowStep.failed(
                "The admitted consumable is not available in the player inventory.",
                measured
            );
        }
        if (!bridge.equipmentMatches(itemId, "main_hand") &&
            !bridge.equip(itemId, "main_hand")) {
            bridge.releaseItemUse();
            measured.put("reason_code", "consume_equip_failed");
            return WorkflowStep.failed(
                "The admitted consumable could not be equipped in the main hand.",
                measured
            );
        }
        if (!bridge.holdItemUse(itemId, "main_hand")) {
            bridge.releaseItemUse();
            measured.put("reason_code", "consume_use_rejected");
            return WorkflowStep.failed(
                "The native client rejected continuous use of the admitted main-hand item.",
                measured
            );
        }
        if (player.isUsingItem()) {
            consumeUseTicks++;
            consumeStartWaitTicks = 0;
        } else if (++consumeStartWaitTicks > 20) {
            bridge.releaseItemUse();
            measured.put("reason_code", "consume_use_not_started");
            return WorkflowStep.failed(
                "The admitted item did not enter vanilla continuous-use state within the bounded start window.",
                measured
            );
        }
        measured.put("use_ticks", consumeUseTicks);
        measured.put("reason_code", player.isUsingItem()
            ? "consume_in_progress"
            : "consume_waiting_for_use_start");
        return WorkflowStep.running(
            Math.min(0.99, (double) consumed / requested),
            "The client is maintaining the admitted item-use input and awaiting measured consumption.",
            measured
        );
    }

    private WorkflowStep inventoryTransfer(long actionTicks) {
        LocalPlayer player = requirePlayer();
        String itemId = text(arguments, "item_id");
        String direction = text(arguments, "direction");
        int requested = integer(arguments, "count");
        int current = inventoryCount(itemId);
        int transferred = "deposit".equals(direction)
            ? Math.max(0, initialInventoryCount - current)
            : Math.max(0, current - initialInventoryCount);
        if (transferred >= requested) {
            return WorkflowStep.succeeded(
                "The exact requested inventory transfer was measured on the player side of the container.",
                Map.of("item_id", itemId, "direction", direction, "requested_count", requested, "transferred_count", transferred)
            );
        }
        if ("looked_at_container".equals(text(arguments, "container_target")) &&
            player.containerMenu == player.inventoryMenu && !containerOpenRequested) {
            containerOpenRequested = bridge.interact("looked_at_block", "main_hand", "interact");
            if (!containerOpenRequested) {
                return WorkflowStep.failed(
                    "The looked-at block did not accept a container interaction.",
                    Map.of("container_opened", false, "item_id", itemId)
                );
            }
            return WorkflowStep.running(
                null,
                "The client requested the looked-at container and is awaiting its server menu.",
                Map.of("container_open_requested", true, "item_id", itemId)
            );
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (menu == player.inventoryMenu || menu instanceof CraftingMenu) {
            if (++pendingTicks > 20) {
                return WorkflowStep.failed(
                    "No transferable container menu became available.",
                    Map.of("container_open", false, "item_id", itemId)
                );
            }
            return WorkflowStep.running(
                null,
                "The client is waiting for the admitted container menu.",
                Map.of("container_open", false, "item_id", itemId)
            );
        }
        if (!inventoryActionIssued) {
            int remaining = requested - transferred;
            inventoryCountBeforeIssued = current;
            int moved = transferExact(menu, player, itemId, direction, remaining);
            if (moved < 1) {
                return WorkflowStep.failed(
                    "The active container could not accept or provide the requested item.",
                    Map.of("item_id", itemId, "direction", direction, "transferred_count", transferred)
                );
            }
            inventoryActionIssued = true;
            pendingTicks = 0;
        } else if (current != inventoryCountBeforeIssued) {
            inventoryActionIssued = false;
        } else if (++pendingTicks > 20) {
            return WorkflowStep.failed(
                "The server did not confirm the requested container transfer.",
                Map.of("item_id", itemId, "direction", direction, "transferred_count", transferred)
            );
        }
        return WorkflowStep.running(
            Math.min(0.99, (double) transferred / requested),
            "The client submitted a bounded inventory transfer and is verifying the player inventory delta.",
            Map.of("item_id", itemId, "direction", direction, "requested_count", requested, "transferred_count", transferred)
        );
    }

    private int transferExact(
        AbstractContainerMenu menu,
        LocalPlayer player,
        String itemId,
        String direction,
        int remaining
    ) {
        boolean deposit = "deposit".equals(direction);
        Inventory inventory = player.getInventory();
        int sourceIndex = -1;
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot slot = menu.slots.get(index);
            boolean playerSlot = slot.container == inventory;
            if (sourceIndex < 0 && playerSlot == deposit && matches(slot.getItem(), itemId)) {
                sourceIndex = index;
            }
        }
        if (sourceIndex < 0) return 0;
        ItemStack source = menu.getSlot(sourceIndex).getItem();
        int destinationIndex = -1;
        for (int index = 0; index < menu.slots.size(); index++) {
            Slot slot = menu.slots.get(index);
            boolean playerSlot = slot.container == inventory;
            if (playerSlot != deposit && slot.mayPlace(source) &&
                (slot.getItem().isEmpty() || matches(slot.getItem(), itemId))) {
                destinationIndex = index;
                break;
            }
        }
        if (destinationIndex < 0) return 0;
        Slot destination = menu.getSlot(destinationIndex);
        int capacity = destination.getMaxStackSize(source) - destination.getItem().getCount();
        int count = Math.min(Math.min(remaining, source.getCount()), capacity);
        count = Math.min(count, MAX_EXACT_TRANSFER_PER_STEP);
        if (count < 1) return 0;
        if (count == source.getCount()) {
            minecraft.gameMode.handleInventoryMouseClick(
                menu.containerId,
                sourceIndex,
                0,
                ClickType.QUICK_MOVE,
                player
            );
            return count;
        }
        minecraft.gameMode.handleInventoryMouseClick(menu.containerId, sourceIndex, 0, ClickType.PICKUP, player);
        for (int index = 0; index < count; index++) {
            minecraft.gameMode.handleInventoryMouseClick(menu.containerId, destinationIndex, 1, ClickType.PICKUP, player);
        }
        minecraft.gameMode.handleInventoryMouseClick(menu.containerId, sourceIndex, 0, ClickType.PICKUP, player);
        return count;
    }

    private RecipeDisplayEntry findCraftableRecipe(
        LocalPlayer player,
        String outputId,
        boolean largeGrid
    ) {
        StackedItemContents contents = new StackedItemContents();
        player.getInventory().fillStackedContents(contents);
        for (RecipeCollection collection : player.getRecipeBook().getCollections()) {
            for (RecipeDisplayEntry entry : collection.getRecipes()) {
                if (!entry.canCraft(contents) || !recipeFits(entry, largeGrid)) continue;
                boolean matchesOutput = entry.resultItems(
                    SlotDisplayContext.fromLevel(minecraft.level)
                ).stream().anyMatch(stack -> matches(stack, outputId));
                if (matchesOutput) return entry;
            }
        }
        return null;
    }

    boolean isCraftable(String outputId) {
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null) return false;
        AbstractContainerMenu menu = player.containerMenu;
        if (menu != player.inventoryMenu && !(menu instanceof CraftingMenu)) return false;
        return findCraftableRecipe(player, outputId, menu instanceof CraftingMenu) != null;
    }

    private static boolean recipeFits(RecipeDisplayEntry entry, boolean largeGrid) {
        if (largeGrid) return true;
        if (entry.display() instanceof ShapedCraftingRecipeDisplay shaped) {
            return shaped.width() <= 2 && shaped.height() <= 2;
        }
        if (entry.display() instanceof ShapelessCraftingRecipeDisplay shapeless) {
            return shapeless.ingredients().size() <= 4;
        }
        return false;
    }

    private boolean usePlacementItem(
        BlockPos target,
        String handName,
        String placementMethod
    ) {
        LocalPlayer player = requirePlayer();
        InteractionHand hand = "off_hand".equals(handName)
            ? InteractionHand.OFF_HAND
            : InteractionHand.MAIN_HAND;
        for (Direction direction : Direction.values()) {
            BlockPos support = target.relative(direction);
            BlockState supportState = minecraft.level.getBlockState(support);
            if (supportState.isAir() || supportState.canBeReplaced()) continue;
            Direction supportFace = direction.getOpposite();
            Vec3 hitLocation = supportFaceHitLocation(support, supportFace);
            BlockHitResult hit = new BlockHitResult(
                hitLocation,
                supportFace,
                support,
                false
            );
            bridge.lookAt(
                hitLocation.x,
                hitLocation.y,
                hitLocation.z,
                180.0F
            );
            for (String interactionMode : placementInteractionOrder(placementMethod)) {
                InteractionResult result = "use_item".equals(interactionMode)
                    ? minecraft.gameMode.useItem(player, hand)
                    : minecraft.gameMode.useItemOn(player, hand, hit);
                if (result.consumesAction()) return true;
            }
        }
        return false;
    }

    static List<String> placementInteractionOrder(String placementMethod) {
        // Flint-and-steel and similar items require use-on-block semantics,
        // while buckets implement Item.use and perform a POV ray cast. Try the
        // already-admitted support face first, then the air/POV form only for
        // the general item_use contract. Ordinary block items never need the
        // fallback and therefore cannot produce a second interaction packet.
        return "item_use".equals(placementMethod)
            ? List.of("use_item_on", "use_item")
            : List.of("use_item_on");
    }

    private static boolean handChanged(
        HandObservation before,
        HandObservation after
    ) {
        return before.available() && after.available() &&
            (
                before.count() != after.count() ||
                before.damage() != after.damage() ||
                !before.itemId().equals(after.itemId())
            );
    }

    private int connectedMatchingBlockCount(
        BlockPos start,
        String expectedBlockId,
        int ceiling
    ) {
        if (!blockMatches(start, expectedBlockId) || ceiling < 1) return 0;
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        pending.add(start);
        while (!pending.isEmpty() && visited.size() < ceiling) {
            BlockPos current = pending.removeFirst();
            if (!visited.add(current.asLong())) continue;
            for (Direction direction : Direction.values()) {
                BlockPos adjacent = current.relative(direction);
                if (
                    !visited.contains(adjacent.asLong()) &&
                    blockMatches(adjacent, expectedBlockId)
                ) pending.addLast(adjacent);
            }
        }
        return visited.size();
    }

    static Vec3 supportFaceHitLocation(BlockPos support, Direction supportFace) {
        return Vec3.atCenterOf(support).add(
            supportFace.getStepX() * 0.5,
            supportFace.getStepY() * 0.5,
            supportFace.getStepZ() * 0.5
        );
    }

    private double placementInteractionDistance(LocalPlayer player, BlockPos target) {
        double nearest = Double.POSITIVE_INFINITY;
        for (Direction direction : Direction.values()) {
            BlockPos support = target.relative(direction);
            BlockState supportState = minecraft.level.getBlockState(support);
            if (supportState.isAir() || supportState.canBeReplaced()) continue;
            Vec3 hitLocation = supportFaceHitLocation(
                support,
                direction.getOpposite()
            );
            nearest = Math.min(
                nearest,
                distance(
                    player.getX(),
                    player.getEyeY(),
                    player.getZ(),
                    hitLocation.x,
                    hitLocation.y,
                    hitLocation.z
                )
            );
        }
        return nearest;
    }

    private BlockPos findNearestBlock(BlockPos center, String blockId, int radius) {
        for (int shell = 0; shell <= radius; shell++) {
            BlockPos nearest = null;
            double nearestDistance = Double.POSITIVE_INFINITY;
            for (int dx = -shell; dx <= shell; dx++) {
                for (int dy = -shell; dy <= shell; dy++) {
                    for (int dz = -shell; dz <= shell; dz++) {
                        if (Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz)) != shell) continue;
                        BlockPos candidate = center.offset(dx, dy, dz);
                        if (completedBlockTargets.contains(candidate.asLong()) ||
                            !minecraft.level.hasChunkAt(candidate) ||
                            !blockMatches(candidate, blockId) ||
                            nearestExposedMiningFace(
                                candidate,
                                requirePlayer().getEyePosition(),
                                this::isOpenMiningNeighbor
                            ) == null) continue;
                        double distance = candidate.distSqr(center);
                        if (distance < nearestDistance) {
                            nearest = candidate.immutable();
                            nearestDistance = distance;
                        }
                    }
                }
            }
            if (nearest != null) return nearest;
        }
        return null;
    }

    private boolean isOpenMiningNeighbor(BlockPos position) {
        if (!minecraft.level.hasChunkAt(position)) return false;
        BlockState state = minecraft.level.getBlockState(position);
        return state.isAir() || state.canBeReplaced();
    }

    private boolean isMiningSupport(BlockPos position) {
        if (!minecraft.level.hasChunkAt(position)) return false;
        BlockState state = minecraft.level.getBlockState(position);
        return !state.isAir() && !state.canBeReplaced();
    }

    static boolean miningNavigationStalled(int noProgressTicks) {
        return noProgressTicks > MINING_NAVIGATION_NO_PROGRESS_TICKS;
    }

    static boolean miningFocusNeedsApproach(int pendingFocusTicks) {
        return pendingFocusTicks > 5;
    }

    static Direction nearestExposedMiningFace(
        BlockPos target,
        Vec3 actorEye,
        Predicate<BlockPos> openNeighbor
    ) {
        Direction nearest = null;
        double nearestDistance = Double.POSITIVE_INFINITY;
        for (Direction direction : Direction.values()) {
            if (!openNeighbor.test(target.relative(direction))) continue;
            Vec3 face = miningFaceCenter(target, direction);
            double distance = actorEye.distanceToSqr(face);
            if (distance < nearestDistance) {
                nearest = direction;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static Vec3 miningFaceCenter(BlockPos target, Direction face) {
        return MiningTargetAffordance.faceCenter(target, face);
    }

    private static Direction direction(String serializedName) {
        for (Direction direction : Direction.values()) {
            if (direction.getSerializedName().equals(serializedName)) return direction;
        }
        return null;
    }

    private void recordSensingDuration(long durationNanos) {
        if (sensingTimingSampleCount >= sensingTimingSamplesNanos.length) return;
        sensingTimingSamplesNanos[sensingTimingSampleCount++] = Math.max(0, durationNanos);
    }

    private long p95SensingDurationNanos() {
        return p95DurationNanos(sensingTimingSamplesNanos, sensingTimingSampleCount);
    }

    static long p95DurationNanos(long[] samples, int count) {
        if (samples == null || count < 0 || count > samples.length) {
            throw new IllegalArgumentException("bounded timing sample count is invalid");
        }
        if (count == 0) return 0;
        long[] sorted = Arrays.copyOf(samples, count);
        Arrays.sort(sorted);
        int index = Math.max(0, (int) Math.ceil(sorted.length * 0.95) - 1);
        return sorted[index];
    }

    private WorkflowStep navigateToward(
        double x,
        double y,
        double z,
        double arrivalRadius,
        boolean sprint
    ) {
        return navigateToward(x, y, z, arrivalRadius, sprint, true, false);
    }

    private WorkflowStep navigateToward(
        double x,
        double y,
        double z,
        double arrivalRadius,
        boolean sprint,
        boolean allowCollisionJump,
        boolean controlledJumpArc
    ) {
        LocalPlayer player = requirePlayer();
        double current = distance(player.getX(), player.getY(), player.getZ(), x, y, z);
        if (current <= arrivalRadius) {
            bridge.applyMovement(MovementInput.released());
            return null;
        }
        double healthFloor = arguments.get("stop_below_health") instanceof Number value
            ? value.doubleValue()
            : 6.0;
        LocomotionSafetyEnvelope.Check safety = locomotionSafetyCheck(
            x,
            z,
            healthFloor,
            controlledJumpArc
        );
        if (!safety.decision().admitted()) {
            bridge.applyMovement(MovementInput.released());
            LinkedHashMap<String, Object> measurements = new LinkedHashMap<>(
                safety.measurements()
            );
            addWorkflowMotionMeasurements(measurements, player);
            return WorkflowStep.failed(
                "Native locomotion stopped before asserting forward control because the local safety envelope refused the next step.",
                measurements
            );
        }
        bridge.lookAt(x, y + 0.5, z, 18.0F);
        bridge.applyMovement(new MovementInput(
            true,
            false,
            false,
            false,
            allowCollisionJump && player.horizontalCollision && player.onGround(),
            sprint
        ));
        return null;
    }

    LocomotionSafetyEnvelope.Check locomotionSafetyCheck(
        double targetX,
        double targetZ,
        double minimumHealth,
        boolean controlledJumpArc
    ) {
        LocalPlayer player = requirePlayer();
        PlayerSensorFrame frame = bridge.sensorFrame();
        double dx = targetX - player.getX();
        double dz = targetZ - player.getZ();
        double horizontal = Math.hypot(dx, dz);
        double stepX = horizontal < 0.001 ? player.getX() : player.getX() + dx / horizontal * 0.8;
        double stepZ = horizontal < 0.001 ? player.getZ() : player.getZ() + dz / horizontal * 0.8;
        BlockPos predictedFeet = BlockPos.containing(stepX, player.getY() + 0.01, stepZ);
        boolean geometryKnown = minecraft.level.hasChunkAt(predictedFeet) &&
            minecraft.level.hasChunkAt(predictedFeet.below(2));
        boolean predictedLava = geometryKnown && (
            minecraft.level.getFluidState(predictedFeet).is(FluidTags.LAVA) ||
            minecraft.level.getFluidState(predictedFeet.below()).is(FluidTags.LAVA) ||
            minecraft.level.getFluidState(predictedFeet.below(2)).is(FluidTags.LAVA)
        );
        double predictedDrop = 3;
        if (geometryKnown) {
            if (isLocomotionSupport(predictedFeet.below())) predictedDrop = 0;
            else if (isLocomotionSupport(predictedFeet.below(2))) predictedDrop = 1;
        }
        LocomotionSafetyEnvelope.Decision decision = new LocomotionSafetyEnvelope(
            minimumHealth,
            1
        ).assess(
            new LocomotionSafetyEnvelope.Observation(
                frame.health(), frame.onGround(), frame.velocityY(), player.fallDistance,
                player.isInLava(), player.isOnFire(), geometryKnown,
                predictedDrop, predictedLava, controlledJumpArc
            )
        );
        return new LocomotionSafetyEnvelope.Check(
            decision,
            movementSafetyMeasurements(
                frame,
                predictedFeet,
                geometryKnown,
                predictedDrop,
                decision,
                minimumHealth,
                controlledJumpArc
            )
        );
    }

    private Map<String, Object> movementSafetyMeasurements(
        PlayerSensorFrame frame,
        BlockPos predictedFeet,
        boolean geometryKnown,
        double predictedDrop,
        LocomotionSafetyEnvelope.Decision decision,
        double minimumHealth,
        boolean controlledJumpArc
    ) {
        LocalPlayer player = requirePlayer();
        return Map.ofEntries(
            Map.entry("reason_code", decision.reasonCode()),
            Map.entry("safety_interrupted", true),
            Map.entry("effect_prevented", true),
            Map.entry("sensor_world_revision", frame.worldRevision()),
            Map.entry("sensor_game_tick", frame.gameTick()),
            Map.entry("measured_health", (double) frame.health()),
            Map.entry("stop_below_health", minimumHealth),
            Map.entry("vertical_velocity", frame.velocityY()),
            Map.entry("fall_distance_blocks", (double) player.fallDistance),
            Map.entry("landing_geometry_known", geometryKnown),
            Map.entry("predicted_drop_blocks", predictedDrop),
            Map.entry("controlled_jump_arc", controlledJumpArc),
            Map.entry("predicted_landing_position", position(predictedFeet)),
            Map.entry("controls_released", true)
        );
    }

    private boolean isLocomotionSupport(BlockPos position) {
        if (!minecraft.level.hasChunkAt(position)) return false;
        BlockState state = minecraft.level.getBlockState(position);
        return !state.getCollisionShape(minecraft.level, position).isEmpty();
    }

    private BoundedNavigationFrontier.CellKind nativeNavigationCell(
        BoundedNavigationFrontier.Position position
    ) {
        BlockPos block = blockPosition(position);
        if (minecraft.level == null || !minecraft.level.hasChunkAt(block)) {
            return BoundedNavigationFrontier.CellKind.UNKNOWN;
        }
        BlockState state = minecraft.level.getBlockState(block);
        if (
            state.is(Blocks.LAVA) ||
            state.is(Blocks.FIRE) ||
            state.is(Blocks.SOUL_FIRE) ||
            state.is(Blocks.MAGMA_BLOCK) ||
            state.is(Blocks.CACTUS) ||
            state.is(Blocks.SWEET_BERRY_BUSH) ||
            state.is(Blocks.POWDER_SNOW)
        ) return BoundedNavigationFrontier.CellKind.HAZARD;
        if (!state.getFluidState().isEmpty()) {
            return BoundedNavigationFrontier.CellKind.FLUID;
        }
        if (state.isAir() || state.canBeReplaced()) {
            return BoundedNavigationFrontier.CellKind.CLEAR;
        }
        if (!state.getCollisionShape(minecraft.level, block).isEmpty()) {
            return BoundedNavigationFrontier.CellKind.SOLID;
        }
        return BoundedNavigationFrontier.CellKind.BLOCKED;
    }

    private static BoundedNavigationFrontier.Position frontierPosition(
        BlockPos position
    ) {
        return new BoundedNavigationFrontier.Position(
            position.getX(),
            position.getY(),
            position.getZ()
        );
    }

    static BlockPos navigationStepValidationOrigin(
        BlockPos current,
        BlockPos expectedFrom,
        boolean onGround
    ) {
        // A jump can put the player's feet one block above the admitted route
        // for a few ticks. That airborne cell is not a new foothold from which
        // the ground planner can prove a path; retain the planned support cell
        // only while horizontal position still matches it.
        if (!onGround && current.getY() == expectedFrom.getY() + 1 &&
            current.getX() == expectedFrom.getX() &&
            current.getZ() == expectedFrom.getZ()) {
            return expectedFrom;
        }
        return current;
    }

    static boolean retainOwnedJumpArc(
        boolean previousOwnedArc,
        boolean onGround,
        boolean jumpNow
    ) {
        return jumpNow || (!onGround && previousOwnedArc);
    }

    private static BlockPos blockPosition(
        BoundedNavigationFrontier.Position position
    ) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    private static int chebyshevDistance(BlockPos first, BlockPos second) {
        return Math.max(
            Math.max(
                Math.abs(first.getX() - second.getX()),
                Math.abs(first.getY() - second.getY())
            ),
            Math.abs(first.getZ() - second.getZ())
        );
    }

    private AbstractClientPlayer findPlayer(String nativeId) {
        if (nativeId.isBlank() || minecraft.level == null) return null;
        UUID uuid = null;
        try {
            uuid = UUID.fromString(nativeId);
        } catch (IllegalArgumentException ignored) {}
        for (AbstractClientPlayer player : minecraft.level.players()) {
            if ((uuid != null && player.getUUID().equals(uuid)) ||
                player.getScoreboardName().equalsIgnoreCase(nativeId)) return player;
        }
        return null;
    }

    private int inventoryCount(String itemId) {
        LocalPlayer player = requirePlayer();
        int count = 0;
        for (int index = 0; index < player.getInventory().getContainerSize(); index++) {
            ItemStack stack = player.getInventory().getItem(index);
            if (matches(stack, itemId)) count += stack.getCount();
        }
        return count;
    }

    private static int findInventorySlot(Inventory inventory, String itemId) {
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            if (matches(inventory.getItem(index), itemId)) return index;
        }
        return -1;
    }

    private boolean blockMatches(BlockPos position, String blockId) {
        return blockId(minecraft.level.getBlockState(position)).equals(blockId);
    }

    private static String blockId(BlockState state) {
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    private static boolean matches(ItemStack stack, String itemId) {
        return !stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).toString().equals(itemId);
    }

    private LocalPlayer requirePlayer() {
        if (minecraft.player == null || minecraft.level == null || minecraft.gameMode == null) {
            throw new IllegalStateException("The Minecraft player is not connected.");
        }
        return minecraft.player;
    }

    boolean screenAutomationAllowed() {
        return ownsScreen(actionKind);
    }

    static boolean ownsScreen(String actionKind) {
        return "craft".equals(actionKind) || "inventory_transfer".equals(actionKind);
    }

    void cancel() {
        boolean closeOwnedScreen = ownsScreen(actionKind);
        if (minecraft.gameMode != null && blockActionStarted) minecraft.gameMode.stopDestroyBlock();
        if (baritoneGoalOwned) baritone.cancel();
        if (closeOwnedScreen && minecraft.screen != null) minecraft.setScreen(null);
        actionKind = "";
        arguments = Map.of();
        controlEngine = "native_fabric";
        initialInventoryCount = 0;
        completedCount = 0;
        worldMutationCount = 0;
        placeIndex = 0;
        noTargetTicks = 0;
        pendingTicks = 0;
        initialDistance = -1;
        workflowStartX = null;
        workflowStartY = null;
        workflowStartZ = null;
        navigationLastX = null;
        navigationLastY = null;
        navigationLastZ = null;
        navigationPathDistance = 0;
        nativeNavigationOwnedJumpArc = false;
        blockTarget = null;
        blockTargetFace = null;
        blockApproach = null;
        maxSensorCaptureDurationNanos = 0;
        maxAffordanceDeriveDurationNanos = 0;
        sensingTimingSampleCount = 0;
        blockActionStarted = false;
        containerOpenRequested = false;
        inventoryActionIssued = false;
        inventoryCountBeforeIssued = 0;
        baritoneGoalOwned = false;
        baritoneFinalApproach = false;
        nativeNavigationPlan = null;
        nativeNavigationStepIndex = 0;
        nativeNavigationReplanCount = 0;
        nativeNavigationNoProgressTicks = 0;
        nativeNavigationClosestStepDistance = Double.POSITIVE_INFINITY;
        nativeNavigationLastReplanReason = "initial_plan";
        latestPlacementForecast = Map.of();
        dynamicPlacementBindingEvidence = Map.of();
        dynamicPlacementTarget = null;
        placementInventoryMutationCount = 0;
        placementHandBefore = HandObservation.unavailable();
        placementHandAfter = HandObservation.unavailable();
        landingCleanupEligible = false;
        landingCleanupIssued = false;
        landingCleanupPendingTicks = 0;
        consumeBaselineCaptured = false;
        initialFoodLevel = 0;
        initialSaturationLevel = 0;
        initialHealth = 0;
        initialRemainderCount = 0;
        consumeUseTicks = 0;
        consumeStartWaitTicks = 0;
        consumeUseStarted = false;
        consumeMinimumHealth = 0;
        consumePreviousHealth = 0;
        consumeObservedHealthLoss = 0;
        consumeHealthLossEventCount = 0;
        completedBlockTargets.clear();
    }

    BaritoneFacade baritone() {
        return baritone;
    }

    private double progressFromDistance(double distance, double radius) {
        double baseline = Math.max(radius + 0.01, initialDistance);
        return Math.max(0, Math.min(0.99, 1.0 - distance / baseline));
    }

    private static double eyeDistance(LocalPlayer player, BlockPos pos) {
        return distance(
            player.getX(),
            player.getEyeY(),
            player.getZ(),
            pos.getX() + 0.5,
            pos.getY() + 0.5,
            pos.getZ() + 0.5
        );
    }

    private static double distance(
        double x1,
        double y1,
        double z1,
        double x2,
        double y2,
        double z2
    ) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }

    private static Map<String, Object> position(BlockPos position) {
        return Map.of("x", position.getX(), "y", position.getY(), "z", position.getZ());
    }

    private Map<String, Object> withPlacementForecast(Map<String, Object> measurements) {
        Map<String, Object> result = new LinkedHashMap<>(measurements);
        String placementMethod = text(arguments, "placement_method");
        if (placementMethod.isBlank()) placementMethod = "block_item";
        result.put("placement_method", placementMethod);
        result.put(
            "source_item_id",
            "item_use".equals(placementMethod)
                ? text(arguments, "source_item_id")
                : text(arguments, "block_id")
        );
        result.put(
            "hand",
            "item_use".equals(placementMethod)
                ? text(arguments, "hand")
                : "main_hand"
        );
        result.put(
            "inventory_mutations_performed",
            placementInventoryMutationCount
        );
        if (placementHandBefore.available()) {
            result.put("held_item_id_before", placementHandBefore.itemId());
            result.put("held_item_count_before", placementHandBefore.count());
            result.put("held_item_damage_before", placementHandBefore.damage());
        }
        if (placementHandAfter.available()) {
            result.put("held_item_id_after", placementHandAfter.itemId());
            result.put("held_item_count_after", placementHandAfter.count());
            result.put("held_item_damage_after", placementHandAfter.damage());
        }
        if (!latestPlacementForecast.isEmpty()) {
            result.put("placement_prediction", latestPlacementForecast);
        }
        return Map.copyOf(result);
    }

    private Map<String, Object> mergePlacementForecast(
        Map<String, Object> current,
        Map<String, Object> binding
    ) {
        Map<String, Object> result = new LinkedHashMap<>(current);
        for (String key : List.of(
            "position_binding_kind",
            "first_collision_tick",
            "max_distance_blocks",
            "require_replaceable",
            "actor_position_at_resolution",
            "resolved_distance_blocks"
        )) {
            Object value = binding.get(key);
            if (value != null) result.put(key, value);
        }
        return Map.copyOf(result);
    }

    private boolean dynamicPlacementTargetAdmitted(BlockPos target, String blockId) {
        Map<String, Object> scope = optionalObject(
            arguments.get("_helix_admitted_mutation_scope")
        );
        if (scope.isEmpty()) return true;
        if (!bool(scope, "world_mutation_allowed")) return false;
        List<String> allowedBlocks = strings(scope.get("allowed_block_ids"));
        if (!allowedBlocks.isEmpty() && !allowedBlocks.contains(blockId)) return false;
        List<Map<String, Object>> regions = objects(scope.get("allowed_regions"));
        if (regions.isEmpty()) return true;
        return regions.stream().anyMatch(region -> {
            Map<String, Object> minimum = optionalObject(region.get("min"));
            Map<String, Object> maximum = optionalObject(region.get("max"));
            return !minimum.isEmpty() && !maximum.isEmpty() &&
                target.getX() >= integer(minimum, "x") &&
                target.getX() <= integer(maximum, "x") &&
                target.getY() >= integer(minimum, "y") &&
                target.getY() <= integer(maximum, "y") &&
                target.getZ() >= integer(minimum, "z") &&
                target.getZ() <= integer(maximum, "z");
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("The workflow arguments are incomplete.");
        }
        return (Map<String, Object>) map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> optionalObject(Object value) {
        return value instanceof Map<?, ?> map
            ? (Map<String, Object>) map
            : Map.of();
    }

    private static List<Map<String, Object>> objects(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object entry : values) {
            Map<String, Object> record = optionalObject(entry);
            if (!record.isEmpty()) result.add(record);
        }
        return List.copyOf(result);
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> values)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object entry : values) {
            if (entry instanceof String text && !text.isBlank()) result.add(text);
        }
        return List.copyOf(result);
    }

    private static List<BlockPos> positions(Object value) {
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException("Placement positions are required.");
        }
        List<BlockPos> positions = new ArrayList<>();
        for (Object entry : values) {
            Map<String, Object> position = object(entry);
            positions.add(new BlockPos(
                integer(position, "x"),
                integer(position, "y"),
                integer(position, "z")
            ));
        }
        return List.copyOf(positions);
    }

    private static BlockPos optionalBlockPosition(Object value) {
        Map<String, Object> position = optionalObject(value);
        if (position.isEmpty()) return null;
        return new BlockPos(
            integer(position, "x"),
            integer(position, "y"),
            integer(position, "z")
        );
    }

    private static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value instanceof String text ? text.trim() : "";
    }

    private static double number(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())) {
            throw new IllegalArgumentException("The numeric workflow argument " + key + " is required.");
        }
        return number.doubleValue();
    }

    private static int integer(Map<String, Object> map, String key) {
        double value = number(map, key);
        if (Math.rint(value) != value) {
            throw new IllegalArgumentException("The workflow argument " + key + " must be an integer.");
        }
        return (int) value;
    }

    private static boolean bool(Map<String, Object> map, String key) {
        return map.get(key) == Boolean.TRUE;
    }
}
