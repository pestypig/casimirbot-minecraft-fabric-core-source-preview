package com.casimirbot.helixplayer.fabric;

import com.casimirbot.helixplayer.fabric.PlayerActionWorkflow.WorkflowStep;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deterministic, bounded hostile-PvE reflex controller. The semantic planner
 * selects the profile; this controller only applies the admitted repertoire at
 * tick speed and never broadens target eligibility beyond hostile mobs.
 */
final class MinecraftCombatGuardian {
    static final String PROFILE_ID = "resident.minecraft.hostile-combat.v1";
    static final String ARTIFACT_VERSION = "1.3.1";

    enum MovementMode {
        HOLD,
        APPROACH,
        FLANK_LEFT,
        FLANK_RIGHT,
        RETREAT,
        COVER_LEFT,
        COVER_RIGHT,
        EVADE_LEFT,
        EVADE_RIGHT
    }

    record Profile(
        Set<String> hostileEntityTypeIds,
        String combatMode,
        double maxAcquisitionDistance,
        double minimumAttackCooldown,
        int maxAttackPulses,
        int maxTargetSwitches,
        int targetCommitTicks,
        double retreatStartDistance,
        double retreatStopDistance,
        int retreatWhenHostileCountAtLeast,
        double stopBelowHealth,
        String approachPolicy,
        int maxApproachTicks,
        String coverPolicy,
        int maxCoverTicks,
        String projectileResponse,
        int projectileEvasionHorizonTicks,
        int maxEvasionTicks,
        String shieldHand,
        int maxShieldHoldTicks
    ) {
        Profile {
            hostileEntityTypeIds = Set.copyOf(Objects.requireNonNull(hostileEntityTypeIds));
            if (hostileEntityTypeIds.isEmpty() || hostileEntityTypeIds.size() > 16) {
                throw new IllegalArgumentException("hostileEntityTypeIds must contain 1-16 values");
            }
            if (!Set.of("engage", "disengage_to_distance").contains(combatMode)) {
                throw new IllegalArgumentException("combatMode is unsupported");
            }
            if (maxAcquisitionDistance < 2 || maxAcquisitionDistance > 32) {
                throw new IllegalArgumentException("maxAcquisitionDistance must be 2-32");
            }
            if (minimumAttackCooldown < 0.1 || minimumAttackCooldown > 1) {
                throw new IllegalArgumentException("minimumAttackCooldown must be 0.1-1");
            }
            if (maxAttackPulses < 1 || maxAttackPulses > 256) {
                throw new IllegalArgumentException("maxAttackPulses must be 1-256");
            }
            if (maxTargetSwitches < 0 || maxTargetSwitches > 64) {
                throw new IllegalArgumentException("maxTargetSwitches must be 0-64");
            }
            if (targetCommitTicks < 0 || targetCommitTicks > 200) {
                throw new IllegalArgumentException("targetCommitTicks must be 0-200");
            }
            if (
                retreatStartDistance < 1 || retreatStartDistance > 6 ||
                retreatStopDistance <= retreatStartDistance || retreatStopDistance > 8
            ) {
                throw new IllegalArgumentException("retreat distances are outside the bounded envelope");
            }
            if (retreatWhenHostileCountAtLeast < 1 || retreatWhenHostileCountAtLeast > 16) {
                throw new IllegalArgumentException("retreat hostile threshold must be 1-16");
            }
            if (stopBelowHealth < 1 || stopBelowHealth > 20) {
                throw new IllegalArgumentException("stopBelowHealth must be 1-20");
            }
            if (!Set.of("none", "direct_bounded", "local_reroute_bounded").contains(approachPolicy)) {
                throw new IllegalArgumentException("approachPolicy is unsupported");
            }
            if (maxApproachTicks < 0 || maxApproachTicks > 1_200) {
                throw new IllegalArgumentException("maxApproachTicks must be 0-1200");
            }
            if (!"none".equals(approachPolicy) != (maxApproachTicks > 0)) {
                throw new IllegalArgumentException("an approach policy requires a positive approach budget");
            }
            if (!Set.of("none", "lateral_bounded").contains(coverPolicy)) {
                throw new IllegalArgumentException("coverPolicy is unsupported");
            }
            if (maxCoverTicks < 0 || maxCoverTicks > 1_200) {
                throw new IllegalArgumentException("maxCoverTicks must be 0-1200");
            }
            if ("lateral_bounded".equals(coverPolicy) != (maxCoverTicks > 0)) {
                throw new IllegalArgumentException("lateral_bounded requires a positive cover budget");
            }
            if (!Set.of("none", "sidestep", "shield_or_sidestep").contains(projectileResponse)) {
                throw new IllegalArgumentException("projectileResponse is unsupported");
            }
            if (projectileEvasionHorizonTicks < 1 || projectileEvasionHorizonTicks > 20) {
                throw new IllegalArgumentException("projectileEvasionHorizonTicks must be 1-20");
            }
            if (maxEvasionTicks < 0 || maxEvasionTicks > 1_200) {
                throw new IllegalArgumentException("maxEvasionTicks must be 0-1200");
            }
            if (!"none".equals(projectileResponse) && maxEvasionTicks == 0) {
                throw new IllegalArgumentException("projectile response requires an evasion budget");
            }
            if (!Set.of("none", "off_hand").contains(shieldHand)) {
                throw new IllegalArgumentException("shieldHand is unsupported");
            }
            if (maxShieldHoldTicks < 0 || maxShieldHoldTicks > 1_200) {
                throw new IllegalArgumentException("maxShieldHoldTicks must be 0-1200");
            }
            if ("shield_or_sidestep".equals(projectileResponse) &&
                (!"off_hand".equals(shieldHand) || maxShieldHoldTicks == 0)) {
                throw new IllegalArgumentException("shield response requires an admitted off-hand hold budget");
            }
            if (!"shield_or_sidestep".equals(projectileResponse) &&
                (!"none".equals(shieldHand) || maxShieldHoldTicks != 0)) {
                throw new IllegalArgumentException("shield authority is only valid for shield_or_sidestep");
            }
        }
    }

