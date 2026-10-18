package com.casimirbot.helixplayer.fabric;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ClickType;

/** Normal client inventory operations for already-admitted player actions. */
final class NativeFabricInventoryControls {
    private NativeFabricInventoryControls() {}

    static void selectForMainHand(
        Minecraft minecraft,
        LocalPlayer player,
        int inventoryIndex
    ) {
        Inventory inventory = player.getInventory();
        if (inventoryIndex < 0 || inventoryIndex >= inventory.getContainerSize()) {
            throw new IllegalArgumentException("The requested inventory slot is invalid.");
        }
        if (inventoryIndex < Inventory.getSelectionSize()) {
            inventory.setSelectedSlot(inventoryIndex);
            return;
        }
        int selectedHotbarSlot = inventory.getSelectedSlot();
        minecraft.gameMode.handleInventoryMouseClick(
            player.inventoryMenu.containerId,
            menuSlotForInventoryIndex(inventoryIndex),
            selectedHotbarSlot,
            ClickType.SWAP,
            player
        );
    }

    static int menuSlotForInventoryIndex(int inventoryIndex) {
        if (inventoryIndex < 0 || inventoryIndex >= 36) {
            throw new IllegalArgumentException("The inventory index must be 0-35.");
        }
        return inventoryIndex < Inventory.getSelectionSize()
            ? 36 + inventoryIndex
            : inventoryIndex;
    }
}
