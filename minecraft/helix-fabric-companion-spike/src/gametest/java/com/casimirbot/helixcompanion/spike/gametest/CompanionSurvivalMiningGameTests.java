package com.casimirbot.helixcompanion.spike.gametest;

import com.casimirbot.helixcompanion.spike.CompanionInventoryCustody;
import com.casimirbot.helixcompanion.spike.CompanionMiningSettlement;
import com.casimirbot.helixcompanion.spike.CompanionPresenceRuntime;
import com.casimirbot.helixcompanion.spike.HelixCompanionSpikeMod;
import com.casimirbot.helixcompanion.spike.PlayerSemanticMiningProbe;
import com.casimirbot.helixcompanion.spike.SpikeCompanionEntity;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

public final class CompanionSurvivalMiningGameTests {
    @GameTest(maxTicks = 100, skyAccess = false)
    public void c3A0StoneSettlesBlockDropWearAndCustodyOnce(GameTestHelper helper) {
        Fixture fixture = fixture(helper, "stone", 120L);
        ItemStack pickaxe = Items.WOODEN_PICKAXE.getDefaultInstance();
        fixture.actor().canonicalInventory().setItem(0, pickaxe.copy());
        BlockPos relative = new BlockPos(3, 1, 3);
        helper.setBlock(relative, Blocks.STONE);
        BlockPos target = helper.absolutePos(relative);
        BlockState before = helper.getLevel().getBlockState(target);

        PlayerSemanticMiningProbe.ProgressiveResult mechanics =
            PlayerSemanticMiningProbe.attemptProgressive(
                helper.getLevel().getServer(), helper.getLevel(), target, pickaxe,
                "c3-stone", 0, 80
            );
        CompanionMiningSettlement.BreakResult result = CompanionMiningSettlement.settleBreak(
            "tx:c3:stone", fixture.lease(), fixture.custody(), fixture.custody().revision(),
            fixture.actor(), 0, pickaxe, helper.getLevel(), target, before, mechanics
        );

        helper.assertTrue(
            result.applied()
                && result.blockAirAfter()
                && result.observedDropItems() == 1
                && fixture.actor().canonicalInventory().countItem(Items.COBBLESTONE) == 1
                && fixture.actor().canonicalInventory().getItem(0).getDamageValue() == 1
                && result.custodyReceipt().revisionBefore() == 1
                && result.custodyReceipt().revisionAfter() == 2
                && result.custodyReceipt().miningAuthority()
                && !result.custodyReceipt().craftingAuthority()
                && !result.custodyReceipt().combatAuthority()
                && !result.custodyReceipt().worldAuthority()
                && !result.custodyReceipt().answerAuthority()
                && !result.custodyReceipt().terminalAuthority(),
            Component.literal("C3 stone must atomically settle one normal drop and one wear delta.")
        );
        helper.assertEntityNotPresent(EntityType.ITEM);
        CompanionMiningA1EvidenceWriter.record(
            "stone_drop_wear_atomic", "c3A0StoneSettlesBlockDropWearAndCustodyOnce",
            fixture.actor(), fixture.presence(), fixture.custody(), Map.of(
                "ticks", Integer.toString(mechanics.observedActionTicks()),
                "drop_items", Integer.toString(result.observedDropItems()),
                "tool_damage", Integer.toString(fixture.actor().canonicalInventory().getItem(0).getDamageValue()),
                "custody_revision", Long.toString(fixture.custody().revision())
            )
        );
        helper.succeed();
    }

