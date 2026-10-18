package com.casimirbot.helixcompanion.spike;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Minimal exact-prestate settlement for S0. The visible companion inventory is
 * canonical; the detached mechanics subject contributes only a measured delta.
 */
public final class CompanionMiningSettlement {
    private CompanionMiningSettlement() {}

    public record Result(
        boolean applied,
        String outcome,
        int canonicalDamageBefore,
        int canonicalDamageAfter
    ) {}

    public record BreakResult(
        boolean applied,
        String outcome,
        int observedDropEntities,
        int observedDropItems,
        boolean blockAirAfter,
        boolean toolBroke,
        CompanionInventoryCustody.Receipt custodyReceipt
    ) {}

    /**
     * Couples the already-observed normal Survival break to canonical C2
     * custody. If exact identity, revision, target prestate or capacity fails,
     * the target block is restored and the just-created vanilla drops are
     * removed so the failed attempt cannot duplicate the economy.
     */
    public static BreakResult settleBreak(
        String transactionId,
        CompanionPresenceRuntime.ActionLease lease,
        CompanionInventoryCustody custody,
        long expectedCustodyRevision,
        SpikeCompanionEntity companion,
        int toolSlot,
        ItemStack expectedToolBefore,
        ServerLevel level,
        BlockPos target,
        BlockState expectedBlockBefore,
        PlayerSemanticMiningProbe.ProgressiveResult mechanics
    ) {
        if (!mechanics.blockDestroyed() || !"none".equals(mechanics.failureCode())) {
            return new BreakResult(false, "mechanics_not_successful", 0, 0,
                level.getBlockState(target).isAir(), false, null);
        }
        if (!level.getBlockState(target).isAir()) {
            return new BreakResult(false, "target_poststate_mismatch", 0, 0, false, false, null);
        }
        List<ItemEntity> entities = level.getEntitiesOfClass(
            ItemEntity.class,
            new AABB(target).inflate(1.5D),
            entity -> entity.isAlive() && !entity.getItem().isEmpty()
        );
        List<ItemStack> drops = entities.stream().map(entity -> entity.getItem().copy()).toList();
        int dropItems = drops.stream().mapToInt(ItemStack::getCount).sum();
        try {
            CompanionInventoryCustody.Receipt receipt = custody.settleMining(
                transactionId,
                lease,
                expectedCustodyRevision,
                toolSlot,
                expectedToolBefore,
                mechanics.toolDamageDelta(),
                drops,
                () -> {
                    entities.forEach(ItemEntity::discard);
                    return true;
                }
            );
            boolean toolBroke = "mining_settlement_tool_broke".equals(receipt.operation());
            return new BreakResult(
                true,
                receipt.operation(),
                entities.size(),
                dropItems,
                level.getBlockState(target).isAir(),
                toolBroke,
                receipt
            );
        } catch (RuntimeException rejected) {
            entities.forEach(ItemEntity::discard);
            level.setBlockAndUpdate(target, expectedBlockBefore);
            String outcome = rejected instanceof CompanionInventoryCustody.CustodyException custodyFailure
                ? custodyFailure.code()
                : "mining_settlement_failed:" + rejected.getClass().getSimpleName();
            return new BreakResult(false, outcome, entities.size(), dropItems, false, false, null);
        }
    }

    public static Result settleToolWear(
        SpikeCompanionEntity companion,
        int slot,
        ItemStack expectedCanonicalBefore,
        PlayerSemanticMiningProbe.ProgressiveResult mechanics
    ) {
        SimpleContainer inventory = companion.canonicalInventory();
        if (slot < 0 || slot >= inventory.getContainerSize()) {
            return new Result(false, "slot_out_of_scope", -1, -1);
        }
        ItemStack current = inventory.getItem(slot);
        int damageBefore = current.isEmpty() ? 0 : current.getDamageValue();
        if (!ItemStack.matches(current, expectedCanonicalBefore)) {
            return new Result(
                false,
                "canonical_prestate_mismatch",
                damageBefore,
                damageBefore
            );
        }
        if (!mechanics.blockDestroyed() || !"none".equals(mechanics.failureCode())) {
            return new Result(
                false,
                "mechanics_not_successful",
                damageBefore,
                damageBefore
            );
        }
        int wear = mechanics.toolDamageDelta();
        if (wear < 0 || (wear > 0 && !current.isDamageableItem())) {
            return new Result(
                false,
                "invalid_tool_wear_delta",
                damageBefore,
                damageBefore
            );
        }
        int damageAfter = damageBefore + wear;
        if (current.isDamageableItem() && damageAfter >= current.getMaxDamage()) {
            return new Result(
                false,
                "tool_break_settlement_not_in_s0",
                damageBefore,
                damageBefore
            );
        }
        ItemStack settled = current.copy();
        settled.setDamageValue(damageAfter);
        inventory.setItem(slot, settled);
        return new Result(true, "settled", damageBefore, damageAfter);
    }
}
