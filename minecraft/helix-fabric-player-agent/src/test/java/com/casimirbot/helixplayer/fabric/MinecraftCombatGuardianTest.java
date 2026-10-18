package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class MinecraftCombatGuardianTest {
    @Test
    void prioritizesAnAttackingHostileAndAppliesCooldownGatedVanillaAttack() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.targets = List.of(
            target("near", 1.5, false, true, 1.0),
            target("threat", 2.5, true, true, 1.0)
        );
        MinecraftCombatGuardian guardian = new MinecraftCombatGuardian(runtime);
        guardian.begin(profile(2, 8));

        PlayerActionWorkflow.WorkflowStep step = guardian.step(1);

        assertEquals(PlayerActionWorkflow.WorkflowStepStatus.RUNNING, step.status());
        assertEquals("threat", runtime.trackedRefs.get(0));
        assertEquals(List.of("threat"), runtime.attackedRefs);
        assertTrue(runtime.retreating);
        assertEquals(false, step.measurements().get("friendly_fire"));
    }

    @Test
    void commitmentPreventsTargetOscillationUntilItsTickBudgetExpires() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.targets = List.of(target("first", 2.0, false, true, 0.2));
        MinecraftCombatGuardian guardian = new MinecraftCombatGuardian(runtime);
        guardian.begin(profile(1, 3));
        guardian.step(1);

        runtime.targets = List.of(
            target("first", 2.0, false, true, 0.2),
            target("second", 1.0, true, true, 0.2)
        );
        guardian.step(2);
        guardian.step(3);
        guardian.step(4);

        assertEquals(List.of("first", "first", "first", "second"), runtime.trackedRefs);
    }

    @Test
    void stopsAndReleasesAtTheAdmittedHealthFloor() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.health = 4;
        runtime.targets = List.of(target("zombie", 2, true, true, 1));
        MinecraftCombatGuardian guardian = new MinecraftCombatGuardian(runtime);
        guardian.begin(profile(1, 4));

        PlayerActionWorkflow.WorkflowStep step = guardian.step(1);

        assertEquals(PlayerActionWorkflow.WorkflowStepStatus.FAILED, step.status());
        assertEquals("player_health_floor_reached", step.measurements().get("reason_code"));
        assertTrue(runtime.releaseCount >= 2);
        assertTrue(runtime.attackedRefs.isEmpty());
    }

    @Test
    void refusesToSwitchBeyondTheExplicitBudget() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.targets = List.of(target("first", 2, false, true, 0.2));
        MinecraftCombatGuardian guardian = new MinecraftCombatGuardian(runtime);
        guardian.begin(profile(1, 0, 0));
        guardian.step(1);
        runtime.targets = List.of(target("second", 2, false, true, 0.2));

        PlayerActionWorkflow.WorkflowStep step = guardian.step(2);

        assertEquals(PlayerActionWorkflow.WorkflowStepStatus.FAILED, step.status());
        assertEquals("target_switch_budget_exhausted", step.measurements().get("reason_code"));
        assertTrue(runtime.attackedRefs.isEmpty());
    }

    @Test
    void closesDistanceOnlyInsideTheAdmittedApproachBudget() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.targets = List.of(targetOfType("skeleton", "minecraft:skeleton", 8, true, false, 1));
        MinecraftCombatGuardian guardian = new MinecraftCombatGuardian(runtime);
        guardian.begin(rangedProfile(
            "direct_bounded", 2, "none", 0, "none", 0, "none", 0
        ));

        PlayerActionWorkflow.WorkflowStep first = guardian.step(1);
        PlayerActionWorkflow.WorkflowStep second = guardian.step(2);
        PlayerActionWorkflow.WorkflowStep third = guardian.step(3);

        assertEquals(PlayerActionWorkflow.WorkflowStepStatus.RUNNING, first.status());
        assertEquals(PlayerActionWorkflow.WorkflowStepStatus.RUNNING, second.status());
        assertEquals(PlayerActionWorkflow.WorkflowStepStatus.FAILED, third.status());
        assertEquals("approach_tick_budget_exhausted", third.measurements().get("reason_code"));
        assertEquals(List.of(
            MinecraftCombatGuardian.MovementMode.APPROACH,
            MinecraftCombatGuardian.MovementMode.APPROACH
        ), runtime.movementModes);
    }

    @Test
    void usesAnExplicitBoundedLateralRerouteWhenDirectClosingIsBlocked() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.blockedModes = Set.of(MinecraftCombatGuardian.MovementMode.APPROACH);
        runtime.targets = List.of(targetOfType("skeleton", "minecraft:skeleton", 8, true, false, 1));
        MinecraftCombatGuardian guardian = new MinecraftCombatGuardian(runtime);
        guardian.begin(rangedProfile(
            "local_reroute_bounded", 20, "none", 0, "none", 0, "none", 0
        ));

        PlayerActionWorkflow.WorkflowStep step = guardian.step(1);

        assertEquals(PlayerActionWorkflow.WorkflowStepStatus.RUNNING, step.status());
        assertEquals(MinecraftCombatGuardian.MovementMode.APPROACH, runtime.movementModes.get(0));
        assertTrue(runtime.movementModes.get(1) == MinecraftCombatGuardian.MovementMode.FLANK_LEFT ||
            runtime.movementModes.get(1) == MinecraftCombatGuardian.MovementMode.FLANK_RIGHT);
        assertEquals(1, step.measurements().get("approach_reroute_ticks"));
        assertEquals(1, step.measurements().get("approach_ticks"));
    }

    @Test
    void sidestepsAnImminentProjectileBeforeClosing() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.targets = List.of(targetOfType("skeleton", "minecraft:skeleton", 8, true, false, 1));
        runtime.threats = List.of(new MinecraftCombatGuardian.ProjectileThreat(
            "arrow", 3, 1, 0, 8, 65, 0
        ));
        MinecraftCombatGuardian guardian = new MinecraftCombatGuardian(runtime);
        guardian.begin(rangedProfile(
            "direct_bounded", 20, "none", 0, "sidestep", 4, "none", 0
        ));

        PlayerActionWorkflow.WorkflowStep step = guardian.step(1);

        assertEquals(PlayerActionWorkflow.WorkflowStepStatus.RUNNING, step.status());
        assertTrue(runtime.movementModes.get(0) == MinecraftCombatGuardian.MovementMode.EVADE_LEFT ||
            runtime.movementModes.get(0) == MinecraftCombatGuardian.MovementMode.EVADE_RIGHT);
        assertEquals(1, step.measurements().get("evasion_ticks"));
        assertEquals(0, step.measurements().get("approach_ticks"));
    }

    @Test
    void raisesAnAdmittedOffhandShieldAndContinuesClosing() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.shieldAvailable = true;
        runtime.targets = List.of(targetOfType("skeleton", "minecraft:skeleton", 8, true, false, 1));
        runtime.threats = List.of(new MinecraftCombatGuardian.ProjectileThreat(
            "arrow", 3, 1, 0, 8, 65, 0
        ));
        MinecraftCombatGuardian guardian = new MinecraftCombatGuardian(runtime);
        guardian.begin(rangedProfile(
            "direct_bounded", 20, "none", 0,
            "shield_or_sidestep", 20, "off_hand", 20
        ));

        PlayerActionWorkflow.WorkflowStep step = guardian.step(1);

        assertEquals(PlayerActionWorkflow.WorkflowStepStatus.RUNNING, step.status());
        assertTrue(runtime.shielding);
        assertEquals(List.of(MinecraftCombatGuardian.MovementMode.APPROACH), runtime.movementModes);
        assertEquals(1, step.measurements().get("shield_ticks"));
        assertTrue(runtime.attackedRefs.isEmpty());
    }

    @Test
    void prefersAnAdmittedCoverCorridorBeforeShieldOrSidestep() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.coverAvailable = true;
        runtime.shieldAvailable = true;
        runtime.targets = List.of(targetOfType("skeleton", "minecraft:skeleton", 8, true, false, 1));
        runtime.threats = List.of(new MinecraftCombatGuardian.ProjectileThreat(
            "arrow", 3, 1, 0, 8, 65, 0
        ));
        MinecraftCombatGuardian guardian = new MinecraftCombatGuardian(runtime);
        guardian.begin(rangedProfile(
            "direct_bounded", 20, "lateral_bounded", 20,
            "shield_or_sidestep", 20, "off_hand", 20
        ));

        PlayerActionWorkflow.WorkflowStep step = guardian.step(1);

        assertEquals(PlayerActionWorkflow.WorkflowStepStatus.RUNNING, step.status());
        assertTrue(runtime.movementModes.get(0) == MinecraftCombatGuardian.MovementMode.COVER_LEFT ||
            runtime.movementModes.get(0) == MinecraftCombatGuardian.MovementMode.COVER_RIGHT);
        assertEquals(1, step.measurements().get("cover_ticks"));
        assertEquals(0, step.measurements().get("evasion_ticks"));
        assertFalse(runtime.shielding);
    }

    @Test
    void disengagesWithoutAttackingUntilEveryVisibleHostileIsOutsideTheEnvelope() {
        FakeRuntime runtime = new FakeRuntime();
        runtime.targets = List.of(
            target("nearest", 2.0, true, true, 1.0),
            target("farther", 3.5, false, true, 1.0)
        );
        MinecraftCombatGuardian guardian = new MinecraftCombatGuardian(runtime);
        guardian.begin(disengageProfile());

        PlayerActionWorkflow.WorkflowStep retreating = guardian.step(1);
        assertEquals(PlayerActionWorkflow.WorkflowStepStatus.RUNNING, retreating.status());
        assertEquals(List.of(MinecraftCombatGuardian.MovementMode.RETREAT), runtime.movementModes);
        assertEquals(List.of("nearest"), runtime.orientedAwayRefs);
        assertTrue(runtime.trackedRefs.isEmpty());
        assertTrue(runtime.attackedRefs.isEmpty());

        runtime.targets = List.of(
            target("nearest", 5.0, true, false, 1.0),
            target("farther", 6.0, false, false, 1.0)
        );
        PlayerActionWorkflow.WorkflowStep separated = guardian.step(2);

        assertEquals(PlayerActionWorkflow.WorkflowStepStatus.SUCCEEDED, separated.status());
        assertEquals("safe_separation_reached", separated.measurements().get("reason_code"));
        assertEquals(true, separated.measurements().get("safe_separation_reached"));
        assertTrue(runtime.attackedRefs.isEmpty());
        assertTrue(runtime.releaseCount >= 2);
    }

    private static MinecraftCombatGuardian.Profile profile(
        int retreatThreshold,
        int commitTicks
    ) {
        return profile(retreatThreshold, commitTicks, 8);
    }

    private static MinecraftCombatGuardian.Profile profile(
        int retreatThreshold,
        int commitTicks,
        int maxTargetSwitches
    ) {
        return new MinecraftCombatGuardian.Profile(
            Set.of("minecraft:zombie"),
            "engage",
            16,
            0.9,
            32,
            maxTargetSwitches,
            commitTicks,
            2.75,
            4,
            retreatThreshold,
            4,
            "none",
            0,
            "none",
            0,
            "none",
            8,
            0,
            "none",
            0
        );
    }

    private static MinecraftCombatGuardian.Profile disengageProfile() {
        return new MinecraftCombatGuardian.Profile(
            Set.of("minecraft:zombie"),
            "disengage_to_distance",
            16,
            0.9,
            1,
            8,
            0,
            2.75,
            4.5,
            1,
            4,
            "none",
            0,
            "none",
            0,
            "none",
            8,
            0,
            "none",
            0
        );
    }

    private static MinecraftCombatGuardian.Profile rangedProfile(
        String approachPolicy,
        int maxApproachTicks,
        String coverPolicy,
        int maxCoverTicks,
        String projectileResponse,
        int maxEvasionTicks,
        String shieldHand,
        int maxShieldHoldTicks
    ) {
        return new MinecraftCombatGuardian.Profile(
            Set.of("minecraft:skeleton"),
            "engage",
            16,
            0.9,
            32,
            8,
            10,
            2.75,
            4,
            1,
            4,
            approachPolicy,
            maxApproachTicks,
            coverPolicy,
            maxCoverTicks,
            projectileResponse,
            8,
            maxEvasionTicks,
            shieldHand,
            maxShieldHoldTicks
        );
    }

    private static MinecraftCombatGuardian.Target target(
        String ref,
        double distance,
        boolean targetingPlayer,
        boolean withinRange,
        double cooldown
    ) {
        return targetOfType(
            ref, "minecraft:zombie", distance, targetingPlayer, withinRange, cooldown
        );
    }

    private static MinecraftCombatGuardian.Target targetOfType(
        String ref,
        String entityTypeId,
        double distance,
        boolean targetingPlayer,
        boolean withinRange,
        double cooldown
    ) {
        return new MinecraftCombatGuardian.Target(
            ref,
            entityTypeId,
            distance,
            64,
            0,
            distance,
            true,
            withinRange,
            targetingPlayer,
            cooldown
        );
    }

    private static final class FakeRuntime implements MinecraftCombatGuardian.Runtime {
        double health = 20;
        List<MinecraftCombatGuardian.Target> targets = List.of();
        List<MinecraftCombatGuardian.ProjectileThreat> threats = List.of();
        final List<String> trackedRefs = new ArrayList<>();
        final List<String> orientedAwayRefs = new ArrayList<>();
        final List<String> attackedRefs = new ArrayList<>();
        final List<MinecraftCombatGuardian.MovementMode> movementModes = new ArrayList<>();
        boolean retreating;
        boolean shielding;
        boolean shieldAvailable;
        boolean coverAvailable;
        Set<MinecraftCombatGuardian.MovementMode> blockedModes = Set.of();
        int releaseCount;

        @Override
        public double playerHealth() {
            return health;
        }

        @Override
        public List<MinecraftCombatGuardian.Target> eligibleTargets(
            MinecraftCombatGuardian.Profile profile
        ) {
            return targets;
        }

        @Override
        public List<MinecraftCombatGuardian.ProjectileThreat> collisionThreats(
            MinecraftCombatGuardian.Profile profile
        ) {
            return threats;
        }

        @Override
        public void track(MinecraftCombatGuardian.Target target) {
            trackedRefs.add(target.targetRef());
        }

        @Override
        public void orientAway(MinecraftCombatGuardian.Target target) {
            orientedAwayRefs.add(target.targetRef());
        }

        @Override
        public boolean move(
            MinecraftCombatGuardian.MovementMode mode,
            MinecraftCombatGuardian.ProjectileThreat threat,
            MinecraftCombatGuardian.Target target
        ) {
            if (mode != MinecraftCombatGuardian.MovementMode.HOLD) {
                movementModes.add(mode);
            }
            retreating = mode == MinecraftCombatGuardian.MovementMode.RETREAT;
            if (blockedModes.contains(mode)) return false;
            if (mode == MinecraftCombatGuardian.MovementMode.COVER_LEFT ||
                mode == MinecraftCombatGuardian.MovementMode.COVER_RIGHT) {
                return coverAvailable;
            }
            return true;
        }

        @Override
        public boolean shield(boolean active, String hand) {
            shielding = active && shieldAvailable && "off_hand".equals(hand);
            return shielding;
        }

        @Override
        public boolean attack(String targetRef) {
            attackedRefs.add(targetRef);
            return true;
        }

        @Override
        public void release() {
            retreating = false;
            shielding = false;
            releaseCount++;
        }
    }
}