    @GameTest(maxTicks = 100, skyAccess = false)
    public void c3A0StaleCustodyRestoresBlockAndRemovesUnownedDrop(GameTestHelper helper) {
        Fixture fixture = fixture(helper, "stale", 120L);
        ItemStack pickaxe = Items.WOODEN_PICKAXE.getDefaultInstance();
        fixture.actor().canonicalInventory().setItem(0, pickaxe.copy());
        BlockPos relative = new BlockPos(3, 1, 3);
        helper.setBlock(relative, Blocks.STONE);
        BlockPos target = helper.absolutePos(relative);
        BlockState before = helper.getLevel().getBlockState(target);

        PlayerSemanticMiningProbe.ProgressiveResult mechanics =
            PlayerSemanticMiningProbe.attemptProgressive(
                helper.getLevel().getServer(), helper.getLevel(), target, pickaxe,
                "c3-stale", 0, 80
            );
        CompanionMiningSettlement.BreakResult result = CompanionMiningSettlement.settleBreak(
            "tx:c3:stale", fixture.lease(), fixture.custody(), fixture.custody().revision() + 1,
            fixture.actor(), 0, pickaxe, helper.getLevel(), target, before, mechanics
        );

        helper.assertFalse(result.applied(), Component.literal("A stale custody revision must fail closed."));
        helper.assertTrue(
            result.outcome().equals("companion_custody_revision_stale")
                && helper.getLevel().getBlockState(target).is(Blocks.STONE)
                && fixture.actor().canonicalInventory().countItem(Items.COBBLESTONE) == 0
                && fixture.actor().canonicalInventory().getItem(0).getDamageValue() == 0
                && fixture.custody().revision() == 1,
            Component.literal("Rejected settlement must restore the exact block and canonical economy.")
        );
        helper.assertEntityNotPresent(EntityType.ITEM);
        CompanionMiningA1EvidenceWriter.record(
            "stale_revision_rollback", "c3A0StaleCustodyRestoresBlockAndRemovesUnownedDrop",
            fixture.actor(), fixture.presence(), fixture.custody(), Map.of(
                "failure_code", result.outcome(),
                "block_restored", "true", "orphan_drops", "0", "tool_damage", "0"
            )
        );
        helper.succeed();
    }

    @GameTest(maxTicks = 100, skyAccess = false)
    public void c3A0FinalDurabilityBreaksToolWithoutDuplication(GameTestHelper helper) {
        Fixture fixture = fixture(helper, "tool-break", 120L);
        ItemStack pickaxe = Items.WOODEN_PICKAXE.getDefaultInstance();
        pickaxe.setDamageValue(pickaxe.getMaxDamage() - 1);
        fixture.actor().canonicalInventory().setItem(0, pickaxe.copy());
        BlockPos relative = new BlockPos(3, 1, 3);
        helper.setBlock(relative, Blocks.STONE);
        BlockPos target = helper.absolutePos(relative);
        BlockState before = helper.getLevel().getBlockState(target);

        PlayerSemanticMiningProbe.ProgressiveResult mechanics =
            PlayerSemanticMiningProbe.attemptProgressive(
                helper.getLevel().getServer(), helper.getLevel(), target, pickaxe,
                "c3-tool-break", 0, 80
            );
        CompanionMiningSettlement.BreakResult result = CompanionMiningSettlement.settleBreak(
            "tx:c3:tool-break", fixture.lease(), fixture.custody(), fixture.custody().revision(),
            fixture.actor(), 0, pickaxe, helper.getLevel(), target, before, mechanics
        );

        helper.assertTrue(
            result.applied() && result.toolBroke() && mechanics.toolBroke()
                && mechanics.toolDamageDelta() == 1
                && fixture.actor().canonicalInventory().countItem(Items.WOODEN_PICKAXE) == 0
                && fixture.actor().canonicalInventory().countItem(Items.COBBLESTONE) == 1,
            Component.literal("Final durability must consume one tool and preserve exactly one drop.")
        );
        helper.assertEntityNotPresent(EntityType.ITEM);
        CompanionMiningA1EvidenceWriter.record(
            "final_durability_break", "c3A0FinalDurabilityBreaksToolWithoutDuplication",
            fixture.actor(), fixture.presence(), fixture.custody(), Map.of(
                "tool_broke", Boolean.toString(result.toolBroke()),
                "wear_delta", Integer.toString(mechanics.toolDamageDelta()),
                "drop_items", Integer.toString(result.observedDropItems())
            )
        );
        helper.succeed();
    }