    record Target(
        String targetRef,
        String entityTypeId,
        double x,
        double y,
        double z,
        double distance,
        boolean visible,
        boolean withinAttackRange,
        boolean targetingPlayer,
        double attackCooldown
    ) {
        Target {
            Objects.requireNonNull(targetRef);
            Objects.requireNonNull(entityTypeId);
        }
    }

    record ProjectileThreat(
        String projectileRef,
        int predictedCollisionTick,
        double velocityX,
        double velocityZ,
        double sourceX,
        double sourceY,
        double sourceZ
    ) {
        ProjectileThreat {
            Objects.requireNonNull(projectileRef);
            if (predictedCollisionTick < 1 || predictedCollisionTick > 20) {
                throw new IllegalArgumentException("predictedCollisionTick must be 1-20");
            }
        }
    }

    interface Runtime {
        double playerHealth();
        List<Target> eligibleTargets(Profile profile);
        List<ProjectileThreat> collisionThreats(Profile profile);
        void track(Target target);
        void orientAway(Target target);
        boolean move(MovementMode mode, ProjectileThreat threat, Target target);
        boolean shield(boolean active, String hand);
        boolean attack(String targetRef);
        void release();

        default Map<String, Object> movementDiagnostics() {
            return Map.of();
        }
    }

    private final Runtime runtime;
    private Profile profile;
    private String selectedTargetRef;
    private long selectedAtTick;
    private int targetSwitches;
    private int attackPulses;
    private int retreatTicks;
    private int approachTicks;
    private int approachRerouteTicks;
    private int coverTicks;
    private int evasionTicks;
    private int shieldTicks;
    private int peakHostileCount;
    private int peakProjectileThreatCount;
    private boolean retreating;
    private boolean approaching;
    private boolean covering;
    private boolean evading;
    private boolean shieldActive;
    private boolean sawHostile;
    private boolean sawProjectileThreat;

    MinecraftCombatGuardian(Runtime runtime) {
        this.runtime = Objects.requireNonNull(runtime);
    }

    void begin(Profile nextProfile) {
        profile = Objects.requireNonNull(nextProfile);
        selectedTargetRef = null;
        selectedAtTick = 0;
        targetSwitches = 0;
        attackPulses = 0;
        retreatTicks = 0;
        approachTicks = 0;
        approachRerouteTicks = 0;
        coverTicks = 0;
        evasionTicks = 0;
        shieldTicks = 0;
        peakHostileCount = 0;
        peakProjectileThreatCount = 0;
        retreating = false;
        approaching = false;
        covering = false;
        evading = false;
        shieldActive = false;
        sawHostile = false;
        sawProjectileThreat = false;
        runtime.release();
    }

