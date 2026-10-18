package com.casimirbot.helixcompanion.spike.gametest;

import com.casimirbot.helixcompanion.spike.CustomEntityMiningProbe;
import com.casimirbot.helixcompanion.spike.CompanionMiningSettlement;
import com.casimirbot.helixcompanion.spike.HelixCompanionSpikeMod;
import com.casimirbot.helixcompanion.spike.PlayerSemanticMiningProbe;
import com.casimirbot.helixcompanion.spike.SpikeCompanionEntity;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Blocks;

public final class CompanionFeasibilityGameTests {
    @GameTest(maxTicks = 40, skyAccess = false)
    public void s0CustomEntityHasOneBodyAndOneCanonicalInventory(GameTestHelper helper) {
        SpikeCompanionEntity companion = helper.spawn(
            HelixCompanionSpikeMod.SPIKE_COMPANION,
            new BlockPos(1, 1, 1)
        );
        companion.canonicalInventory().setItem(0, Items.WOODEN_PICKAXE.getDefaultInstance());

        helper.assertTrue(companion.isAlive(), Component.literal(
            "S0 custom companion must have one live visible body."
        ));
        helper.assertTrue(companion.canonicalInventory().getContainerSize() == 9, Component.literal(
            "S0 custom companion must expose one bounded canonical inventory."
        ));
        helper.assertTrue(
            companion.canonicalInventory().countItem(Items.WOODEN_PICKAXE) == 1,
            Component.literal("S0 canonical inventory must contain exactly one test tool.")
        );
        helper.succeed();
    }

    @GameTest(maxTicks = 40, skyAccess = false)
    public void s0CustomEntityBaselineDeclaresPlayerModifierGap(GameTestHelper helper) {
        BlockPos relative = new BlockPos(2, 1, 2);
        helper.setBlock(relative, Blocks.STONE);
        BlockPos absolute = helper.absolutePos(relative);

        CustomEntityMiningProbe.Result result = CustomEntityMiningProbe.baseline(
            helper.getLevel(),
            absolute,
            Items.WOODEN_PICKAXE.getDefaultInstance()
        );
        helper.assertTrue(result.correctToolForDrops(), Component.literal(
            "Wooden pickaxe must be harvest-eligible for stone in the baseline probe."
        ));
        helper.assertTrue(result.minimumTicks() > 1, Component.literal(
            "Candidate A must model progressive mining rather than instant removal."
        ));
        helper.assertFalse(result.playerModifiersSupported(), Component.literal(
            "Candidate A must expose, not conceal, its player-only modifier gap."
        ));
        helper.assertBlockPresent(Blocks.STONE, relative);
        helper.succeed();
    }

    @GameTest(maxTicks = 40, skyAccess = false)
    public void s0DetachedPlayerSemanticsProbeIsAccountIndependent(GameTestHelper helper) {
        helper.getLevel().setDefaultSpawnPos(new BlockPos(0, 80, 0), 0.0F);
        BlockPos relative = new BlockPos(3, 1, 3);
        helper.setBlock(relative, Blocks.STONE);
        BlockPos absolute = helper.absolutePos(relative);

        PlayerSemanticMiningProbe.Result result = PlayerSemanticMiningProbe.attempt(
            helper.getLevel().getServer(),
            helper.getLevel(),
            absolute,
            Items.WOODEN_PICKAXE.getDefaultInstance(),
            "gametest-companion-1",
            false,
            false
        );
        helper.assertFalse(result.networkConnectionPresent(), Component.literal(
            "S0 player-semantics subject must not synthesize a client connection."
        ));
        helper.assertTrue(result.vanillaProgressPerTick() > 0.0F, Component.literal(
            "Detached player semantics must expose vanilla progressive-mining calculation."
        ));
        helper.assertTrue(result.blockDestroyed(), Component.literal(
            "Candidate B must settle the normal server-player destruction pathway."
        ));
        helper.assertTrue(result.failureCode().equals("none"), Component.literal(
            "Candidate B must not hide a detached-player compatibility failure."
        ));
        helper.assertTrue(
            result.eventCounts().before() == 1
                && result.eventCounts().after() == 1
                && result.eventCounts().canceled() == 0,
            Component.literal("Candidate B must traverse before/after Fabric break hooks once.")
        );
        helper.assertBlockNotPresent(Blocks.STONE, relative);
        helper.succeed();
    }