    @GameTest(maxTicks = 100, skyAccess = false)
    public void c3A0TickGuardsAbortLeaseTargetRangeAndEmergencyStop(GameTestHelper helper) {
        ItemStack pickaxe = Items.WOODEN_PICKAXE.getDefaultInstance();

        Fixture expiry = fixture(helper, "expiry", 120L);
        BlockPos expiryRelative = new BlockPos(3, 1, 3);
        helper.setBlock(expiryRelative, Blocks.STONE);
        BlockPos expiryTarget = helper.absolutePos(expiryRelative);
        long admittedTick = helper.getLevel().getGameTime();
        CompanionPresenceRuntime.ActionLease shortLease = expiry.presence().issueAction(
            "action:c3:expiry:short", admittedTick + 3
        );
        PlayerSemanticMiningProbe.ProgressiveResult expired =
            PlayerSemanticMiningProbe.attemptProgressive(
                helper.getLevel().getServer(), helper.getLevel(), expiryTarget, pickaxe,
                "c3-expiry", 0, 80, PlayerSemanticMiningProbe.MiningConditions.grounded(),
                (tick, subject, level, target) -> {
                    CompanionPresenceRuntime.ActionCheck check = expiry.presence().checkAction(
                        shortLease, admittedTick + tick
                    );
                    return check.current() ? null : check.reason();
                }
            );

        Fixture replacement = fixture(helper, "replacement", 120L);
        BlockPos replacementRelative = new BlockPos(6, 1, 3);
        helper.setBlock(replacementRelative, Blocks.STONE);
        BlockPos replacementTarget = helper.absolutePos(replacementRelative);
        PlayerSemanticMiningProbe.ProgressiveResult replaced =
            PlayerSemanticMiningProbe.attemptProgressive(
                helper.getLevel().getServer(), helper.getLevel(), replacementTarget, pickaxe,
                "c3-replacement", 0, 80, PlayerSemanticMiningProbe.MiningConditions.grounded(),
                (tick, subject, level, target) -> {
                    if (tick < 3) return null;
                    level.setBlockAndUpdate(target, Blocks.DIRT.defaultBlockState());
                    return "companion_mining_target_replaced";
                }
            );

        Fixture range = fixture(helper, "range", 120L);
        BlockPos rangeRelative = new BlockPos(9, 1, 3);
        helper.setBlock(rangeRelative, Blocks.STONE);
        BlockPos rangeTarget = helper.absolutePos(rangeRelative);
        PlayerSemanticMiningProbe.ProgressiveResult rangeLost =
            PlayerSemanticMiningProbe.attemptProgressive(
                helper.getLevel().getServer(), helper.getLevel(), rangeTarget, pickaxe,
                "c3-range", 0, 80, PlayerSemanticMiningProbe.MiningConditions.grounded(),
                (tick, subject, level, target) -> {
                    if (tick < 3) return null;
                    subject.knockback(
                        32.0D,
                        target.getX() + 0.5D - subject.getX(),
                        target.getZ() + 0.5D - subject.getZ()
                    );
                    subject.move(MoverType.SELF, subject.getDeltaMovement());
                    return subject.canInteractWithBlock(target, 1.0D)
                        ? null : "companion_mining_range_lost";
                }
            );

        Fixture stopped = fixture(helper, "emergency", 120L);
        BlockPos stoppedRelative = new BlockPos(12, 1, 3);
        helper.setBlock(stoppedRelative, Blocks.STONE);
        BlockPos stoppedTarget = helper.absolutePos(stoppedRelative);
        PlayerSemanticMiningProbe.ProgressiveResult emergencyStopped =
            PlayerSemanticMiningProbe.attemptProgressive(
                helper.getLevel().getServer(), helper.getLevel(), stoppedTarget, pickaxe,
                "c3-emergency", 0, 80, PlayerSemanticMiningProbe.MiningConditions.grounded(),
                (tick, subject, level, target) -> {
                    if (tick < 3) return null;
                    stopped.presence().cleanup("cleanup:c3:emergency", "emergency_stop", false);
                    return stopped.presence().checkAction(stopped.lease(), admittedTick + tick).reason();
                }
            );

        helper.assertTrue(
            expired.aborted() && expired.failureCode().equals("companion_action_expired")
                && helper.getLevel().getBlockState(expiryTarget).is(Blocks.STONE)
                && replaced.aborted() && replaced.failureCode().equals("companion_mining_target_replaced")
                && helper.getLevel().getBlockState(replacementTarget).is(Blocks.DIRT)
                && rangeLost.aborted() && rangeLost.failureCode().equals("companion_mining_range_lost")
                && helper.getLevel().getBlockState(rangeTarget).is(Blocks.STONE)
                && emergencyStopped.aborted()
                && emergencyStopped.failureCode().equals("companion_not_active")
                && helper.getLevel().getBlockState(stoppedTarget).is(Blocks.STONE),
            Component.literal("Every tick-local C3 guard must abort before block, drop, or wear settlement.")
        );
        helper.assertEntityNotPresent(EntityType.ITEM);
        CompanionMiningA1EvidenceWriter.record(
            "tick_guard_interruptions", "c3A0TickGuardsAbortLeaseTargetRangeAndEmergencyStop",
            stopped.actor(), stopped.presence(), stopped.custody(), Map.of(
                "lease_expiry", expired.failureCode(),
                "target_replacement", replaced.failureCode(),
                "knockback_range_loss", rangeLost.failureCode(),
                "emergency_stop", emergencyStopped.failureCode()
            )
        );
        helper.succeed();
    }

