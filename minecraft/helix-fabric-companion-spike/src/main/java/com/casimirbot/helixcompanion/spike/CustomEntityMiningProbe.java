package com.casimirbot.helixcompanion.spike;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Candidate A baseline. This intentionally exposes its unsupported player-only
 * modifiers instead of claiming parity from a custom entity.
 */
public final class CustomEntityMiningProbe {
    private CustomEntityMiningProbe() {}

    public record Result(
        float progressPerTick,
        int minimumTicks,
        boolean correctToolForDrops,
        boolean playerModifiersSupported
    ) {}

    public static Result baseline(Level level, BlockPos pos, ItemStack tool) {
        BlockState state = level.getBlockState(pos);
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F) {
            return new Result(0.0F, Integer.MAX_VALUE, false, false);
        }
        boolean correctTool = !state.requiresCorrectToolForDrops()
            || tool.isCorrectToolForDrops(state);
        float toolSpeed = tool.getDestroySpeed(state);
        float divisor = correctTool ? 30.0F : 100.0F;
        float progress = hardness == 0.0F ? 1.0F : toolSpeed / hardness / divisor;
        int ticks = progress <= 0.0F ? Integer.MAX_VALUE : (int) Math.ceil(1.0F / progress);
        return new Result(progress, ticks, correctTool, false);
    }
}