    @GameTest(maxTicks = 40, skyAccess = false)
    public void s0RawDetachedPlayerFailsClosedOnFabricCancellation(GameTestHelper helper) {
        helper.getLevel().setDefaultSpawnPos(new BlockPos(0, 80, 0), 0.0F);
        BlockPos relative = new BlockPos(3, 1, 3);
        helper.setBlock(relative, Blocks.STONE);
        BlockPos absolute = helper.absolutePos(relative);

        PlayerSemanticMiningProbe.Result result = PlayerSemanticMiningProbe.attempt(
            helper.getLevel().getServer(),
            helper.getLevel(),
            absolute,
            Items.WOODEN_PICKAXE.getDefaultInstance(),
            "gametest-companion-canceled",
            true,
            false
        );
        helper.assertFalse(result.blockDestroyed(), Component.literal(
            "A canceled companion break must not report destruction."
        ));
        helper.assertTrue(
            result.eventCounts().before() == 1
                && result.eventCounts().after() == 0
                && result.eventCounts().canceled() == 0,
            Component.literal("Raw detached subject must stop at the known client-update gap.")
        );
        helper.assertTrue(
            result.failureCode().equals("detached_player_interaction_failed:NullPointerException")
                && result.failureLocation().contains("InteractionEventsRouter"),
            Component.literal("Raw Candidate B must retain the exact Fabric cancellation gap.")
        );
        helper.assertTrue(
            result.toolDamageAfter() == result.toolDamageBefore(),
            Component.literal("Canceled break must not consume tool durability.")
        );
        helper.assertBlockPresent(Blocks.STONE, relative);
        helper.succeed();
    }

    @GameTest(maxTicks = 40, skyAccess = false)
    public void s0BoundedPacketSinkPreservesFabricCancellation(GameTestHelper helper) {
        helper.getLevel().setDefaultSpawnPos(new BlockPos(0, 80, 0), 0.0F);
        BlockPos relative = new BlockPos(3, 1, 3);
        helper.setBlock(relative, Blocks.STONE);
        BlockPos absolute = helper.absolutePos(relative);

        PlayerSemanticMiningProbe.Result result = PlayerSemanticMiningProbe.attempt(
            helper.getLevel().getServer(),
            helper.getLevel(),
            absolute,
            Items.WOODEN_PICKAXE.getDefaultInstance(),
            "gametest-companion-bounded-sink",
            true,
            true
        );
        helper.assertFalse(result.blockDestroyed(), Component.literal(
            "Canceled sink-backed break must not mutate the world."
        ));
        helper.assertTrue(result.boundedPacketSinkInstalled(), Component.literal(
            "Candidate B repair must identify its bounded non-networked update sink."
        ));
        helper.assertTrue(
            result.eventCounts().before() == 1
                && result.eventCounts().after() == 0
                && result.eventCounts().canceled() == 1,
            Component.literal("Sink-backed cancellation must preserve all Fabric callbacks.")
        );
        helper.assertTrue(
            result.discardedClientUpdatePackets() == 27,
            Component.literal("Fabric cancellation must emit the bounded 3x3x3 update set.")
        );
        helper.assertTrue(result.failureCode().equals("none"), Component.literal(
            "Bounded sink must eliminate the raw detached-player null connection failure."
        ));
        helper.assertBlockPresent(Blocks.STONE, relative);
        helper.succeed();
    }