    @GameTest(maxTicks = 260, skyAccess = false)
    public void c3A0DirtHandWrongToolAndProtectionPreserveVanillaEconomy(GameTestHelper helper) {
        Fixture fixture = fixture(helper, "hand-and-protection", 300L);

        BlockPos dirtRelative = new BlockPos(3, 1, 3);
        helper.setBlock(dirtRelative, Blocks.DIRT);
        BlockPos dirtTarget = helper.absolutePos(dirtRelative);
        BlockState dirtBefore = helper.getLevel().getBlockState(dirtTarget);
        PlayerSemanticMiningProbe.ProgressiveResult dirtMechanics =
            PlayerSemanticMiningProbe.attemptProgressive(
                helper.getLevel().getServer(), helper.getLevel(), dirtTarget, ItemStack.EMPTY,
                "c3-dirt-hand", 0, 60
            );
        CompanionMiningSettlement.BreakResult dirt = CompanionMiningSettlement.settleBreak(
            "tx:c3:dirt-hand", fixture.lease(), fixture.custody(), fixture.custody().revision(),
            fixture.actor(), -1, ItemStack.EMPTY, helper.getLevel(), dirtTarget,
            dirtBefore, dirtMechanics
        );

        BlockPos wrongRelative = new BlockPos(7, 1, 3);
        helper.setBlock(wrongRelative, Blocks.STONE);
        BlockPos wrongTarget = helper.absolutePos(wrongRelative);
        BlockState wrongBefore = helper.getLevel().getBlockState(wrongTarget);
        PlayerSemanticMiningProbe.ProgressiveResult wrongMechanics =
            PlayerSemanticMiningProbe.attemptProgressive(
                helper.getLevel().getServer(), helper.getLevel(), wrongTarget, ItemStack.EMPTY,
                "c3-stone-hand", 0, 180
            );
        CompanionMiningSettlement.BreakResult wrong = CompanionMiningSettlement.settleBreak(
            "tx:c3:stone-hand", fixture.lease(), fixture.custody(), fixture.custody().revision(),
            fixture.actor(), -1, ItemStack.EMPTY, helper.getLevel(), wrongTarget,
            wrongBefore, wrongMechanics
        );

        BlockPos protectedRelative = new BlockPos(11, 1, 3);
        helper.setBlock(protectedRelative, Blocks.STONE);
        PlayerSemanticMiningProbe.Result protectedResult = PlayerSemanticMiningProbe.attempt(
            helper.getLevel().getServer(), helper.getLevel(), helper.absolutePos(protectedRelative),
            Items.WOODEN_PICKAXE.getDefaultInstance(), "c3-protected", true, true
        );

        helper.assertTrue(
            dirt.applied() && dirtMechanics.expectedActionTicks() == 15
                && dirtMechanics.observedActionTicks() == 15
                && fixture.actor().canonicalInventory().countItem(Items.DIRT) == 1
                && wrong.applied() && wrongMechanics.expectedActionTicks() == 150
                && wrongMechanics.observedActionTicks() == 150
                && wrong.observedDropItems() == 0
                && fixture.actor().canonicalInventory().countItem(Items.COBBLESTONE) == 0
                && !protectedResult.blockDestroyed()
                && protectedResult.eventCounts().before() == 1
                && protectedResult.eventCounts().after() == 0
                && protectedResult.eventCounts().canceled() == 1
                && helper.getBlockState(protectedRelative).is(Blocks.STONE),
            Component.literal("Hand, wrong-tool and protected mining must preserve vanilla timing and economy.")
        );
        helper.assertEntityNotPresent(EntityType.ITEM);
        CompanionMiningA1EvidenceWriter.record(
            "hand_wrong_tool_protection", "c3A0DirtHandWrongToolAndProtectionPreserveVanillaEconomy",
            fixture.actor(), fixture.presence(), fixture.custody(), Map.of(
                "dirt_hand_ticks", Integer.toString(dirtMechanics.observedActionTicks()),
                "stone_hand_ticks", Integer.toString(wrongMechanics.observedActionTicks()),
                "wrong_tool_drops", Integer.toString(wrong.observedDropItems()),
                "protection_canceled", Boolean.toString(protectedResult.eventCounts().canceled() == 1)
            )
        );
        helper.succeed();
    }

