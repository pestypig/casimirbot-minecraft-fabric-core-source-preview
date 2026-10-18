package com.casimirbot.helixcompanion.spike;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

/**
 * C2's private, actor-bound inventory/equipment transaction arbiter.
 *
 * <p>The visible companion inventory and its native equipment slots are the
 * only canonical state. Every mutation validates the C0/C1 action identity and
 * one exact custody revision, snapshots all touched state, applies once, and
 * either commits an immutable receipt or restores the complete prestate. A
 * delivery retry returns the original receipt and never executes the effect a
 * second time.
 */
public final class CompanionInventoryCustody {
    public static final String PROFILE_ID = "resident.minecraft.companion-custody.v1";
    private static final List<EquipmentSlot> EQUIPMENT_SLOTS = List.of(
        EquipmentSlot.MAINHAND,
        EquipmentSlot.OFFHAND,
        EquipmentSlot.HEAD,
        EquipmentSlot.CHEST,
        EquipmentSlot.LEGS,
        EquipmentSlot.FEET
    );

    private final CompanionPresenceRuntime presence;
    private final SpikeCompanionEntity actor;
    private final Map<String, SettledTransaction> settled = new HashMap<>();
    private long revision;
    private boolean released;

    public CompanionInventoryCustody(CompanionPresenceRuntime presence) {
        this.presence = Objects.requireNonNull(presence);
        this.actor = presence.actor();
        this.revision = 1L;
    }

    public Receipt pickup(
        String transactionId,
        CompanionPresenceRuntime.ActionLease lease,
        long expectedRevision,
        Container source,
        int sourceSlot,
        int requestedCount,
        BooleanSupplier backendCommit
    ) {
        requireSlot(source, sourceSlot, "pickup_source_slot_denied");
        ItemStack sourceBefore = source.getItem(sourceSlot).copy();
        String fingerprint = fingerprint(
            "pickup",
            expectedRevision,
            sourceSlot,
            -1,
            sourceBefore,
            requestedCount,
            "drop_source"
        );
        Receipt replay = replay(transactionId, fingerprint);
        if (replay != null) return replay;
        validate(transactionId, lease, expectedRevision, requestedCount);
        if (sourceBefore.isEmpty() || requestedCount > sourceBefore.getCount()) {
            throw new CustodyException("pickup_source_insufficient");
        }
        int destinationSlot = firstAcceptingSlot(actor.canonicalInventory(), sourceBefore);
        if (destinationSlot < 0) throw new CustodyException("companion_inventory_full");

        return transact(
            transactionId,
            fingerprint,
            "pickup",
            source,
            sourceSlot,
            actor.canonicalInventory(),
            destinationSlot,
            requestedCount,
            null,
            backendCommit
        );
    }

    public Receipt transferToOwner(
        String transactionId,
        CompanionPresenceRuntime.ActionLease lease,
        long expectedRevision,
        int companionSlot,
        Container ownerInventory,
        int ownerSlot,
        int requestedCount,
        String containerScope,
        boolean containerAdmitted,
        BooleanSupplier backendCommit
    ) {
        if (!containerAdmitted || !"bound_owner_inventory".equals(containerScope)) {
            throw new CustodyException("companion_container_not_admitted");
        }
        requireSlot(actor.canonicalInventory(), companionSlot, "companion_slot_denied");
        requireSlot(ownerInventory, ownerSlot, "owner_slot_denied");
        ItemStack sourceBefore = actor.canonicalInventory().getItem(companionSlot).copy();
        String fingerprint = fingerprint(
            "transfer_to_owner",
            expectedRevision,
            companionSlot,
            ownerSlot,
            sourceBefore,
            requestedCount,
            containerScope
        );
        Receipt replay = replay(transactionId, fingerprint);
        if (replay != null) return replay;
        validate(transactionId, lease, expectedRevision, requestedCount);
        return transact(
            transactionId,
            fingerprint,
            "transfer_to_owner",
            actor.canonicalInventory(),
            companionSlot,
            ownerInventory,
            ownerSlot,
            requestedCount,
            null,
            backendCommit
        );
    }