    @GameTest(maxTicks = 80, skyAccess = false)
    public void s0ProgressivePlayerSemanticsMatchesVanillaTicksAndDrops(GameTestHelper helper) {
        helper.getLevel().setDefaultSpawnPos(new BlockPos(0, 80, 0), 0.0F);
        BlockPos relative = new BlockPos(3, 1, 3);
        helper.setBlock(relative, Blocks.STONE);

        PlayerSemanticMiningProbe.ProgressiveResult result =
            PlayerSemanticMiningProbe.attemptProgressive(
                helper.getLevel().getServer(),
                helper.getLevel(),
                helper.absolutePos(relative),
                Items.WOODEN_PICKAXE.getDefaultInstance(),
                "gametest-companion-progressive",
                0,
                80
            );
        helper.assertTrue(result.failureCode().equals("none"), Component.literal(
            "Progressive Candidate B must complete without a compatibility failure."
        ));
        helper.assertTrue(result.blockDestroyed(), Component.literal(
            "Progressive Candidate B must remove the admitted stone block; observed="
                + result.observedActionTicks() + ", expected="
                + result.expectedActionTicks() + ", canInteract="
                + result.canInteractAtStart() + ", worldMayInteract="
                + result.worldMayInteractAtStart() + ", restricted="
                + result.blockActionRestrictedAtStart() + ", packets="
                + result.discardedClientUpdatePackets() + ", failure="
                + result.failureCode() + "."
        ));
        helper.assertTrue(
            result.observedActionTicks() == result.expectedActionTicks(),
            Component.literal(
                "Observed mining ticks must equal vanilla calculation: observed="
                    + result.observedActionTicks() + ", expected="
                    + result.expectedActionTicks() + "."
            )
        );
        helper.assertTrue(result.expectedActionTicks() == 23, Component.literal(
            "Grounded wooden-pickaxe stone mining must retain the pinned 23-tick oracle."
        ));
        helper.assertTrue(result.toolDamageDelta() == 1, Component.literal(
            "Successful stone mining must consume one wooden-pickaxe durability."
        ));
        helper.assertTrue(
            result.eventCounts().before() == 1
                && result.eventCounts().after() == 1
                && result.eventCounts().canceled() == 0,
            Component.literal("Progressive success must traverse before/after hooks once.")
        );
        helper.assertEntitiesPresent(EntityType.ITEM, 1);
        helper.succeed();
    }

    @GameTest(maxTicks = 60, skyAccess = false)
    public void s0ProgressiveDirtByHandMatchesPinnedVanillaTicks(GameTestHelper helper) {
        helper.getLevel().setDefaultSpawnPos(new BlockPos(0, 80, 0), 0.0F);
        BlockPos relative = new BlockPos(3, 1, 3);
        helper.setBlock(relative, Blocks.DIRT);

        PlayerSemanticMiningProbe.ProgressiveResult result =
            PlayerSemanticMiningProbe.attemptProgressive(
                helper.getLevel().getServer(),
                helper.getLevel(),
                helper.absolutePos(relative),
                net.minecraft.world.item.ItemStack.EMPTY,
                "gametest-companion-dirt-hand",
                0,
                60
            );
        helper.assertTrue(
            result.blockDestroyed()
                && result.expectedActionTicks() == 15
                && result.observedActionTicks() == 15,
            Component.literal(
                "Grounded dirt-by-hand must complete in the pinned 15 vanilla action ticks."
            )
        );
        helper.assertTrue(result.toolDamageDelta() == 0, Component.literal(
            "Dirt-by-hand must not manufacture tool durability."
        ));
        helper.assertTrue(
            result.eventCounts().before() == 1
                && result.eventCounts().after() == 1
                && result.eventCounts().canceled() == 0,
            Component.literal("Dirt-by-hand must traverse normal Fabric callbacks once.")
        );
        helper.assertEntitiesPresent(EntityType.ITEM, 1);
        helper.succeed();
    }