    @GameTest(maxTicks = 180, skyAccess = false)
    public void c3A0RestartBetweenBreakAndSettlementRejectsOldIncarnation(GameTestHelper helper) {
        ItemStack pickaxe = Items.WOODEN_PICKAXE.getDefaultInstance();
        Fixture first = fixture(helper, "restart-1", 220L);
        first.actor().canonicalInventory().setItem(0, pickaxe.copy());
        BlockPos relative = new BlockPos(3, 1, 3);
        helper.setBlock(relative, Blocks.STONE);
        BlockPos target = helper.absolutePos(relative);
        BlockState before = helper.getLevel().getBlockState(target);

        PlayerSemanticMiningProbe.ProgressiveResult oldMechanics =
            PlayerSemanticMiningProbe.attemptProgressive(
                helper.getLevel().getServer(), helper.getLevel(), target, pickaxe,
                "c3-restart-old", 0, 80
            );
        CompanionInventoryCustody.PersistentState saved = first.custody().snapshotForRestart(
            CompanionInventoryCustody.DeathPolicy.KEEP
        );
        first.presence().cleanup("cleanup:c3:restart-1", "restart", true);

        Fixture second = fixture(helper, "restart-2", 220L);
        second.custody().restoreAfterRestart(saved);
        CompanionMiningSettlement.BreakResult stale = CompanionMiningSettlement.settleBreak(
            "tx:c3:restart:stale", first.lease(), second.custody(), second.custody().revision(),
            second.actor(), 0, pickaxe, helper.getLevel(), target, before, oldMechanics
        );
        helper.assertTrue(
            !stale.applied()
                && stale.outcome().equals("companion_action_identity_stale")
                && helper.getLevel().getBlockState(target).is(Blocks.STONE)
                && second.actor().canonicalInventory().getItem(0).getDamageValue() == 0
                && second.actor().canonicalInventory().countItem(Items.COBBLESTONE) == 0,
            Component.literal("Restart must reject old-incarnation settlement and restore its world delta.")
        );

        PlayerSemanticMiningProbe.ProgressiveResult freshMechanics =
            PlayerSemanticMiningProbe.attemptProgressive(
                helper.getLevel().getServer(), helper.getLevel(), target, pickaxe,
                "c3-restart-fresh", 0, 80
            );
        CompanionMiningSettlement.BreakResult fresh = CompanionMiningSettlement.settleBreak(
            "tx:c3:restart:fresh", second.lease(), second.custody(), second.custody().revision(),
            second.actor(), 0, pickaxe, helper.getLevel(), target, before, freshMechanics
        );
        helper.assertTrue(
            fresh.applied()
                && second.actor().canonicalInventory().getItem(0).getDamageValue() == 1
                && second.actor().canonicalInventory().countItem(Items.COBBLESTONE) == 1,
            Component.literal("Only a fresh incarnation and lease may settle after restart.")
        );
        helper.assertEntityNotPresent(EntityType.ITEM);
        CompanionMiningA1EvidenceWriter.record(
            "restart_incarnation_isolation", "c3A0RestartBetweenBreakAndSettlementRejectsOldIncarnation",
            second.actor(), second.presence(), second.custody(), Map.of(
                "stale_failure", stale.outcome(),
                "fresh_settlement", Boolean.toString(fresh.applied()),
                "new_incarnation", second.presence().incarnationId(),
                "drop_items", Integer.toString(fresh.observedDropItems())
            )
        );
        helper.succeed();
    }

