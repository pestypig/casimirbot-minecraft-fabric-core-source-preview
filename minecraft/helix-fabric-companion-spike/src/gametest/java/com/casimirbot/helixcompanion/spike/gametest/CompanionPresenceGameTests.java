package com.casimirbot.helixcompanion.spike.gametest;

import com.casimirbot.helixcompanion.spike.CompanionPresenceRuntime;
import com.casimirbot.helixcompanion.spike.HelixCompanionSpikeMod;
import com.casimirbot.helixcompanion.spike.SpikeCompanionEntity;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CompanionPresenceGameTests {
    private static final String PROFILE_HASH =
        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @GameTest(maxTicks = 80, skyAccess = false)
    public void c0A0VisibleIdentityRestartRotationAndCleanup(GameTestHelper helper) {
        CompanionPresenceRuntime.Profile profile = profile();
        long startTick = helper.getLevel().getGameTime();
        for (int x = 0; x <= 5; x++) {
            helper.setBlock(new BlockPos(x, 0, 1), Blocks.STONE);
            helper.setBlock(new BlockPos(x, 1, 1), Blocks.AIR);
            helper.setBlock(new BlockPos(x, 2, 1), Blocks.AIR);
        }
        SpikeCompanionEntity firstActor = helper.spawn(
            HelixCompanionSpikeMod.SPIKE_COMPANION,
            new BlockPos(1, 1, 1)
        );
        helper.runAfterDelay(2L, () -> {
        CompanionPresenceRuntime first = new CompanionPresenceRuntime(
            profile,
            firstActor,
            helper.getLevel(),
            "incarnation:c0-a0:1",
            "connector-epoch:c0-a0:1",
            startTick + 40
        );
        first.admit("actor-lease:c0-a0:1", "effect-lease:c0-a0:1");

        BlockPos navigationTarget = helper.absolutePos(new BlockPos(4, 1, 1));
        awaitNativeNavigation(helper, first, firstActor, navigationTarget, 20, () -> {
        ChunkPos claimedChunk = firstActor.chunkPosition();
        first.claimChunk(claimedChunk);
        first.queueTask("task:c0-a0:follow-owner");
        CompanionPresenceRuntime.ActionLease oldAction = first.issueAction("action:c0-a0:old");

        helper.assertTrue(firstActor.isAlive() && !firstActor.isRemoved(), Component.literal(
            "C0 A0 requires one live registered companion entity."
        ));
        helper.assertTrue(firstActor.getNavigation().isInProgress(), Component.literal(
            "The physical A0 fixture must own a native navigation path before cleanup."
        ));
        helper.assertTrue(
            helper.getLevel().getForceLoadedChunks().contains(claimedChunk.toLong()),
            Component.literal("The physical A0 fixture must own one measured chunk claim.")
        );
        helper.assertTrue(first.checkAction(oldAction, startTick + 1).current(), Component.literal(
            "The exact first-incarnation action must be current before restart."
        ));

        CompoundTag persisted = CompanionPresenceRuntime.snapshotProfile(
            profile,
            first.incarnationId()
        );
        helper.assertFalse(persisted.getBooleanOr("active_incarnation_persisted", true), Component.literal(
            "Persistence must not retain an active incarnation."
        ));
        helper.assertFalse(persisted.contains("actor_lease_id"), Component.literal(
            "Persistence must not contain an actor lease value."
        ));

        CompanionPresenceRuntime.CleanupReceipt restartCleanup = first.cleanup(
            "cleanup:c0-a0:restart",
            "restart",
            true
        );
        assertCompleteCleanup(helper, restartCleanup, claimedChunk);

        CompanionPresenceRuntime.RestoredProfile restored =
            CompanionPresenceRuntime.restoreProfile(persisted);
        helper.assertTrue(
            restored.profile().equals(profile)
                && restored.previousIncarnationId().equals("incarnation:c0-a0:1"),
            Component.literal("Restart reconstruction must retain only durable profile identity.")
        );

        SpikeCompanionEntity secondActor = helper.spawn(
            HelixCompanionSpikeMod.SPIKE_COMPANION,
            new BlockPos(2, 1, 1)
        );
        CompanionPresenceRuntime second = new CompanionPresenceRuntime(
            restored.profile(),
            secondActor,
            helper.getLevel(),
            "incarnation:c0-a0:2",
            "connector-epoch:c0-a0:2",
            startTick + 60
        );
        second.admit("actor-lease:c0-a0:2", "effect-lease:c0-a0:2");
        CompanionPresenceRuntime.ActionLease currentAction = second.issueAction(
            "action:c0-a0:current"
        );

        helper.assertTrue(!firstActor.getUUID().equals(secondActor.getUUID()), Component.literal(
            "Restart reconstruction must create a distinct physical entity."
        ));
        helper.assertTrue(
            !second.checkAction(oldAction, startTick + 2).current()
                && second.checkAction(oldAction, startTick + 2).reason()
                    .equals("companion_action_identity_stale"),
            Component.literal("The new incarnation must reject the old action and leases.")
        );
        helper.assertTrue(second.checkAction(currentAction, startTick + 2).current(), Component.literal(
            "The fresh incarnation action must remain current."
        ));

        ChunkPos secondClaim = secondActor.chunkPosition();
        second.claimChunk(secondClaim);
        second.queueTask("task:c0-a0:hold");
        CompanionPresenceRuntime.CleanupReceipt manualCleanup = second.cleanup(
            "cleanup:c0-a0:manual",
            "manual_override",
            true
        );
        assertCompleteCleanup(helper, manualCleanup, secondClaim);
        Path a1EvidencePath = CompanionPresenceA1EvidenceWriter.write(
            restored.profile(),
            second,
            manualCleanup,
            secondClaim
        );
        helper.assertTrue(Files.isRegularFile(a1EvidencePath), Component.literal(
            "C0 A1 requires one atomic physical evidence export."
        ));
        helper.succeed();
        });
        });
    }

    @GameTest(maxTicks = 40, skyAccess = false)
    public void c0A0FinitePresenceExpiryRemovesActor(GameTestHelper helper) {
        long startTick = helper.getLevel().getGameTime();
        SpikeCompanionEntity actor = helper.spawn(
            HelixCompanionSpikeMod.SPIKE_COMPANION,
            new BlockPos(1, 1, 1)
        );
        CompanionPresenceRuntime runtime = new CompanionPresenceRuntime(
            profile(),
            actor,
            helper.getLevel(),
            "incarnation:c0-a0:expiry",
            "connector-epoch:c0-a0:expiry",
            startTick + 5
        );
        runtime.admit("actor-lease:c0-a0:expiry", "effect-lease:c0-a0:expiry");
        ChunkPos claim = actor.chunkPosition();
        runtime.claimChunk(claim);
        runtime.queueTask("task:c0-a0:expiry");

        helper.assertTrue(runtime.enforceExpiry("cleanup:too-early", startTick + 4) == null,
            Component.literal("Finite presence must remain active before its exact expiry tick."));
        CompanionPresenceRuntime.CleanupReceipt receipt = runtime.enforceExpiry(
            "cleanup:c0-a0:expiry",
            startTick + 5
        );
        helper.assertTrue(receipt != null && receipt.reason().equals("lease_expired"),
            Component.literal("The exact expiry tick must produce typed cleanup."));
        assertCompleteCleanup(helper, receipt, claim);
        helper.assertTrue(
            runtime.checkAction(
                new CompanionPresenceRuntime.ActionLease(
                    "action:expired",
                    profile().companionId(),
                    actor.getUUID().toString(),
                    "incarnation:c0-a0:expiry",
                    "connector-epoch:c0-a0:expiry",
                    "actor-lease:c0-a0:expiry",
                    "effect-lease:c0-a0:expiry",
                    2L,
                    startTick + 5
                ),
                startTick + 5
            ).reason().equals("companion_not_active"),
            Component.literal("No action may revive an expired and removed actor."));
        helper.succeed();
    }

    private static void assertCompleteCleanup(
        GameTestHelper helper,
        CompanionPresenceRuntime.CleanupReceipt receipt,
        ChunkPos claimedChunk
    ) {
        helper.assertTrue(
            receipt.navigationReleased()
                && receipt.chunksReleased()
                && receipt.tasksReleased()
                && receipt.controlsReleased()
                && receipt.actorRemoved()
                && receipt.lateEffectCount() == 0
                && receipt.duplicateEffectCount() == 0,
            Component.literal("Cleanup must release entity, navigation, chunks, tasks and controls.")
        );
        helper.assertFalse(
            helper.getLevel().getForceLoadedChunks().contains(claimedChunk.toLong()),
            Component.literal("Cleanup must remove the exact forced chunk claim.")
        );
    }

    private static void awaitNativeNavigation(
        GameTestHelper helper,
        CompanionPresenceRuntime runtime,
        SpikeCompanionEntity actor,
        BlockPos target,
        int attemptsRemaining,
        Runnable onReady
    ) {
        boolean started = runtime.startNavigation(
            target.getX() + 0.5D,
            target.getY(),
            target.getZ() + 0.5D,
            0.8D
        );
        if (started && actor.getNavigation().isInProgress()) {
            onReady.run();
            return;
        }
        helper.assertTrue(attemptsRemaining > 0, Component.literal(
            "The physical A0 fixture could not acquire its bounded native navigation path."
        ));
        helper.runAfterDelay(1L, () -> awaitNativeNavigation(
            helper,
            runtime,
            actor,
            target,
            attemptsRemaining - 1,
            onReady
        ));
    }

    private static CompanionPresenceRuntime.Profile profile() {
        return new CompanionPresenceRuntime.Profile(
            "companion:noble-one",
            "account:owner",
            "subject:owner",
            "player:owner",
            "resident.minecraft.companion-follow.v1",
            PROFILE_HASH
        );
    }
}