    public Receipt equip(
        String transactionId,
        CompanionPresenceRuntime.ActionLease lease,
        long expectedRevision,
        int companionSlot,
        EquipmentSlot equipmentSlot,
        BooleanSupplier backendCommit
    ) {
        requireEquipmentSlot(equipmentSlot);
        requireSlot(actor.canonicalInventory(), companionSlot, "companion_slot_denied");
        ItemStack inventoryBefore = actor.canonicalInventory().getItem(companionSlot).copy();
        ItemStack equipmentBefore = actor.getItemBySlot(equipmentSlot).copy();
        String fingerprint = fingerprint(
            "equip:" + equipmentSlot.getName(),
            expectedRevision,
            companionSlot,
            equipmentSlot.ordinal(),
            ItemStack.EMPTY,
            inventoryBefore.getCount(),
            equipmentSlot.getName()
        );
        Receipt replay = replay(transactionId, fingerprint);
        if (replay != null) return replay;
        validate(transactionId, lease, expectedRevision, 1);
        if (inventoryBefore.isEmpty()) throw new CustodyException("equip_source_empty");
        if (!equipmentBefore.isEmpty()) throw new CustodyException("equipment_slot_occupied");
        if (!actor.canUseSlot(equipmentSlot)) throw new CustodyException("equipment_slot_denied");

        StateSnapshot before = snapshot(null);
        String beforeHash = stateHash(null);
        try {
            ItemStack equipped = inventoryBefore.copyWithCount(1);
            ItemStack remainder = inventoryBefore.copyWithCount(inventoryBefore.getCount() - 1);
            actor.canonicalInventory().setItem(companionSlot, remainder);
            actor.setItemSlot(equipmentSlot, equipped);
            requireBackendCommit(backendCommit);
            return commit(
                transactionId,
                fingerprint,
                "equip",
                companionSlot,
                equipmentSlot.ordinal(),
                equipped,
                1,
                beforeHash,
                stateHash(null)
            );
        } catch (RuntimeException failure) {
            restore(before, null);
            throw rollbackFailure(failure, beforeHash, stateHash(null));
        }
    }

    public Receipt unequip(
        String transactionId,
        CompanionPresenceRuntime.ActionLease lease,
        long expectedRevision,
        EquipmentSlot equipmentSlot,
        int companionSlot,
        BooleanSupplier backendCommit
    ) {
        requireEquipmentSlot(equipmentSlot);
        requireSlot(actor.canonicalInventory(), companionSlot, "companion_slot_denied");
        ItemStack equipmentBefore = actor.getItemBySlot(equipmentSlot).copy();
        String fingerprint = fingerprint(
            "unequip:" + equipmentSlot.getName(),
            expectedRevision,
            equipmentSlot.ordinal(),
            companionSlot,
            ItemStack.EMPTY,
            equipmentBefore.getCount(),
            equipmentSlot.getName()
        );
        Receipt replay = replay(transactionId, fingerprint);
        if (replay != null) return replay;
        validate(transactionId, lease, expectedRevision, 1);
        if (equipmentBefore.isEmpty()) throw new CustodyException("equipment_slot_empty");
        ensureCanMerge(actor.canonicalInventory(), companionSlot, equipmentBefore, 1);

        StateSnapshot before = snapshot(null);
        String beforeHash = stateHash(null);
        try {
            merge(actor.canonicalInventory(), companionSlot, equipmentBefore, 1);
            actor.setItemSlot(equipmentSlot, ItemStack.EMPTY);
            requireBackendCommit(backendCommit);
            return commit(
                transactionId,
                fingerprint,
                "unequip",
                equipmentSlot.ordinal(),
                companionSlot,
                equipmentBefore,
                1,
                beforeHash,
                stateHash(null)
            );
        } catch (RuntimeException failure) {
            restore(before, null);
            throw rollbackFailure(failure, beforeHash, stateHash(null));
        }
    }