    @GameTest(maxTicks = 80, skyAccess = false)
    public void s0ProgressiveAbortProducesNoLateMutationOrWear(GameTestHelper helper) {
        helper.getLevel().setDefaultSpawnPos(new BlockPos(0, 80, 0), 0.0F);
        BlockPos relative = new BlockPos(3, 1, 3);
        helper.setBlock(relative, Blocks.STONE);

        PlayerSemanticMiningProbe.ProgressiveResult result =
            PlayerSemanticMiningProbe.attemptProgressive(
                helper.getLevel().getServer(),
                helper.getLevel(),
                helper.absolutePos(relative),
                Items.WOODEN_PICKAXE.getDefaultInstance(),
                "gametest-companion-abort",
                3,
                80
            );
        helper.assertTrue(result.aborted(), Component.literal(
            "Bounded progressive mining must report explicit abort."
        ));
        helper.assertFalse(result.blockDestroyed(), Component.literal(
            "Aborted mining must not mutate the block after settle ticks."
        ));
        helper.assertTrue(result.toolDamageDelta() == 0, Component.literal(
            "Aborted mining must not consume durability."
        ));
        helper.assertTrue(
            result.eventCounts().before() == 0
                && result.eventCounts().after() == 0
                && result.eventCounts().canceled() == 0,
            Component.literal("Abort before settlement must not emit completion callbacks.")
        );
        helper.assertBlockPresent(Blocks.STONE, relative);
        helper.assertEntityNotPresent(EntityType.ITEM);
        helper.succeed();
    }

    @GameTest(maxTicks = 220, skyAccess = false)
    public void s0WrongToolBreaksStoneWithoutManufacturingDrop(GameTestHelper helper) {
        helper.getLevel().setDefaultSpawnPos(new BlockPos(0, 80, 0), 0.0F);
        BlockPos relative = new BlockPos(3, 1, 3);
        helper.setBlock(relative, Blocks.STONE);

        PlayerSemanticMiningProbe.ProgressiveResult result =
            PlayerSemanticMiningProbe.attemptProgressive(
                helper.getLevel().getServer(),
                helper.getLevel(),
                helper.absolutePos(relative),
                net.minecraft.world.item.ItemStack.EMPTY,
                "gametest-companion-stone-hand",
                0,
                180
            );
        helper.assertTrue(
            result.blockDestroyed()
                && result.expectedActionTicks() == 150
                && result.observedActionTicks() == 150,
            Component.literal(
                "Stone-by-hand must retain the pinned 150-tick vanilla penalty."
            )
        );
        helper.assertTrue(result.toolDamageDelta() == 0, Component.literal(
            "A hand cannot acquire synthetic durability wear."
        ));
        helper.assertEntityNotPresent(EntityType.ITEM);
        helper.succeed();
    }

    @GameTest(maxTicks = 160, skyAccess = false)
    public void s0AirbornePenaltyUsesVanillaPlayerState(GameTestHelper helper) {
        helper.getLevel().setDefaultSpawnPos(new BlockPos(0, 80, 0), 0.0F);
        BlockPos relative = new BlockPos(3, 1, 3);
        helper.setBlock(relative, Blocks.STONE);

        PlayerSemanticMiningProbe.ProgressiveResult result =
            PlayerSemanticMiningProbe.attemptProgressive(
                helper.getLevel().getServer(),
                helper.getLevel(),
                helper.absolutePos(relative),
                Items.WOODEN_PICKAXE.getDefaultInstance(),
                "gametest-companion-airborne",
                0,
                150,
                new PlayerSemanticMiningProbe.MiningConditions(false, false, -1, -1)
            );
        helper.assertTrue(
            result.blockDestroyed()
                && result.expectedActionTicks() == 113
                && result.observedActionTicks() == 113,
            Component.literal(
                "Airborne wooden-pickaxe stone mining must retain the pinned 113-tick oracle."
            )
        );
        helper.assertEntitiesPresent(EntityType.ITEM, 1);
        helper.succeed();
    }