    WorkflowStep step(long actionTicks) {
        if (profile == null) {
            return WorkflowStep.failed(
                "The resident combat guardian was not started.",
                Map.of(
                    "reason_code", "combat_guard_not_started",
                    "controls_released", true
                )
            );
        }
        List<Target> targets = runtime.eligibleTargets(profile).stream()
            .filter(target -> profile.hostileEntityTypeIds().contains(target.entityTypeId()))
            .filter(Target::visible)
            .sorted(targetComparator())
            .toList();
        peakHostileCount = Math.max(peakHostileCount, targets.size());
        sawHostile |= !targets.isEmpty();
        List<ProjectileThreat> projectileThreats = runtime.collisionThreats(profile).stream()
            .filter(threat -> threat.predictedCollisionTick() <= profile.projectileEvasionHorizonTicks())
            .sorted(Comparator
                .comparingInt(ProjectileThreat::predictedCollisionTick)
                .thenComparing(ProjectileThreat::projectileRef))
            .toList();
        peakProjectileThreatCount = Math.max(peakProjectileThreatCount, projectileThreats.size());
        sawProjectileThreat |= !projectileThreats.isEmpty();

        if (runtime.playerHealth() <= profile.stopBelowHealth()) {
            runtime.release();
            return failed(
                "player_health_floor_reached",
                "The resident combat guardian stopped because measured player health crossed the admitted floor.",
                targets,
                projectileThreats
            );
        }
        if (targets.isEmpty() && projectileThreats.isEmpty()) {
            runtime.release();
            return WorkflowStep.succeeded(
                sawHostile
                    ? "No admitted hostile remains in the bounded combat envelope."
                    : "No admitted hostile was present in the bounded combat envelope.",
                measurements("hostiles_cleared", targets, projectileThreats)
            );
        }

        double minimumHostileDistance = targets.stream()
            .mapToDouble(Target::distance)
            .min()
            .orElse(Double.POSITIVE_INFINITY);
        if (
            "disengage_to_distance".equals(profile.combatMode()) &&
            minimumHostileDistance >= profile.retreatStopDistance()
        ) {
            runtime.release();
            return WorkflowStep.succeeded(
                "Every visible admitted hostile is outside the requested recovery separation envelope.",
                measurements("safe_separation_reached", targets, projectileThreats)
            );
        }

        ProjectileThreat imminentThreat = projectileThreats.isEmpty()
            ? null
            : projectileThreats.get(0);
        Target defensiveTarget = targets.isEmpty() ? null : targets.get(0);
        shieldActive = false;
        covering = false;
        evading = false;
        if (imminentThreat != null && !"none".equals(profile.projectileResponse())) {
            if ("lateral_bounded".equals(profile.coverPolicy()) &&
                coverTicks < profile.maxCoverTicks()) {
                MovementMode preferredCover = ((imminentThreat.projectileRef().hashCode() & 1) == 0)
                    ? MovementMode.COVER_LEFT
                    : MovementMode.COVER_RIGHT;
                covering = runtime.move(preferredCover, imminentThreat, defensiveTarget);
                if (!covering) {
                    MovementMode alternateCover = preferredCover == MovementMode.COVER_LEFT
                        ? MovementMode.COVER_RIGHT
                        : MovementMode.COVER_LEFT;
                    covering = runtime.move(alternateCover, imminentThreat, defensiveTarget);
                }
                if (covering) coverTicks++;
            }
            if (covering) runtime.shield(false, profile.shieldHand());
            if (!covering && "shield_or_sidestep".equals(profile.projectileResponse()) &&
                shieldTicks < profile.maxShieldHoldTicks()) {
                shieldActive = runtime.shield(true, profile.shieldHand());
                if (shieldActive) shieldTicks++;
            }
            if (!covering && !shieldActive) {
                runtime.shield(false, profile.shieldHand());
                if (evasionTicks >= profile.maxEvasionTicks()) {
                    runtime.release();
                    return failed(
                        "projectile_evasion_budget_exhausted",
                        "The resident combat guardian exhausted its admitted projectile-evasion budget.",
                        targets,
                        projectileThreats
                    );
                }
                MovementMode preferred = ((imminentThreat.projectileRef().hashCode() & 1) == 0)
                    ? MovementMode.EVADE_LEFT
                    : MovementMode.EVADE_RIGHT;
                evading = runtime.move(preferred, imminentThreat, defensiveTarget);
                if (!evading) {
                    MovementMode alternate = preferred == MovementMode.EVADE_LEFT
                        ? MovementMode.EVADE_RIGHT
                        : MovementMode.EVADE_LEFT;
                    evading = runtime.move(alternate, imminentThreat, defensiveTarget);
                }
                if (!evading) {
                    runtime.release();
                    return failed(
                        "projectile_evasion_path_unavailable",
                        "No collision-checked sidestep was available for the admitted projectile threat.",
                        targets,
                        projectileThreats
                    );
                }
                evasionTicks++;
            }
        } else {
            runtime.shield(false, profile.shieldHand());
        }

        if (targets.isEmpty()) {
            approaching = false;
            retreating = false;
            if (!covering && !evading) runtime.move(MovementMode.HOLD, imminentThreat, null);
            return WorkflowStep.running(
                null,
                "The resident combat guardian is holding defense until the admitted projectile threat clears.",
                measurements("projectile_defense_active", targets, projectileThreats)
            );
        }

        Target current = targets.stream()
            .filter(target -> target.targetRef().equals(selectedTargetRef))
            .findFirst()
            .orElse(null);
        Target preferred = "disengage_to_distance".equals(profile.combatMode())
            ? targets.stream()
                .min(Comparator.comparingDouble(Target::distance).thenComparing(Target::targetRef))
                .orElse(targets.get(0))
            : targets.get(0);
        boolean commitmentActive = current != null &&
            actionTicks - selectedAtTick < profile.targetCommitTicks();
        Target selected = commitmentActive ? current : preferred;
        if (current != null && !commitmentActive && !preferred.targetRef().equals(current.targetRef())) {
            // Keep a viable target unless the replacement is actively attacking
            // the player or is materially closer. This is the anti-oscillation
            // boundary for multi-hostile encounters.
            if (current.targetingPlayer() || (
                !preferred.targetingPlayer() &&
                preferred.distance() + 1.25 >= current.distance()
            )) {
                selected = current;
            }
        }
        if (!selected.targetRef().equals(selectedTargetRef)) {
            if (selectedTargetRef != null) {
                if (targetSwitches >= profile.maxTargetSwitches()) {
                    runtime.release();
                    return failed(
                        "target_switch_budget_exhausted",
                        "The resident combat guardian exhausted its admitted target-switch budget.",
                        targets,
                        projectileThreats
                    );
                }
                targetSwitches++;
            }
            selectedTargetRef = selected.targetRef();
            selectedAtTick = actionTicks;
        }

        if ("disengage_to_distance".equals(profile.combatMode())) {
            runtime.orientAway(selected);
        } else {
            runtime.track(selected);
        }
        boolean retreatEligible =
            "disengage_to_distance".equals(profile.combatMode()) ||
            targets.size() >= profile.retreatWhenHostileCountAtLeast();
        if (retreating) {
            retreating = retreatEligible && selected.distance() < profile.retreatStopDistance();
        } else {
            retreating = retreatEligible && selected.distance() <= profile.retreatStartDistance();
        }
        approaching = false;
        if (!covering && !evading) {
            if (retreating) {
                boolean moved = runtime.move(MovementMode.RETREAT, imminentThreat, selected);
                if (!moved && "disengage_to_distance".equals(profile.combatMode())) {
                    MovementMode preferredFlank = ((selected.targetRef().hashCode() & 1) == 0)
                        ? MovementMode.FLANK_LEFT
                        : MovementMode.FLANK_RIGHT;
                    moved = runtime.move(preferredFlank, imminentThreat, selected);
                    if (!moved) {
                        MovementMode alternateFlank = preferredFlank == MovementMode.FLANK_LEFT
                            ? MovementMode.FLANK_RIGHT
                            : MovementMode.FLANK_LEFT;
                        moved = runtime.move(alternateFlank, imminentThreat, selected);
                    }
                }
                if (moved) retreatTicks++;
            } else if (!selected.withinAttackRange() &&
                !"none".equals(profile.approachPolicy())) {
                if (approachTicks >= profile.maxApproachTicks()) {
                    runtime.release();
                    return failed(
                        "approach_tick_budget_exhausted",
                        "The resident combat guardian exhausted its admitted closing-distance budget.",
                        targets,
                        projectileThreats
                    );
                }
                approaching = runtime.move(MovementMode.APPROACH, imminentThreat, selected);
                if (!approaching && "local_reroute_bounded".equals(profile.approachPolicy())) {
                    MovementMode preferredFlank = ((selected.targetRef().hashCode() & 1) == 0)
                        ? MovementMode.FLANK_LEFT
                        : MovementMode.FLANK_RIGHT;
                    approaching = runtime.move(preferredFlank, imminentThreat, selected);
                    if (!approaching) {
                        MovementMode alternateFlank = preferredFlank == MovementMode.FLANK_LEFT
                            ? MovementMode.FLANK_RIGHT
                            : MovementMode.FLANK_LEFT;
                        approaching = runtime.move(alternateFlank, imminentThreat, selected);
                    }
                    if (approaching) approachRerouteTicks++;
                }
                if (!approaching) {
                    runtime.release();
                    return failed(
                        "approach_path_unavailable",
                        "No collision-checked direct closing step was available.",
                        targets,
                        projectileThreats
                    );
                }
                approachTicks++;
            } else {
                runtime.move(MovementMode.HOLD, imminentThreat, selected);
            }
        }

        if ("engage".equals(profile.combatMode()) &&
            !shieldActive && selected.withinAttackRange() &&
            selected.attackCooldown() >= profile.minimumAttackCooldown()) {
            if (attackPulses >= profile.maxAttackPulses()) {
                runtime.release();
                return failed(
                    "attack_pulse_budget_exhausted",
                    "The resident combat guardian exhausted its admitted vanilla attack budget.",
                    targets,
                    projectileThreats
                );
            }
            if (!runtime.attack(selected.targetRef())) {
                runtime.release();
                return failed(
                    "vanilla_attack_rejected",
                    "The vanilla client rejected the selected hostile attack; no substitute target was attacked.",
                    targets,
                    projectileThreats
                );
            }
            attackPulses++;
        }

        return WorkflowStep.running(
            null,
            "The resident combat guardian is tracking an admitted hostile and applying bounded tick-speed controls.",
            measurements("engaged", targets, projectileThreats)
        );
    }

