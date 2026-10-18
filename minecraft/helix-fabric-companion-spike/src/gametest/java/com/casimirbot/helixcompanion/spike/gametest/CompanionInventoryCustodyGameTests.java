package com.casimirbot.helixcompanion.spike.gametest;

import com.casimirbot.helixcompanion.spike.CompanionInventoryCustody;
import com.casimirbot.helixcompanion.spike.CompanionPresenceRuntime;
import com.casimirbot.helixcompanion.spike.HelixCompanionSpikeMod;
import com.casimirbot.helixcompanion.spike.SpikeCompanionEntity;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class CompanionInventoryCustodyGameTests {
    private static final String PROFILE_HASH =
        CompanionCustodyA1EvidenceWriter.controllerArtifactHash();

    @GameTest(maxTicks = 40, skyAccess = false)
    public void c2A0ExactPickupEquipUnequipTransferAndRetry(GameTestHelper helper) {
        Fixture fixture = fixture(helper, "exact", 100L);
        SimpleContainer droppedItem = new SimpleContainer(1);
        droppedItem.setItem(0, new ItemStack(Items.IRON_INGOT, 5));
        AtomicInteger backendCommits = new AtomicInteger();

        long pickupRevision = fixture.custody().revision();
        CompanionInventoryCustody.Receipt pickup = fixture.custody().pickup(
            "tx:c2:pickup",
            fixture.lease(),
            pickupRevision,
            droppedItem,
            0,
            5,
            () -> backendCommits.incrementAndGet() == 1
        );
        CompanionInventoryCustody.Receipt pickupRetry = fixture.custody().pickup(
            "tx:c2:pickup",
            fixture.lease(),
            pickupRevision,
            droppedItem,
            0,
            5,
            () -> {
                backendCommits.incrementAndGet();
                return true;
            }
        );
        helper.assertTrue(
            droppedItem.isEmpty()
                && fixture.actor().canonicalInventory().countItem(Items.IRON_INGOT) == 5
                && pickup.sourceDelta() == -5
                && pickup.destinationDelta() == 5
                && pickupRetry.replayed()
                && backendCommits.get() == 1,
            Component.literal("Pickup delivery retry must return one receipt with one physical effect.")
        );

        fixture.actor().canonicalInventory().setItem(1, new ItemStack(Items.IRON_SWORD));
        CompanionInventoryCustody.Receipt equip = fixture.custody().equip(
            "tx:c2:equip",
            fixture.lease(),
            fixture.custody().revision(),
            1,
            EquipmentSlot.MAINHAND,
            () -> true
        );
        helper.assertTrue(
            fixture.actor().canonicalInventory().getItem(1).isEmpty()
                && fixture.actor().getMainHandItem().is(Items.IRON_SWORD)
                && equip.sourceDelta() == -1
                && equip.destinationDelta() == 1,
            Component.literal("Equip must move one exact item into the native actor slot.")
        );

        fixture.custody().unequip(
            "tx:c2:unequip",
            fixture.lease(),
            fixture.custody().revision(),
            EquipmentSlot.MAINHAND,
            1,
            () -> true
        );
        SimpleContainer ownerInventory = new SimpleContainer(9);
        CompanionInventoryCustody.Receipt transfer = fixture.custody().transferToOwner(
            "tx:c2:transfer",
            fixture.lease(),
            fixture.custody().revision(),
            0,
            ownerInventory,
            2,
            3,
            "bound_owner_inventory",
            true,
            () -> true
        );
        helper.assertTrue(
            fixture.actor().canonicalInventory().countItem(Items.IRON_INGOT) == 2
                && ownerInventory.countItem(Items.IRON_INGOT) == 3
                && transfer.atomic()
                && transfer.controlsReleased()
                && !transfer.miningAuthority()
                && !transfer.craftingAuthority()
                && !transfer.combatAuthority()
                && !transfer.worldAuthority()
                && !transfer.answerAuthority()
                && !transfer.terminalAuthority(),
            Component.literal("Transfer must conserve items and retain every broader authority negative.")
        );
        CompanionPresenceRuntime.CleanupReceipt cleanup = fixture.presence().cleanup(
            "cleanup:c2:exact", "completed", true
        );
        CompanionCustodyA1EvidenceWriter.record(
            "pickup_equip_unequip_transfer_retry",
            "c2A0ExactPickupEquipUnequipTransferAndRetry",
            pickup.stateHashBefore(),
            transfer.stateHashAfter(),
            fixture.custody(),
            fixture.presence(),
            cleanup
        );
        helper.succeed();
    }

    @GameTest(maxTicks = 40, skyAccess = false)
    public void c2A0DeniedSlotsContainersStaleRevisionAndConflict(GameTestHelper helper) {
        Fixture fixture = fixture(helper, "denials", 100L);
        fixture.actor().canonicalInventory().setItem(0, new ItemStack(Items.COBBLESTONE, 4));
        SimpleContainer ownerInventory = new SimpleContainer(9);
        String before = fixture.custody().stateHash(ownerInventory);

        assertCode(helper, "companion_container_not_admitted", () ->
            fixture.custody().transferToOwner(
                "tx:c2:container-denied", fixture.lease(), fixture.custody().revision(),
                0, ownerInventory, 0, 1, "nearby_chest", false, () -> true
            )
        );
        assertCode(helper, "companion_slot_denied", () ->
            fixture.custody().transferToOwner(
                "tx:c2:slot-denied", fixture.lease(), fixture.custody().revision(),
                99, ownerInventory, 0, 1, "bound_owner_inventory", true, () -> true
            )
        );
        assertCode(helper, "companion_custody_revision_stale", () ->
            fixture.custody().transferToOwner(
                "tx:c2:stale", fixture.lease(), fixture.custody().revision() + 1,
                0, ownerInventory, 0, 1, "bound_owner_inventory", true, () -> true
            )
        );

        long revision = fixture.custody().revision();
        CompanionInventoryCustody.Receipt transfer = fixture.custody().transferToOwner(
            "tx:c2:conflict", fixture.lease(), revision, 0, ownerInventory, 0,
            1, "bound_owner_inventory", true, () -> true
        );
        assertCode(helper, "companion_custody_idempotency_conflict", () ->
            fixture.custody().transferToOwner(
                "tx:c2:conflict", fixture.lease(), revision, 0, ownerInventory, 0,
                2, "bound_owner_inventory", true, () -> true
            )
        );
        helper.assertTrue(
            fixture.actor().canonicalInventory().countItem(Items.COBBLESTONE) == 3
                && ownerInventory.countItem(Items.COBBLESTONE) == 1,
            Component.literal("Denied and conflicting calls must not mutate canonical custody.")
        );
        CompanionPresenceRuntime.CleanupReceipt cleanup = fixture.presence().cleanup(
            "cleanup:c2:denials", "completed", true
        );
        CompanionCustodyA1EvidenceWriter.record(
            "denied_slots_containers_stale_revision_conflict",
            "c2A0DeniedSlotsContainersStaleRevisionAndConflict",
            before,
            transfer.stateHashAfter(),
            fixture.custody(),
            fixture.presence(),
            cleanup
        );
        helper.succeed();
    }

    @GameTest(maxTicks = 40, skyAccess = false)
    public void c2A0BackendFailureRollsBackAndReleaseBlocksLateEffects(GameTestHelper helper) {
        Fixture fixture = fixture(helper, "rollback", 100L);
        fixture.actor().canonicalInventory().setItem(0, new ItemStack(Items.DIAMOND, 2));
        SimpleContainer ownerInventory = new SimpleContainer(9);
        String before = fixture.custody().stateHash(ownerInventory);

        assertCode(helper, "companion_custody_backend_failure", () ->
            fixture.custody().transferToOwner(
                "tx:c2:backend-failure", fixture.lease(), fixture.custody().revision(),
                0, ownerInventory, 0, 2, "bound_owner_inventory", true, () -> false
            )
        );
        helper.assertTrue(
            before.equals(fixture.custody().stateHash(ownerInventory))
                && fixture.actor().canonicalInventory().countItem(Items.DIAMOND) == 2
                && ownerInventory.isEmpty(),
            Component.literal("Backend failure must restore both sides exactly.")
        );

        CompanionInventoryCustody.ReleaseReceipt release = fixture.custody().release("disconnect");
        assertCode(helper, "companion_custody_released", () ->
            fixture.custody().transferToOwner(
                "tx:c2:late", fixture.lease(), fixture.custody().revision(),
                0, ownerInventory, 0, 1, "bound_owner_inventory", true, () -> true
            )
        );
        helper.assertTrue(
            release.released()
                && release.lateEffectCount() == 0
                && release.duplicateEffectCount() == 0
                && fixture.actor().canonicalInventory().countItem(Items.DIAMOND) == 2,
            Component.literal("Disconnect release must reject late effects without item loss.")
        );
        CompanionPresenceRuntime.CleanupReceipt cleanup = fixture.presence().cleanup(
            "cleanup:c2:rollback", "disconnect", true
        );
        CompanionCustodyA1EvidenceWriter.record(
            "backend_rollback_disconnect_release",
            "c2A0BackendFailureRollsBackAndReleaseBlocksLateEffects",
            before,
            fixture.custody().stateHash(ownerInventory),
            fixture.custody(),
            fixture.presence(),
            cleanup
        );
        helper.succeed();
    }

    @GameTest(maxTicks = 60, skyAccess = false)
    public void c2A0RestartAndConfiguredDeathPolicyPreserveOneEconomy(GameTestHelper helper) {
        Fixture first = fixture(helper, "restart-1", 120L);
        first.actor().canonicalInventory().setItem(0, new ItemStack(Items.BREAD, 6));
        first.actor().canonicalInventory().setItem(1, new ItemStack(Items.IRON_HELMET));
        String before = first.custody().stateHash(null);
        first.custody().equip(
            "tx:c2:helmet", first.lease(), first.custody().revision(), 1,
            EquipmentSlot.HEAD, () -> true
        );
        CompanionInventoryCustody.PersistentState saved = first.custody().snapshotForRestart(
            CompanionInventoryCustody.DeathPolicy.KEEP
        );
        first.custody().release("restart");
        first.presence().cleanup("cleanup:c2:restart-1", "restart", true);

        Fixture second = fixture(helper, "restart-2", 120L);
        second.custody().restoreAfterRestart(saved);
        helper.assertTrue(
            second.actor().canonicalInventory().countItem(Items.BREAD) == 6
                && second.actor().getItemBySlot(EquipmentSlot.HEAD).is(Items.IRON_HELMET)
                && second.custody().revision() == saved.revision() + 1,
            Component.literal("Restart must restore one canonical inventory and rotate its revision.")
        );

        CompanionInventoryCustody.DeathReceipt keep = second.custody().settleDeath(
            CompanionInventoryCustody.DeathPolicy.KEEP
        );
        helper.assertTrue(
            keep.drops().isEmpty()
                && keep.restartState().inventory().stream().mapToInt(ItemStack::getCount).sum() == 6
                && second.actor().canonicalInventory().countItem(Items.BREAD) == 6,
            Component.literal("KEEP death policy must retain custody and produce no drops.")
        );

        CompanionInventoryCustody.DeathReceipt drop = second.custody().settleDeath(
            CompanionInventoryCustody.DeathPolicy.DROP
        );
        int droppedCount = drop.drops().stream().mapToInt(ItemStack::getCount).sum();
        helper.assertTrue(
            second.actor().canonicalInventory().isEmpty()
                && second.actor().getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                && droppedCount == 7
                && !drop.miningAuthority()
                && !drop.craftingAuthority()
                && !drop.combatAuthority()
                && !drop.worldAuthority()
                && !drop.answerAuthority()
                && !drop.terminalAuthority(),
            Component.literal("DROP death policy must clear custody once and return the exact economy.")
        );
        CompanionPresenceRuntime.CleanupReceipt cleanup = second.presence().cleanup(
            "cleanup:c2:restart-2", "death", true
        );
        CompanionCustodyA1EvidenceWriter.record(
            "restart_keep_drop_death_policy",
            "c2A0RestartAndConfiguredDeathPolicyPreserveOneEconomy",
            before,
            second.custody().stateHash(null),
            second.custody(),
            second.presence(),
            cleanup
        );
        helper.succeed();
    }

    private static Fixture fixture(GameTestHelper helper, String suffix, long lifetimeTicks) {
        SpikeCompanionEntity actor = helper.spawn(
            HelixCompanionSpikeMod.SPIKE_COMPANION,
            new BlockPos(1, 1, 1)
        );
        long now = helper.getLevel().getGameTime();
        CompanionPresenceRuntime presence = new CompanionPresenceRuntime(
            profile(), actor, helper.getLevel(), "incarnation:c2:" + suffix,
            "connector-epoch:c2:" + suffix, now + lifetimeTicks
        );
        presence.admit("actor-lease:c2:" + suffix, "effect-lease:c2:" + suffix);
        CompanionPresenceRuntime.ActionLease lease = presence.issueAction(
            "action:c2:" + suffix, now + lifetimeTicks - 1
        );
        return new Fixture(actor, presence, lease, new CompanionInventoryCustody(presence));
    }

    private static CompanionPresenceRuntime.Profile profile() {
        return new CompanionPresenceRuntime.Profile(
            "companion:datdampig:c2",
            "account:owner:c2",
            "subject:owner:c2",
            "subject:beneficiary:c2",
            CompanionInventoryCustody.PROFILE_ID,
            PROFILE_HASH
        );
    }

    private static void assertCode(GameTestHelper helper, String expected, Runnable operation) {
        try {
            operation.run();
            helper.fail(Component.literal("Expected custody failure " + expected));
        } catch (CompanionInventoryCustody.CustodyException failure) {
            helper.assertTrue(
                expected.equals(failure.code()),
                Component.literal("Expected " + expected + " but received " + failure.code())
            );
        }
    }

    private record Fixture(
        SpikeCompanionEntity actor,
        CompanionPresenceRuntime presence,
        CompanionPresenceRuntime.ActionLease lease,
        CompanionInventoryCustody custody
    ) {}
}
