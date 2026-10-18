package com.casimirbot.helixcompanion.spike.gametest;

import com.casimirbot.helixcompanion.spike.CompanionFollowController;
import com.casimirbot.helixcompanion.spike.CompanionPresenceRuntime;
import com.casimirbot.helixcompanion.spike.HelixCompanionSpikeMod;
import com.casimirbot.helixcompanion.spike.SpikeCompanionEntity;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public final class CompanionFollowGameTests {
    private static final String PROFILE_HASH =
        "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

    @GameTest(maxTicks = 320, skyAccess = false)
    public void c1A0FollowHysteresisHoldLookAndCodexDelay(GameTestHelper helper) {
        prepareFloor(helper, 18, 5);
        long startTick = helper.getLevel().getGameTime();
        SpikeCompanionEntity actor = spawn(helper, 1, 2);
        SpikeCompanionEntity target = spawn(helper, 11, 2);
        CompanionPresenceRuntime presence = presence(
            helper,
            actor,
            "incarnation:c1-a0:follow",
            startTick + 300
        );
        CompanionFollowController controller = new CompanionFollowController(
            presence,
            CompanionFollowController.C1_PROFILE
        );
        controller.admit(
            CompanionFollowController.Command.follow(
                "follow-hysteresis",
                target,
                startTick,
                startTick + 200
            ),
            startTick
        );

        tickUntil(helper, controller, startTick + 1, 100, snapshot ->
            actor.distanceTo(target) <= 3.25D
                && snapshot.navigationStarts() > 0
                && snapshot.state() == CompanionFollowController.State.HOLDING,
            reached -> {
                helper.assertTrue(
                    reached.navigationStarts() > 0
                        && reached.navigationStops() > 0
                        && reached.state() == CompanionFollowController.State.HOLDING,
                    Component.literal("C1 follow must use and settle native navigation.")
                );
                int starts = reached.navigationStarts();
                int stops = reached.navigationStops();
                target.setPos(actor.getX() + 4.5D, actor.getY(), actor.getZ());
                tickFor(helper, controller, reached.observedAtTick() + 1, 10, heldBand -> {
                    helper.assertTrue(
                        heldBand.navigationStarts() == starts
                            && heldBand.navigationStops() == stops
                            && heldBand.state() == CompanionFollowController.State.HOLDING,
                        Component.literal("The 3-6 block hysteresis band must not chatter.")
                    );
                    assertFollowCode(helper, "companion_controller_busy", () ->
                        controller.admit(
                            CompanionFollowController.Command.mode(
                                "overlapping-hold",
                                CompanionFollowController.Mode.HOLD,
                                heldBand.observedAtTick(),
                                heldBand.observedAtTick() + 40
                            ),
                            heldBand.observedAtTick()
                        )
                    );
                    CompanionFollowController.Snapshot released = controller.manualRelease(
                        "manual_override",
                        heldBand.observedAtTick()
                    );
                    helper.assertTrue(
                        released.controlsReleased() && released.taskReleased(),
                        Component.literal("Manual release must clear controls and the task.")
                    );

                    CompanionFollowController hold = new CompanionFollowController(
                        presence,
                        CompanionFollowController.C1_PROFILE
                    );
                    hold.admit(
                        CompanionFollowController.Command.mode(
                            "explicit-hold",
                            CompanionFollowController.Mode.HOLD,
                            heldBand.observedAtTick(),
                            heldBand.observedAtTick() + 40
                        ),
                        heldBand.observedAtTick()
                    );
                    CompanionFollowController.Snapshot holdSnapshot = hold.tick(
                        heldBand.observedAtTick() + 1
                    );
                    helper.assertTrue(
                        holdSnapshot.state() == CompanionFollowController.State.HOLDING
                            && holdSnapshot.controlsReleased(),
                        Component.literal("Hold must retain presence while releasing movement.")
                    );
                    hold.manualRelease("manual_override", heldBand.observedAtTick() + 1);

                    CompanionFollowController look = new CompanionFollowController(
                        presence,
                        CompanionFollowController.C1_PROFILE
                    );
                    long lookTick = heldBand.observedAtTick() + 2;
                    look.admit(
                        new CompanionFollowController.Command(
                            "look-target",
                            CompanionFollowController.Mode.LOOK_AT,
                            target,
                            null,
                            null,
                            lookTick,
                            lookTick,
                            lookTick + 40
                        ),
                        lookTick
                    );
                    tickFor(helper, look, lookTick + 1, 5, looked -> {
                        Vec3 expected = target.getEyePosition()
                            .subtract(actor.getEyePosition())
                            .normalize();
                        double alignment = actor.getViewVector(1.0F).dot(expected);
                        helper.assertTrue(
                            looked.state() == CompanionFollowController.State.LOOKING
                                && alignment > 0.45D,
                            Component.literal("Look mode must track the exact admitted target.")
                        );
                        CompanionFollowController.Snapshot emergency = look.manualRelease(
                            "emergency_stop",
                            looked.observedAtTick()
                        );
                        helper.assertTrue(
                            emergency.outcome().equals("emergency_stopped")
                                && emergency.controlsReleased()
                                && emergency.taskReleased(),
                            Component.literal("Emergency Stop must settle and release C1.")
                        );
                        presence.cleanup("cleanup:c1-a0:follow", "manual_override", true);
                        target.discard();
                        helper.succeed();
                    });
                });
            }
        );
    }

    @GameTest(maxTicks = 260, skyAccess = false)
    public void c1A0NearbyWaypointReturnAndBounds(GameTestHelper helper) {
        prepareFloor(helper, 22, 5);
        long startTick = helper.getLevel().getGameTime();
        SpikeCompanionEntity actor = spawn(helper, 1, 2);
        SpikeCompanionEntity owner = spawn(helper, 1, 4);
        CompanionPresenceRuntime presence = presence(
            helper,
            actor,
            "incarnation:c1-a0:waypoint",
            startTick + 240
        );
        CompanionFollowController waypoint = new CompanionFollowController(
            presence,
            CompanionFollowController.C1_PROFILE
        );
        Vec3 nearby = center(helper, 10, 1, 2);
        waypoint.admit(
            new CompanionFollowController.Command(
                "nearby-waypoint",
                CompanionFollowController.Mode.NAVIGATE_NEARBY,
                null,
                nearby,
                null,
                startTick,
                startTick,
                startTick + 120
            ),
            startTick
        );
        tickUntil(helper, waypoint, startTick + 1, 100, snapshot ->
            snapshot.state() == CompanionFollowController.State.RELEASED,
            arrived -> {
                helper.assertTrue(
                    "completed".equals(arrived.outcome())
                        && "nearby_waypoint_reached".equals(arrived.reason())
                        && arrived.controlsReleased()
                        && arrived.taskReleased(),
                    Component.literal("Nearby waypoint must complete with released resources.")
                );

                CompanionFollowController bounds = new CompanionFollowController(
                    presence,
                    CompanionFollowController.C1_PROFILE
                );
                long boundsTick = arrived.observedAtTick() + 1;
                assertFollowCode(helper, "companion_waypoint_out_of_bounds", () ->
                    bounds.admit(
                        new CompanionFollowController.Command(
                            "far-waypoint",
                            CompanionFollowController.Mode.NAVIGATE_NEARBY,
                            null,
                            actor.position().add(17.0D, 0.0D, 0.0D),
                            null,
                            boundsTick,
                            boundsTick,
                            boundsTick + 40
                        ),
                        boundsTick
                    )
                );

                helper.runAfterDelay(1L, () -> {
                    CompanionFollowController returning = new CompanionFollowController(
                        presence,
                        CompanionFollowController.C1_PROFILE
                    );
                    long returnTick = helper.getLevel().getGameTime();
                    helper.assertTrue(
                        owner.isAlive()
                            && !owner.isRemoved()
                            && owner.level() == actor.level()
                            && actor.distanceTo(owner) >= 6.0D,
                        Component.literal(
                            "Return fixture target must be live, same-world and outside start distance; "
                                + "alive=" + owner.isAlive()
                                + " removed=" + owner.isRemoved()
                                + " distance=" + actor.distanceTo(owner)
                        )
                    );
                    returning.admit(
                        new CompanionFollowController.Command(
                            "return-owner",
                            CompanionFollowController.Mode.RETURN_TO_OWNER,
                            owner,
                            null,
                            null,
                            returnTick,
                            returnTick,
                            returnTick + 120
                        ),
                        returnTick
                    );
                    tickUntil(helper, returning, returnTick + 1, 100, snapshot ->
                        (actor.distanceTo(owner) <= 3.25D
                            && snapshot.state() == CompanionFollowController.State.HOLDING)
                            || snapshot.state() == CompanionFollowController.State.SUSPENDED,
                        returned -> {
                            helper.assertTrue(
                                returned.state() == CompanionFollowController.State.HOLDING
                                    && returned.navigationStarts() > 0,
                                Component.literal(
                                    "Return must use the bound owner's native path; state="
                                        + returned.state()
                                        + " reason=" + returned.reason()
                                        + " ownerAlive=" + owner.isAlive()
                                        + " ownerRemoved=" + owner.isRemoved()
                                        + " distance=" + actor.distanceTo(owner)
                                )
                            );
                            returning.manualRelease("manual_override", returned.observedAtTick());
                            presence.cleanup("cleanup:c1-a0:waypoint", "manual_override", true);
                            owner.discard();
                            helper.succeed();
                        }
                    );
                });
            }
        );
    }

    @GameTest(maxTicks = 260, skyAccess = false)
    public void c1A0ObstructionTargetLossAndRelease(GameTestHelper helper) {
        prepareFloor(helper, 18, 7);
        long startTick = helper.getLevel().getGameTime();
        SpikeCompanionEntity actor = spawn(helper, 3, 3);
        encloseActor(helper, 3, 3);
        CompanionPresenceRuntime presence = presence(
            helper,
            actor,
            "incarnation:c1-a0:obstruction",
            startTick + 240
        );
        CompanionFollowController obstructed = new CompanionFollowController(
            presence,
            CompanionFollowController.C1_PROFILE
        );
        obstructed.admit(
            new CompanionFollowController.Command(
                "blocked-follow",
                CompanionFollowController.Mode.NAVIGATE_NEARBY,
                null,
                center(helper, 10, 1, 3),
                null,
                startTick,
                startTick,
                startTick + 120
            ),
            startTick
        );
        tickUntil(helper, obstructed, startTick + 1, 80, snapshot ->
            snapshot.state() == CompanionFollowController.State.SUSPENDED,
            suspended -> {
                helper.assertTrue(
                    "companion_obstruction_replan_required".equals(suspended.reason())
                        && suspended.controlsReleased()
                        && suspended.pathFailures() > 0,
                    Component.literal(
                        "Obstruction must suspend with exact causal evidence; reason="
                            + suspended.reason()
                            + " pathFailures=" + suspended.pathFailures()
                            + " controlsReleased=" + suspended.controlsReleased()
                    )
                );
                obstructed.releaseSuspended(suspended.observedAtTick());
                clearEnclosure(helper, 3, 3);
                SpikeCompanionEntity target = spawn(helper, 7, 3);

                CompanionFollowController lost = new CompanionFollowController(
                    presence,
                    CompanionFollowController.C1_PROFILE
                );
                long lostTick = suspended.observedAtTick() + 1;
                lost.admit(
                    CompanionFollowController.Command.follow(
                        "lost-target",
                        target,
                        lostTick,
                        lostTick + 80
                    ),
                    lostTick
                );
                target.discard();
                CompanionFollowController.Snapshot lostSnapshot = lost.tick(lostTick + 1);
                helper.assertTrue(
                    lostSnapshot.state() == CompanionFollowController.State.SUSPENDED
                        && "companion_target_removed_replan_required".equals(lostSnapshot.reason())
                        && lostSnapshot.controlsReleased(),
                    Component.literal("Target loss must suspend before another movement effect.")
                );
                lost.releaseSuspended(lostTick + 1);

                long semanticReleaseTick = lostTick + 2;
                CompanionFollowController semanticRelease = new CompanionFollowController(
                    presence,
                    CompanionFollowController.C1_PROFILE
                );
                CompanionFollowController.Snapshot released = semanticRelease.admit(
                    CompanionFollowController.Command.mode(
                        "semantic-release",
                        CompanionFollowController.Mode.RELEASE,
                        semanticReleaseTick,
                        semanticReleaseTick + 40
                    ),
                    semanticReleaseTick
                );
                helper.assertTrue(
                    released.state() == CompanionFollowController.State.RELEASED
                        && "completed".equals(released.outcome())
                        && "released_by_semantic_action".equals(released.reason())
                        && released.controlsReleased()
                        && released.taskReleased()
                        && !released.worldAuthorityUsed()
                        && !released.inventoryAuthority()
                        && !released.miningAuthorized()
                        && !released.combatAuthorized()
                        && !released.answerAuthority()
                        && !released.terminalEligible(),
                    Component.literal("Semantic RELEASE must settle immediately without broader authority.")
                );

                long abstainTick = semanticReleaseTick + 1;
                CompanionFollowController abstaining = new CompanionFollowController(
                    presence,
                    CompanionFollowController.C1_PROFILE
                );
                CompanionFollowController.Snapshot abstained = abstaining.admit(
                    new CompanionFollowController.Command(
                        "unsafe-context-abstain",
                        CompanionFollowController.Mode.ABSTAIN,
                        null,
                        null,
                        "unsafe_context",
                        abstainTick,
                        abstainTick,
                        abstainTick + 40
                    ),
                    abstainTick
                );
                helper.assertTrue(
                    abstained.state() == CompanionFollowController.State.RELEASED
                        && "abstained".equals(abstained.outcome())
                        && "unsafe_context".equals(abstained.reason())
                        && abstained.controlsReleased()
                        && abstained.taskReleased()
                        && !abstained.worldAuthorityUsed()
                        && !abstained.inventoryAuthority()
                        && !abstained.miningAuthorized()
                        && !abstained.combatAuthorized()
                        && !abstained.answerAuthority()
                        && !abstained.terminalEligible(),
                    Component.literal("ABSTAIN must fail closed and release every bounded control surface.")
                );
                presence.cleanup("cleanup:c1-a0:obstruction", "manual_override", true);
                helper.succeed();
            }
        );
    }

    @GameTest(maxTicks = 180, skyAccess = false)
    public void c1A0LeaseExpiryAndRestartRejectLateControl(GameTestHelper helper) {
        prepareFloor(helper, 16, 5);
        long startTick = helper.getLevel().getGameTime();
        SpikeCompanionEntity actor = spawn(helper, 1, 2);
        SpikeCompanionEntity target = spawn(helper, 11, 2);
        CompanionPresenceRuntime presence = presence(
            helper,
            actor,
            "incarnation:c1-a0:expiry",
            startTick + 160
        );
        CompanionFollowController expiring = new CompanionFollowController(
            presence,
            CompanionFollowController.C1_PROFILE
        );
        expiring.admit(
            CompanionFollowController.Command.follow(
                "expiring-follow",
                target,
                startTick,
                startTick + 6
            ),
            startTick
        );
        tickFor(helper, expiring, startTick + 1, 6, expired -> {
            helper.assertTrue(
                expired.state() == CompanionFollowController.State.RELEASED
                    && "lease_expired".equals(expired.outcome())
                    && expired.controlsReleased()
                    && expired.taskReleased(),
                Component.literal("The exact action expiry must release every C1 resource.")
            );

            CompanionFollowController restarted = new CompanionFollowController(
                presence,
                CompanionFollowController.C1_PROFILE
            );
            long restartTick = expired.observedAtTick() + 1;
            restarted.admit(
                CompanionFollowController.Command.follow(
                    "restart-stale",
                    target,
                    restartTick,
                    restartTick + 80
                ),
                restartTick
            );
            restarted.tick(restartTick + 1);
            presence.cleanup("cleanup:c1-a0:restart", "restart", true);
            CompanionFollowController.Snapshot stale = restarted.tick(restartTick + 2);
            helper.assertTrue(
                stale.state() == CompanionFollowController.State.RELEASED
                    && "identity_stale".equals(stale.outcome())
                    && stale.controlsReleased()
                    && stale.taskReleased(),
                Component.literal("Restart cleanup must prevent late C1 control or replay.")
            );
            target.discard();
            helper.succeed();
        });
    }

    private static CompanionPresenceRuntime presence(
        GameTestHelper helper,
        SpikeCompanionEntity actor,
        String incarnation,
        long expiresAtTick
    ) {
        CompanionPresenceRuntime runtime = new CompanionPresenceRuntime(
            new CompanionPresenceRuntime.Profile(
                "companion:noble-one",
                "account:owner",
                "subject:owner",
                "player:owner",
                "resident.minecraft.companion-follow.v1",
                PROFILE_HASH
            ),
            actor,
            helper.getLevel(),
            incarnation,
            "connector-epoch:c1-a0:1",
            expiresAtTick
        );
        runtime.admit("actor-lease:" + incarnation, "effect-lease:" + incarnation);
        return runtime;
    }

    private static SpikeCompanionEntity spawn(
        GameTestHelper helper,
        int x,
        int z
    ) {
        return helper.spawn(
            HelixCompanionSpikeMod.SPIKE_COMPANION,
            new BlockPos(x, 1, z)
        );
    }

    private static void prepareFloor(GameTestHelper helper, int xSize, int zSize) {
        for (int x = 0; x <= xSize; x++) {
            for (int z = 0; z <= zSize; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
                helper.setBlock(new BlockPos(x, 2, z), Blocks.AIR);
                helper.setBlock(new BlockPos(x, 3, z), Blocks.AIR);
            }
        }
    }

    private static void encloseActor(GameTestHelper helper, int x, int z) {
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(new BlockPos(x - 1, y, z), Blocks.STONE);
            helper.setBlock(new BlockPos(x + 1, y, z), Blocks.STONE);
            helper.setBlock(new BlockPos(x, y, z - 1), Blocks.STONE);
            helper.setBlock(new BlockPos(x, y, z + 1), Blocks.STONE);
        }
        helper.setBlock(new BlockPos(x, 3, z), Blocks.STONE);
    }

    private static void clearEnclosure(GameTestHelper helper, int x, int z) {
        for (int y = 1; y <= 3; y++) {
            helper.setBlock(new BlockPos(x - 1, y, z), Blocks.AIR);
            helper.setBlock(new BlockPos(x + 1, y, z), Blocks.AIR);
            helper.setBlock(new BlockPos(x, y, z - 1), Blocks.AIR);
            helper.setBlock(new BlockPos(x, y, z + 1), Blocks.AIR);
        }
        helper.setBlock(new BlockPos(x, 3, z), Blocks.AIR);
    }

    private static Vec3 center(GameTestHelper helper, int x, int y, int z) {
        BlockPos absolute = helper.absolutePos(new BlockPos(x, y, z));
        return Vec3.atBottomCenterOf(absolute);
    }

    private static void tickFor(
        GameTestHelper helper,
        CompanionFollowController controller,
        long tick,
        int remaining,
        java.util.function.Consumer<CompanionFollowController.Snapshot> done
    ) {
        CompanionFollowController.Snapshot snapshot = controller.tick(tick);
        if (remaining <= 1) {
            done.accept(snapshot);
            return;
        }
        helper.runAfterDelay(1L, () -> tickFor(
            helper,
            controller,
            tick + 1,
            remaining - 1,
            done
        ));
    }

    private static void tickUntil(
        GameTestHelper helper,
        CompanionFollowController controller,
        long tick,
        int remaining,
        java.util.function.Predicate<CompanionFollowController.Snapshot> condition,
        java.util.function.Consumer<CompanionFollowController.Snapshot> done
    ) {
        CompanionFollowController.Snapshot snapshot = controller.tick(tick);
        if (condition.test(snapshot)) {
            done.accept(snapshot);
            return;
        }
        helper.assertTrue(remaining > 1, Component.literal(
            "C1 bounded controller did not reach the required state: "
                + snapshot.state() + " / " + snapshot.reason()
        ));
        helper.runAfterDelay(1L, () -> tickUntil(
            helper,
            controller,
            tick + 1,
            remaining - 1,
            condition,
            done
        ));
    }

    private static void assertFollowCode(
        GameTestHelper helper,
        String expected,
        Runnable action
    ) {
        try {
            action.run();
            helper.fail(Component.literal("Expected C1 error: " + expected));
        } catch (CompanionFollowController.FollowException error) {
            helper.assertTrue(error.code().equals(expected), Component.literal(
                "Expected " + expected + " but received " + error.code()
            ));
        }
    }
}