    void cancel() {
        runtime.release();
        profile = null;
    }

    private Comparator<Target> targetComparator() {
        return Comparator
            .comparing(Target::targetingPlayer).reversed()
            .thenComparingDouble(Target::distance)
            .thenComparing(Target::targetRef);
    }

    private WorkflowStep failed(
        String reason,
        String summary,
        List<Target> targets,
        List<ProjectileThreat> projectileThreats
    ) {
        Map<String, Object> measured = new LinkedHashMap<>(
            measurements(reason, targets, projectileThreats)
        );
        measured.put("controls_released", true);
        return WorkflowStep.failed(summary, measured);
    }

    private Map<String, Object> measurements(
        String reason,
        List<Target> targets,
        List<ProjectileThreat> projectileThreats
    ) {
        Map<String, Object> measured = new LinkedHashMap<>();
        measured.put("profile_id", PROFILE_ID);
        measured.put("artifact_version", ARTIFACT_VERSION);
        measured.put("combat_mode", profile.combatMode());
        measured.put("reason_code", reason);
        measured.put("eligible_hostile_count", targets.size());
        measured.put("minimum_hostile_distance", targets.stream()
            .mapToDouble(Target::distance)
            .min()
            .orElse(-1));
        measured.put(
            "safe_separation_reached",
            !targets.isEmpty() && targets.stream().allMatch(target ->
                target.distance() >= profile.retreatStopDistance()
            )
        );
        measured.put("peak_hostile_count", peakHostileCount);
        measured.put("selected_target_ref", selectedTargetRef == null ? "" : selectedTargetRef);
        targets.stream()
            .filter(target -> target.targetRef().equals(selectedTargetRef))
            .findFirst()
            .ifPresent(target -> {
                measured.put("selected_target_distance", target.distance());
                measured.put("selected_target_within_attack_range", target.withinAttackRange());
                measured.put("selected_target_visible", target.visible());
            });
        measured.put("target_switches", targetSwitches);
        measured.put("attack_pulses", attackPulses);
        measured.put("retreat_ticks", retreatTicks);
        measured.put("retreating", retreating);
        measured.put("approach_policy", profile.approachPolicy());
        measured.put("approach_ticks", approachTicks);
        measured.put("approach_reroute_ticks", approachRerouteTicks);
        measured.put("approaching", approaching);
        measured.put("movement_target_frame", "selected_hostile_position");
        measured.putAll(runtime.movementDiagnostics());
        measured.put("cover_policy", profile.coverPolicy());
        measured.put("cover_ticks", coverTicks);
        measured.put("covering", covering);
        measured.put("projectile_response", profile.projectileResponse());
        measured.put("projectile_threat_count", projectileThreats.size());
        measured.put("peak_projectile_threat_count", peakProjectileThreatCount);
        measured.put("closest_collision_tick", projectileThreats.isEmpty()
            ? -1
            : projectileThreats.get(0).predictedCollisionTick());
        measured.put("evasion_ticks", evasionTicks);
        measured.put("evading", evading);
        measured.put("shield_ticks", shieldTicks);
        measured.put("shield_active", shieldActive);
        measured.put("shield_hand", profile.shieldHand());
        measured.put("player_health", runtime.playerHealth());
        measured.put("friendly_fire", false);
        measured.put("target_classification", "hostile");
        measured.put("world_mutations_performed", 0);
        measured.put("inventory_mutations_performed", 0);
        return Map.copyOf(measured);
    }
}
