package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

final class NativeFabricWorkflowEnginePlacementGeometryTest {
    @Test
    void clicksTheSupportingBlocksExposedFaceInsteadOfThePlacementCellCenter() {
        BlockPos support = new BlockPos(10, 63, 20);

        assertEquals(
            new Vec3(10.5, 64.0, 20.5),
            NativeFabricWorkflowEngine.supportFaceHitLocation(support, Direction.UP)
        );
        assertEquals(
            new Vec3(11.0, 63.5, 20.5),
            NativeFabricWorkflowEngine.supportFaceHitLocation(support, Direction.EAST)
        );
        assertEquals(
            new Vec3(10.0, 63.5, 20.5),
            NativeFabricWorkflowEngine.supportFaceHitLocation(support, Direction.WEST)
        );
    }

    @Test
    void itemUseTriesTheAdmittedSupportFaceBeforeTheAirPovFallback() {
        assertEquals(
            java.util.List.of("use_item_on", "use_item"),
            NativeFabricWorkflowEngine.placementInteractionOrder("item_use")
        );
        assertEquals(
            java.util.List.of("use_item_on"),
            NativeFabricWorkflowEngine.placementInteractionOrder("block_item")
        );
    }

    @Test
    void mapsPlayerInventoryIndexesToTheServersInventoryMenuBeforeHotbarSwap() {
        assertEquals(36, NativeFabricInventoryControls.menuSlotForInventoryIndex(0));
        assertEquals(44, NativeFabricInventoryControls.menuSlotForInventoryIndex(8));
        assertEquals(9, NativeFabricInventoryControls.menuSlotForInventoryIndex(9));
        assertEquals(35, NativeFabricInventoryControls.menuSlotForInventoryIndex(35));
        assertThrows(
            IllegalArgumentException.class,
            () -> NativeFabricInventoryControls.menuSlotForInventoryIndex(36)
        );
    }

    @Test
    void onlyGuiOwningWorkflowsCloseTheirScreenOnSettlement() {
        assertTrue(NativeFabricWorkflowEngine.ownsScreen("craft"));
        assertTrue(NativeFabricWorkflowEngine.ownsScreen("inventory_transfer"));
        assertFalse(NativeFabricWorkflowEngine.ownsScreen("look_at"));
        assertFalse(NativeFabricWorkflowEngine.ownsScreen("mine"));
    }
}