    @GameTest(maxTicks = 600, skyAccess = false)
    public void c3A0ModifierMatrixSettlesExactVanillaTimingAndEconomy(GameTestHelper helper) {
        Fixture fixture = fixture(helper, "modifiers", 700L);
        fixture.actor().canonicalInventory().setItem(0, Items.WOODEN_PICKAXE.getDefaultInstance());

        Trial grounded = runStoneTrial(
            helper, fixture, new BlockPos(3, 1, 3), "grounded",
            PlayerSemanticMiningProbe.MiningConditions.grounded(), 80
        );
        Trial haste = runStoneTrial(
            helper, fixture, new BlockPos(7, 1, 3), "haste-one",
            new PlayerSemanticMiningProbe.MiningConditions(true, false, 0, -1), 80
        );
        Trial fatigue = runStoneTrial(
            helper, fixture, new BlockPos(11, 1, 3), "fatigue-one",
            new PlayerSemanticMiningProbe.MiningConditions(true, false, -1, 0), 110
        );
        Trial submerged = runStoneTrial(
            helper, fixture, new BlockPos(15, 1, 3), "submerged",
            new PlayerSemanticMiningProbe.MiningConditions(true, true, -1, -1), 150
        );
        Trial airborne = runStoneTrial(
            helper, fixture, new BlockPos(19, 1, 3), "airborne",
            new PlayerSemanticMiningProbe.MiningConditions(false, false, -1, -1), 150
        );

        helper.assertTrue(
            grounded.matches(23)
                && haste.matches(19)
                && fatigue.matches(75)
                && submerged.matches(113)
                && submerged.mechanics().eyeSubmergedAtStart()
                && airborne.matches(113)
                && fixture.actor().canonicalInventory().countItem(Items.COBBLESTONE) == 5
                && fixture.actor().canonicalInventory().getItem(0).getDamageValue() == 5
                && fixture.custody().revision() == 6,
            Component.literal("C3 modifiers must use vanilla progress and settle five exact economies.")
        );
        helper.assertEntityNotPresent(EntityType.ITEM);
        CompanionMiningA1EvidenceWriter.record(
            "modifier_matrix", "c3A0ModifierMatrixSettlesExactVanillaTimingAndEconomy",
            fixture.actor(), fixture.presence(), fixture.custody(), Map.of(
                "grounded", Integer.toString(grounded.mechanics().observedActionTicks()),
                "haste_one", Integer.toString(haste.mechanics().observedActionTicks()),
                "fatigue_one", Integer.toString(fatigue.mechanics().observedActionTicks()),
                "submerged", Integer.toString(submerged.mechanics().observedActionTicks()),
                "airborne", Integer.toString(airborne.mechanics().observedActionTicks()),
                "settled_cobblestone", Integer.toString(fixture.actor().canonicalInventory().countItem(Items.COBBLESTONE))
            )
        );
        helper.succeed();
    }