    @GameTest(maxTicks = 80, skyAccess = false)
    public void s0HasteOneChangesVanillaProgressRatherThanFixedTimer(GameTestHelper helper) {
        helper.getLevel().setDefaultSpawnPos(new BlockPos(0, 80, 0), 0.0F);
        BlockPos relative = new BlockPos(3, 1, 3);
        helper.setBlock(relative, Blocks.STONE);

        PlayerSemanticMiningProbe.ProgressiveResult result =
            PlayerSemanticMiningProbe.attemptProgressive(
                helper.getLevel().getServer(),
                helper.getLevel(),
                helper.absolutePos(relative),
                Items.WOODEN_PICKAXE.getDefaultInstance(),
                "gametest-companion-haste-one",
                0,
                80,
                new PlayerSemanticMiningProbe.MiningConditions(true, false, 0, -1)
            );
        helper.assertTrue(
            result.blockDestroyed()
                && result.expectedActionTicks() == 19
                && result.observedActionTicks() == 19,
            Component.literal(
                "Haste I must reduce the pinned stone trial from 23 to 19 action ticks."
            )
        );
        helper.assertTrue(result.toolDamageDelta() == 1, Component.literal(
            "Haste changes timing, not successful-break durability settlement."
        ));
        helper.succeed();
    }

    @GameTest(maxTicks = 120, skyAccess = false)
    public void s0MiningFatigueOneUsesVanillaEffectMultiplier(GameTestHelper helper) {
        helper.getLevel().setDefaultSpawnPos(new BlockPos(0, 80, 0), 0.0F);
        BlockPos relative = new BlockPos(3, 1, 3);
        helper.setBlock(relative, Blocks.STONE);

        PlayerSemanticMiningProbe.ProgressiveResult result =
            PlayerSemanticMiningProbe.attemptProgressive(
                helper.getLevel().getServer(),
                helper.getLevel(),
                helper.absolutePos(relative),
                Items.WOODEN_PICKAXE.getDefaultInstance(),
                "gametest-companion-fatigue-one",
                0,
                110,
                new PlayerSemanticMiningProbe.MiningConditions(true, false, -1, 0)
            );
        helper.assertTrue(
            result.blockDestroyed()
                && result.expectedActionTicks() == 75
                && result.observedActionTicks() == 75,
            Component.literal(
                "Mining Fatigue I must increase the pinned stone trial from 23 to 75 action ticks."
            )
        );
        helper.succeed();
    }

    @GameTest(maxTicks = 160, skyAccess = false)
    public void s0SubmergedEyesUseVanillaMiningAttribute(GameTestHelper helper) {
        helper.getLevel().setDefaultSpawnPos(new BlockPos(0, 80, 0), 0.0F);
        BlockPos relative = new BlockPos(3, 1, 3);
        helper.setBlock(relative, Blocks.STONE);

        PlayerSemanticMiningProbe.ProgressiveResult result =
            PlayerSemanticMiningProbe.attemptProgressive(
                helper.getLevel().getServer(),
                helper.getLevel(),
                helper.absolutePos(relative),
                Items.WOODEN_PICKAXE.getDefaultInstance(),
                "gametest-companion-submerged",
                0,
                150,
                new PlayerSemanticMiningProbe.MiningConditions(true, true, -1, -1)
            );
        helper.assertTrue(result.eyeSubmergedAtStart(), Component.literal(
            "The underwater trial must prove the mechanics subject's eyes are in water."
        ));
        helper.assertTrue(
            result.blockDestroyed()
                && result.expectedActionTicks() == 113
                && result.observedActionTicks() == 113,
            Component.literal(
                "Submerged wooden-pickaxe stone mining must retain the pinned 113-tick oracle."
            )
        );
        helper.succeed();
    }

