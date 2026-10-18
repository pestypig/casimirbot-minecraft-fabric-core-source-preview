package com.casimirbot.helixcompanion.spike.gametest;

import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A real-client differential oracle. World and loadout construction are test
 * fixtures; the measured break is produced only by the client attack input.
 */
public final class RealClientMiningOracleGameTest implements FabricClientGameTest {
    private static final BlockPos PLAYER_BLOCK = new BlockPos(0, 5, 0);
    private static final BlockPos TARGET = new BlockPos(0, 5, -2);

    @Override
    public void runTest(ClientGameTestContext context) {
        try (TestSingleplayerContext singleplayer = context.worldBuilder()
            .adjustSettings(settings -> settings.setGameMode(
                WorldCreationUiState.SelectedGameMode.SURVIVAL
            ))
            .create()) {
            singleplayer.getClientWorld().waitForChunksDownload();
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = onlyPlayer(server.getPlayerList().getPlayers());
                ServerLevel level = (ServerLevel) player.level();
                level.setBlockAndUpdate(PLAYER_BLOCK.below(), Blocks.BEDROCK.defaultBlockState());
                level.setBlockAndUpdate(TARGET, Blocks.STONE.defaultBlockState());
                player.teleportTo(
                    PLAYER_BLOCK.getX() + 0.5D,
                    PLAYER_BLOCK.getY(),
                    PLAYER_BLOCK.getZ() + 0.5D
                );
                player.setOnGround(true);
                player.getInventory().clearContent();
                player.setItemInHand(
                    InteractionHand.MAIN_HAND,
                    Items.WOODEN_PICKAXE.getDefaultInstance()
                );
            });
            context.waitFor(client -> client.level != null
                && client.level.getBlockState(TARGET).is(Blocks.STONE)
                && client.player != null
                && client.player.getMainHandItem().is(Items.WOODEN_PICKAXE));
            context.runOnClient(client -> aimAt(client.player.getEyePosition(), TARGET, client));
            context.waitTick();
            context.runOnClient(client -> {
                if (!(client.hitResult instanceof BlockHitResult hit)
                    || !hit.getBlockPos().equals(TARGET)) {
                    throw new AssertionError(
                        "Real-client fixture crosshair did not settle on the stone target: "
                            + client.hitResult
                    );
                }
            });
            context.takeScreenshot("s0_real_client_stone_before");

            int actionTicks = 0;
            context.getInput().holdMouse(0);
            try {
                while (actionTicks < 40 && context.computeOnClient(
                    client -> !client.level.getBlockState(TARGET).isAir()
                )) {
                    context.waitTick();
                    actionTicks += 1;
                }
            } finally {
                context.getInput().releaseMouse(0);
                context.waitTick();
            }

            int observedTicks = actionTicks;
            AtomicInteger durability = new AtomicInteger(-1);
            AtomicInteger cobblestoneEvidence = new AtomicInteger(0);
            singleplayer.getServer().runOnServer(server -> {
                ServerPlayer player = onlyPlayer(server.getPlayerList().getPlayers());
                ServerLevel level = (ServerLevel) player.level();
                durability.set(player.getMainHandItem().getDamageValue());
                int inventoryCount = player.getInventory().countItem(Items.COBBLESTONE);
                int entityCount = level.getEntitiesOfClass(
                    ItemEntity.class,
                    new AABB(TARGET).inflate(3.0D),
                    item -> item.getItem().is(Items.COBBLESTONE)
                ).stream().mapToInt(item -> item.getItem().getCount()).sum();
                cobblestoneEvidence.set(inventoryCount + entityCount);
                if (!level.getBlockState(TARGET).isAir()) {
                    throw new AssertionError("Real client did not settle the stone mutation.");
                }
            });
            if (observedTicks < 22 || observedTicks > 24) {
                throw new AssertionError(
                    "Real-client stone oracle must match 23 ticks within one tick; observed="
                        + observedTicks
                );
            }
            if (durability.get() != 1) {
                throw new AssertionError(
                    "Real-client wooden pickaxe durability delta must be 1; observed="
                        + durability.get()
                );
            }
            if (cobblestoneEvidence.get() != 1) {
                throw new AssertionError(
                    "Real-client stone oracle must settle exactly one cobblestone; observed="
                        + cobblestoneEvidence.get()
                );
            }
            context.takeScreenshot("s0_real_client_stone_after");
            System.out.println(
                "HELIX_S0_REAL_CLIENT_ORACLE result=pass action_ticks="
                    + observedTicks
                    + " durability_delta=" + durability.get()
                    + " cobblestone=" + cobblestoneEvidence.get()
            );
        }
    }

    private static ServerPlayer onlyPlayer(java.util.List<ServerPlayer> players) {
        if (players.size() != 1) {
            throw new AssertionError(
                "Real-client oracle requires exactly one connected player; observed="
                    + players.size()
            );
        }
        return players.getFirst();
    }

    private static void aimAt(
        Vec3 eye,
        BlockPos target,
        net.minecraft.client.Minecraft client
    ) {
        Vec3 delta = Vec3.atCenterOf(target).subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Mth.atan2(delta.z, delta.x) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) (-(Mth.atan2(delta.y, horizontal) * Mth.RAD_TO_DEG));
        client.player.setYRot(yaw);
        client.player.setXRot(pitch);
    }
}