    /**
     * Atomically settles one already-completed, player-semantic Survival break
     * into the canonical companion inventory. The caller owns rollback of the
     * world prestate if this transaction is rejected; this method owns all
     * canonical tool/drop state and its exact custody revision.
     */
    public Receipt settleMining(
        String transactionId,
        CompanionPresenceRuntime.ActionLease lease,
        long expectedRevision,
        int toolSlot,
        ItemStack expectedToolBefore,
        int toolDamageDelta,
        List<ItemStack> drops,
        BooleanSupplier backendCommit
    ) {
        Objects.requireNonNull(expectedToolBefore);
        Objects.requireNonNull(drops);
        if (toolSlot < -1 || toolSlot >= actor.canonicalInventory().getContainerSize()) {
            throw new CustodyException("mining_tool_slot_denied");
        }
        String dropIdentity = drops.stream()
            .map(CompanionInventoryCustody::stackIdentity)
            .sorted()
            .reduce("", (left, right) -> left + ';' + right);
        String transactionFingerprint = "mining|" + expectedRevision + '|' + toolSlot
            + '|' + stackIdentity(expectedToolBefore) + '|' + toolDamageDelta + '|' + dropIdentity;
        Receipt replay = replay(transactionId, transactionFingerprint);
        if (replay != null) return replay;
        validate(transactionId, lease, expectedRevision, 1);

        ItemStack currentTool = toolSlot < 0
            ? ItemStack.EMPTY
            : actor.canonicalInventory().getItem(toolSlot).copy();
        if (!ItemStack.matches(currentTool, expectedToolBefore)) {
            throw new CustodyException("canonical_mining_tool_prestate_mismatch");
        }
        if (toolDamageDelta < 0 || (toolDamageDelta > 0 && !currentTool.isDamageableItem())) {
            throw new CustodyException("mining_tool_wear_invalid");
        }

        SimpleContainer settledInventory = new SimpleContainer(actor.canonicalInventory().getContainerSize());
        restoreContainer(settledInventory, copyContainer(actor.canonicalInventory()));
        boolean toolBroke = false;
        if (toolSlot >= 0 && toolDamageDelta > 0) {
            ItemStack settledTool = currentTool.copy();
            int damageAfter = settledTool.getDamageValue() + toolDamageDelta;
            toolBroke = damageAfter >= settledTool.getMaxDamage();
            if (toolBroke) settledTool = ItemStack.EMPTY;
            else settledTool.setDamageValue(damageAfter);
            settledInventory.setItem(toolSlot, settledTool);
        }
        int dropCount = 0;
        for (ItemStack drop : drops) {
            if (drop == null || drop.isEmpty()) continue;
            ItemStack remainder = drop.copy();
            dropCount += remainder.getCount();
            for (int slot = 0; slot < settledInventory.getContainerSize() && !remainder.isEmpty(); slot++) {
                ItemStack existing = settledInventory.getItem(slot);
                if (!settledInventory.canPlaceItem(slot, remainder)) continue;
                if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, remainder)) continue;
                int room = remainder.getMaxStackSize() - (existing.isEmpty() ? 0 : existing.getCount());
                if (room <= 0) continue;
                int moved = Math.min(room, remainder.getCount());
                merge(settledInventory, slot, remainder, moved);
                remainder.shrink(moved);
            }
            if (!remainder.isEmpty()) throw new CustodyException("companion_inventory_full");
        }

        StateSnapshot before = snapshot(null);
        String beforeHash = stateHash(null);
        try {
            restoreContainer(actor.canonicalInventory(), copyContainer(settledInventory));
            requireBackendCommit(backendCommit);
            long beforeRevision = revision;
            revision++;
            Receipt receipt = new Receipt(
                transactionId,
                toolBroke ? "mining_settlement_tool_broke" : "mining_settlement",
                beforeRevision,
                revision,
                toolSlot,
                -1,
                "drops:" + dropIdentity,
                -toolDamageDelta,
                dropCount,
                beforeHash,
                stateHash(null),
                false,
                true,
                true,
                true,
                false,
                false,
                false,
                false,
                false
            );
            settled.put(transactionId, new SettledTransaction(transactionFingerprint, receipt));
            return receipt;
        } catch (RuntimeException failure) {
            restore(before, null);
            throw rollbackFailure(failure, beforeHash, stateHash(null));
        }
    }

    public PersistentState snapshotForRestart(DeathPolicy deathPolicy) {
        if (released) throw new CustodyException("companion_custody_released");
        return new PersistentState(
            PROFILE_ID,
            deathPolicy,
            copyContainer(actor.canonicalInventory()),
            copyEquipment(),
            revision
        );
    }

    public void restoreAfterRestart(PersistentState state) {
        if (!PROFILE_ID.equals(state.profileId())) {
            throw new CustodyException("custody_persistence_profile_mismatch");
        }
        if (state.revision() < 1L) throw new CustodyException("custody_persistence_revision_invalid");
        restoreContainer(actor.canonicalInventory(), state.inventory());
        restoreEquipment(state.equipment());
        revision = state.revision() + 1L;
        settled.clear();
    }

    public DeathReceipt settleDeath(DeathPolicy policy) {
        if (released) throw new CustodyException("companion_custody_released");
        List<ItemStack> inventory = copyContainer(actor.canonicalInventory());
        Map<EquipmentSlot, ItemStack> equipment = copyEquipment();
        List<ItemStack> drops = new ArrayList<>();
        if (policy == DeathPolicy.DROP) {
            inventory.stream().filter(stack -> !stack.isEmpty()).forEach(stack -> drops.add(stack.copy()));
            equipment.values().stream().filter(stack -> !stack.isEmpty()).forEach(stack -> drops.add(stack.copy()));
            clearCanonicalState();
        }
        revision++;
        return new DeathReceipt(
            policy,
            new PersistentState(PROFILE_ID, policy, inventory, equipment, revision),
            List.copyOf(drops),
            stateHash(null),
            false,
            false,
            false,
            false,
            false,
            false
        );
    }

    public ReleaseReceipt release(String reason) {
        if (!released) {
            released = true;
            revision++;
        }
        return new ReleaseReceipt(reason, revision, true, settled.size(), 0, 0);
    }

    public long revision() {
        return revision;
    }

    public String stateHash(Container external) {
        StringBuilder canonical = new StringBuilder("inventory[");
        appendContainer(canonical, actor.canonicalInventory());
        canonical.append("]equipment[");
        for (EquipmentSlot slot : EQUIPMENT_SLOTS) {
            canonical.append(slot.getName()).append('=').append(stackIdentity(actor.getItemBySlot(slot))).append(';');
        }
        if (external != null) {
            canonical.append("]external[");
            appendContainer(canonical, external);
        }
        canonical.append(']');
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                canonical.toString().getBytes(StandardCharsets.UTF_8)
            );
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("sha256_unavailable", impossible);
        }
    }

    private Receipt transact(
        String transactionId,
        String fingerprint,
        String operation,
        Container source,
        int sourceSlot,
        Container destination,
        int destinationSlot,
        int requestedCount,
        EquipmentSlot unusedEquipmentSlot,
        BooleanSupplier backendCommit
    ) {
        ItemStack moved = source.getItem(sourceSlot).copyWithCount(requestedCount);
        ensureCanMerge(destination, destinationSlot, moved, requestedCount);
        StateSnapshot before = snapshot(source == actor.canonicalInventory() || destination == actor.canonicalInventory()
            ? (source == actor.canonicalInventory() ? destination : source)
            : source);
        Container external = source == actor.canonicalInventory() ? destination : source;
        String beforeHash = stateHash(external);
        try {
            ItemStack sourceRemainder = source.getItem(sourceSlot).copy();
            sourceRemainder.shrink(requestedCount);
            source.setItem(sourceSlot, sourceRemainder);
            merge(destination, destinationSlot, moved, requestedCount);
            requireBackendCommit(backendCommit);
            return commit(
                transactionId,
                fingerprint,
                operation,
                sourceSlot,
                destinationSlot,
                moved,
                requestedCount,
                beforeHash,
                stateHash(external)
            );
        } catch (RuntimeException failure) {
            restore(before, external);
            throw rollbackFailure(failure, beforeHash, stateHash(external));
        }
    }

    private Receipt commit(
        String transactionId,
        String fingerprint,
        String operation,
        int sourceSlot,
        int destinationSlot,
        ItemStack moved,
        int count,
        String beforeHash,
        String afterHash
    ) {
        long beforeRevision = revision;
        revision++;
        Receipt receipt = new Receipt(
            transactionId,
            operation,
            beforeRevision,
            revision,
            sourceSlot,
            destinationSlot,
            stackIdentity(moved.copyWithCount(1)),
            -count,
            count,
            beforeHash,
            afterHash,
            false,
            true,
            true,
            false,
            false,
            false,
            false,
            false,
            false
        );
        settled.put(transactionId, new SettledTransaction(fingerprint, receipt));
        return receipt;
    }

    private void validate(
        String transactionId,
        CompanionPresenceRuntime.ActionLease lease,
        long expectedRevision,
        int requestedCount
    ) {
        requireText(transactionId, "transaction_id_required");
        if (released) throw new CustodyException("companion_custody_released");
        CompanionPresenceRuntime.ActionCheck action = presence.checkAction(
            Objects.requireNonNull(lease),
            actor.level().getGameTime()
        );
        if (!action.current()) throw new CustodyException(action.reason());
        if (expectedRevision != revision) throw new CustodyException("companion_custody_revision_stale");
        if (requestedCount <= 0) throw new CustodyException("custody_count_invalid");
    }

    private Receipt replay(String transactionId, String fingerprint) {
        requireText(transactionId, "transaction_id_required");
        SettledTransaction prior = settled.get(transactionId);
        if (prior == null) return null;
        if (!prior.fingerprint().equals(fingerprint)) {
            throw new CustodyException("companion_custody_idempotency_conflict");
        }
        return prior.receipt().asReplay();
    }

    private StateSnapshot snapshot(Container external) {
        return new StateSnapshot(
            copyContainer(actor.canonicalInventory()),
            copyEquipment(),
            external == null ? List.of() : copyContainer(external)
        );
    }

    private void restore(StateSnapshot state, Container external) {
        restoreContainer(actor.canonicalInventory(), state.inventory());
        restoreEquipment(state.equipment());
        if (external != null) restoreContainer(external, state.external());
    }

    private Map<EquipmentSlot, ItemStack> copyEquipment() {
        Map<EquipmentSlot, ItemStack> copy = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentSlot slot : EQUIPMENT_SLOTS) copy.put(slot, actor.getItemBySlot(slot).copy());
        return copy;
    }

    private void restoreEquipment(Map<EquipmentSlot, ItemStack> equipment) {
        for (EquipmentSlot slot : EQUIPMENT_SLOTS) {
            actor.setItemSlot(slot, equipment.getOrDefault(slot, ItemStack.EMPTY).copy());
        }
    }

    private void clearCanonicalState() {
        actor.canonicalInventory().clearContent();
        for (EquipmentSlot slot : EQUIPMENT_SLOTS) actor.setItemSlot(slot, ItemStack.EMPTY);
    }

    private static List<ItemStack> copyContainer(Container container) {
        List<ItemStack> copy = new ArrayList<>(container.getContainerSize());
        for (int index = 0; index < container.getContainerSize(); index++) {
            copy.add(container.getItem(index).copy());
        }
        return copy;
    }

    private static void restoreContainer(Container container, List<ItemStack> contents) {
        if (contents.size() != container.getContainerSize()) {
            throw new CustodyException("custody_persistence_size_mismatch");
        }
        for (int index = 0; index < contents.size(); index++) {
            container.setItem(index, contents.get(index).copy());
        }
    }

    private static int firstAcceptingSlot(Container destination, ItemStack stack) {
        for (int slot = 0; slot < destination.getContainerSize(); slot++) {
            try {
                ensureCanMerge(destination, slot, stack, stack.getCount());
                return slot;
            } catch (CustodyException ignored) {
                // Search remains bounded by the canonical inventory size.
            }
        }
        return -1;
    }

    private static void ensureCanMerge(Container destination, int slot, ItemStack moved, int count) {
        requireSlot(destination, slot, "destination_slot_denied");
        ItemStack existing = destination.getItem(slot);
        if (!destination.canPlaceItem(slot, moved)) throw new CustodyException("destination_slot_denied");
        if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, moved)) {
            throw new CustodyException("destination_item_mismatch");
        }
        int existingCount = existing.isEmpty() ? 0 : existing.getCount();
        if (existingCount + count > moved.getMaxStackSize()) {
            throw new CustodyException("destination_capacity_exceeded");
        }
    }

    private static void merge(Container destination, int slot, ItemStack moved, int count) {
        ItemStack existing = destination.getItem(slot);
        if (existing.isEmpty()) {
            destination.setItem(slot, moved.copyWithCount(count));
        } else {
            ItemStack merged = existing.copy();
            merged.grow(count);
            destination.setItem(slot, merged);
        }
    }

    private static void requireSlot(Container container, int slot, String code) {
        if (container == null || slot < 0 || slot >= container.getContainerSize()) {
            throw new CustodyException(code);
        }
    }

    private static void requireEquipmentSlot(EquipmentSlot slot) {
        if (slot == null || !EQUIPMENT_SLOTS.contains(slot)) {
            throw new CustodyException("equipment_slot_denied");
        }
    }

    private static void requireBackendCommit(BooleanSupplier backendCommit) {
        if (backendCommit == null || !backendCommit.getAsBoolean()) {
            throw new CustodyException("companion_custody_backend_failure");
        }
    }

    private static CustodyException rollbackFailure(RuntimeException failure, String beforeHash, String afterHash) {
        if (!beforeHash.equals(afterHash)) throw new CustodyException("companion_custody_rollback_failed");
        if (failure instanceof CustodyException custodyFailure) return custodyFailure;
        return new CustodyException("companion_custody_backend_failure");
    }

    private static String fingerprint(
        String operation,
        long expectedRevision,
        int sourceSlot,
        int destinationSlot,
        ItemStack item,
        int count,
        String scope
    ) {
        // The item is implicit in the exact admitted source slot and custody
        // revision. It is deliberately excluded because a successful physical
        // mutation changes that slot before a delivery retry reaches us.
        return operation + '|' + expectedRevision + '|' + sourceSlot + '|' + destinationSlot
            + '|' + count + '|' + scope;
    }

    private static String stackIdentity(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        return BuiltInRegistries.ITEM.getKey(stack.getItem()) + "@" + stack.getCount()
            + "#" + stack.getDamageValue() + ":" + stack.getComponentsPatch().hashCode();
    }

    private static void appendContainer(StringBuilder target, Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            target.append(slot).append('=').append(stackIdentity(container.getItem(slot))).append(';');
        }
    }

    private static void requireText(String value, String code) {
        if (value == null || value.isBlank()) throw new CustodyException(code);
    }

    public enum DeathPolicy {
        KEEP,
        DROP
    }

    public record PersistentState(
        String profileId,
        DeathPolicy deathPolicy,
        List<ItemStack> inventory,
        Map<EquipmentSlot, ItemStack> equipment,
        long revision
    ) {
        public PersistentState {
            inventory = inventory.stream().map(ItemStack::copy).toList();
            Map<EquipmentSlot, ItemStack> copied = new EnumMap<>(EquipmentSlot.class);
            equipment.forEach((slot, stack) -> copied.put(slot, stack.copy()));
            equipment = Map.copyOf(copied);
        }
    }

    public record Receipt(
        String transactionId,
        String operation,
        long revisionBefore,
        long revisionAfter,
        int sourceSlot,
        int destinationSlot,
        String itemIdentity,
        int sourceDelta,
        int destinationDelta,
        String stateHashBefore,
        String stateHashAfter,
        boolean replayed,
        boolean atomic,
        boolean controlsReleased,
        boolean miningAuthority,
        boolean craftingAuthority,
        boolean combatAuthority,
        boolean worldAuthority,
        boolean answerAuthority,
        boolean terminalAuthority
    ) {
        public Receipt asReplay() {
            return new Receipt(
                transactionId, operation, revisionBefore, revisionAfter, sourceSlot,
                destinationSlot, itemIdentity, sourceDelta, destinationDelta,
                stateHashBefore, stateHashAfter, true, atomic, controlsReleased,
                miningAuthority, craftingAuthority, combatAuthority, worldAuthority,
                answerAuthority, terminalAuthority
            );
        }
    }

    public record DeathReceipt(
        DeathPolicy policy,
        PersistentState restartState,
        List<ItemStack> drops,
        String canonicalStateHashAfter,
        boolean miningAuthority,
        boolean craftingAuthority,
        boolean combatAuthority,
        boolean worldAuthority,
        boolean answerAuthority,
        boolean terminalAuthority
    ) {}

    public record ReleaseReceipt(
        String reason,
        long finalRevision,
        boolean released,
        int settledTransactionCount,
        int lateEffectCount,
        int duplicateEffectCount
    ) {}

    private record SettledTransaction(String fingerprint, Receipt receipt) {}

    private record StateSnapshot(
        List<ItemStack> inventory,
        Map<EquipmentSlot, ItemStack> equipment,
        List<ItemStack> external
    ) {}

    public static final class CustodyException extends RuntimeException {
        private final String code;

        public CustodyException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