    @GameTest(maxTicks = 100, skyAccess = false)
    public void s0HybridSettlesOnlyIntoVisibleCanonicalInventory(GameTestHelper helper) {
        helper.getLevel().setDefaultSpawnPos(new BlockPos(0, 80, 0), 0.0F);
        SpikeCompanionEntity companion = helper.spawn(
            HelixCompanionSpikeMod.SPIKE_COMPANION,
            new BlockPos(1, 1, 1)
        );
        ItemStack canonicalBefore = Items.WOODEN_PICKAXE.getDefaultInstance();
        companion.canonicalInventory().setItem(0, canonicalBefore.copy());
        BlockPos relative = new BlockPos(3, 1, 3);
        helper.setBlock(relative, Blocks.STONE);

        PlayerSemanticMiningProbe.ProgressiveResult mechanics =
            PlayerSemanticMiningProbe.attemptProgressive(
                helper.getLevel().getServer(),
                helper.getLevel(),
                helper.absolutePos(relative),
                canonicalBefore,
                "gametest-companion-hybrid-settlement",
                0,
                80
            );
        CompanionMiningSettlement.Result settlement =
            CompanionMiningSettlement.settleToolWear(
                companion,
                0,
                canonicalBefore,
                mechanics
            );

        helper.assertTrue(
            mechanics.onlinePlayersBefore() == mechanics.onlinePlayersAfter()
                && !mechanics.mechanicsSubjectRegistered(),
            Component.literal(
                "The bounded mechanics subject must never become an online player actor."
            )
        );
        helper.assertTrue(
            settlement.applied()
                && settlement.canonicalDamageBefore() == 0
                && settlement.canonicalDamageAfter() == 1,
            Component.literal(
                "Exactly one measured wear delta must settle into the visible canonical tool."
            )
        );
        helper.assertTrue(
            companion.canonicalInventory().countItem(Items.WOODEN_PICKAXE) == 1
                && companion.canonicalInventory().getItem(0).getDamageValue() == 1,
            Component.literal("Hybrid settlement must not duplicate the canonical tool.")
        );
        helper.assertEntitiesPresent(EntityType.ITEM, 1);
        helper.succeed();
    }

    @GameTest(maxTicks = 100, skyAccess = false)
    public void s0HybridRejectsStaleCanonicalPrestate(GameTestHelper helper) {
        helper.getLevel().setDefaultSpawnPos(new BlockPos(0, 80, 0), 0.0F);
        SpikeCompanionEntity companion = helper.spawn(
            HelixCompanionSpikeMod.SPIKE_COMPANION,
            new BlockPos(1, 1, 1)
        );
        ItemStack expectedBefore = Items.WOODEN_PICKAXE.getDefaultInstance();
        ItemStack externallyChanged = expectedBefore.copy();
        externallyChanged.setDamageValue(2);
        companion.canonicalInventory().setItem(0, externallyChanged);
        BlockPos relative = new BlockPos(3, 1, 3);
        helper.setBlock(relative, Blocks.STONE);

        PlayerSemanticMiningProbe.ProgressiveResult mechanics =
            PlayerSemanticMiningProbe.attemptProgressive(
                helper.getLevel().getServer(),
                helper.getLevel(),
                helper.absolutePos(relative),
                expectedBefore,
                "gametest-companion-stale-settlement",
                0,
                80
            );
        CompanionMiningSettlement.Result settlement =
            CompanionMiningSettlement.settleToolWear(
                companion,
                0,
                expectedBefore,
                mechanics
            );

        helper.assertFalse(settlement.applied(), Component.literal(
            "A stale canonical inventory prestate must fail closed."
        ));
        helper.assertTrue(
            settlement.outcome().equals("canonical_prestate_mismatch")
                && companion.canonicalInventory().getItem(0).getDamageValue() == 2,
            Component.literal(
                "Rejected settlement must preserve the externally changed canonical tool."
            )
        );
        helper.succeed();
    }
}