    private static Trial runStoneTrial(
        GameTestHelper helper,
        Fixture fixture,
        BlockPos relative,
        String suffix,
        PlayerSemanticMiningProbe.MiningConditions conditions,
        int maximumTicks
    ) {
        helper.setBlock(relative, Blocks.STONE);
        BlockPos target = helper.absolutePos(relative);
        BlockState before = helper.getLevel().getBlockState(target);
        ItemStack canonicalTool = fixture.actor().canonicalInventory().getItem(0).copy();
        PlayerSemanticMiningProbe.ProgressiveResult mechanics =
            PlayerSemanticMiningProbe.attemptProgressive(
                helper.getLevel().getServer(), helper.getLevel(), target, canonicalTool,
                "c3-modifier-" + suffix, 0, maximumTicks, conditions
            );
        CompanionMiningSettlement.BreakResult settlement = CompanionMiningSettlement.settleBreak(
            "tx:c3:modifier:" + suffix, fixture.lease(), fixture.custody(),
            fixture.custody().revision(), fixture.actor(), 0, canonicalTool,
            helper.getLevel(), target, before, mechanics
        );
        return new Trial(mechanics, settlement);
    }

    private static Fixture fixture(GameTestHelper helper, String suffix, long lifetimeTicks) {
        SpikeCompanionEntity actor = helper.spawn(
            HelixCompanionSpikeMod.SPIKE_COMPANION, new BlockPos(1, 1, 1)
        );
        long now = helper.getLevel().getGameTime();
        CompanionPresenceRuntime presence = new CompanionPresenceRuntime(
            new CompanionPresenceRuntime.Profile(
                "companion:datdampig:c3", "account:owner:c3", "subject:owner:c3",
                "subject:beneficiary:c3", "resident.minecraft.companion-mining.v1",
                "sha256:c3-controller-under-test"
            ),
            actor, helper.getLevel(), "incarnation:c3:" + suffix,
            "connector-epoch:c3:" + suffix, now + lifetimeTicks
        );
        presence.admit("actor-lease:c3:" + suffix, "effect-lease:c3:" + suffix);
        CompanionPresenceRuntime.ActionLease lease = presence.issueAction(
            "action:c3:" + suffix, now + lifetimeTicks - 1
        );
        return new Fixture(actor, presence, lease, new CompanionInventoryCustody(presence));
    }

    private record Fixture(
        SpikeCompanionEntity actor,
        CompanionPresenceRuntime presence,
        CompanionPresenceRuntime.ActionLease lease,
        CompanionInventoryCustody custody
    ) {}

    private record Trial(
        PlayerSemanticMiningProbe.ProgressiveResult mechanics,
        CompanionMiningSettlement.BreakResult settlement
    ) {
        boolean matches(int expectedTicks) {
            return mechanics.blockDestroyed()
                && mechanics.expectedActionTicks() == expectedTicks
                && mechanics.observedActionTicks() == expectedTicks
                && mechanics.toolDamageDelta() == 1
                && settlement.applied()
                && settlement.observedDropItems() == 1;
        }
    }
}
