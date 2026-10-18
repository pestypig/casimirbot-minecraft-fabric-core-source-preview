package com.casimirbot.helixcompanion.spike;

import com.mojang.authlib.GameProfile;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.tags.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Candidate B feasibility adapter. It constructs a detached, locally identified
 * server-player-shaped mechanics subject, never registers it as a second online
 * actor, and reports failures instead of synthesizing a network connection.
 */
public final class PlayerSemanticMiningProbe {
    private static final String PROFILE_NAMESPACE = "helix-companion-spike:";

    private PlayerSemanticMiningProbe() {}

    public record Result(
        UUID profileId,
        float vanillaProgressPerTick,
        int vanillaMinimumTicks,
        boolean blockDestroyed,
        boolean networkConnectionPresent,
        boolean boundedPacketSinkInstalled,
        int discardedClientUpdatePackets,
        boolean toolMayDestroy,
        boolean blockActionRestricted,
        int toolDamageBefore,
        int toolDamageAfter,
        MiningEventProbe.Counts eventCounts,
        String failureCode,
        String failureLocation
    ) {}

    public record ProgressiveResult(
        float vanillaProgressPerTick,
        int expectedActionTicks,
        int observedActionTicks,
        boolean canInteractAtStart,
        boolean worldMayInteractAtStart,
        boolean blockActionRestrictedAtStart,
        boolean eyeSubmergedAtStart,
        int onlinePlayersBefore,
        int onlinePlayersAfter,
        boolean mechanicsSubjectRegistered,
        boolean aborted,
        boolean blockDestroyed,
        boolean toolBroke,
        int toolDamageDelta,
        MiningEventProbe.Counts eventCounts,
        int discardedClientUpdatePackets,
        String failureCode
    ) {}

    /**
     * Explicit mechanics inputs for a matched mining trial. Negative effect
     * amplifiers mean absent; zero is the first vanilla effect level.
     */
    public record MiningConditions(
        boolean onGround,
        boolean submergedEyes,
        int hasteAmplifier,
        int miningFatigueAmplifier
    ) {
        public static MiningConditions grounded() {
            return new MiningConditions(true, false, -1, -1);
        }
    }

    @FunctionalInterface
    public interface ProgressGuard {
        ProgressGuard ALLOW = (actionTick, subject, level, target) -> null;

        /** Returns null while current, otherwise one typed interruption code. */
        String rejectionCode(int actionTick, ServerPlayer subject, ServerLevel level, BlockPos target);
    }

    public static Result attempt(
        MinecraftServer server,
        ServerLevel level,
        BlockPos target,
        ItemStack tool,
        String companionId,
        boolean cancelThroughFabricEvent,
        boolean installBoundedPacketSink
    ) {
        UUID profileId = UUID.nameUUIDFromBytes(
            (PROFILE_NAMESPACE + companionId).getBytes(StandardCharsets.UTF_8)
        );
        ServerPlayer subject = new ServerPlayer(
            server,
            level,
            new GameProfile(profileId, "HelixSpike"),
            ClientInformation.createDefault()
        );
        subject.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        subject.setPos(target.getX() + 0.5D, target.getY() + 1.0D, target.getZ() + 2.5D);
        subject.setOnGround(true);
        ItemStack workingTool = tool.copy();
        subject.setItemInHand(InteractionHand.MAIN_HAND, workingTool);
        BoundedPacketSinkConnection packetSink = installBoundedPacketSink
            ? new BoundedPacketSinkConnection(64)
            : null;
        if (packetSink != null) {
            subject.connection = new ServerGamePacketListenerImpl(
                server,
                packetSink,
                subject,
                CommonListenerCookie.createInitial(subject.getGameProfile(), false)
            );
        }

        BlockState state = level.getBlockState(target);
        float progress = state.getDestroyProgress(subject, level, target);
        int minimumTicks = progress <= 0.0F
            ? Integer.MAX_VALUE
            : (int) Math.ceil(1.0F / progress);
        boolean connectionPresent = subject.connection != null;
        boolean toolMayDestroy = workingTool.canDestroyBlock(state, level, target, subject);
        boolean actionRestricted = subject.blockActionRestricted(
            level,
            target,
            GameType.SURVIVAL
        );
        int damageBefore = workingTool.getDamageValue();
        MiningEventProbe.Scope events = MiningEventProbe.open(
            target,
            cancelThroughFabricEvent
        );
        try {
            boolean destroyed = subject.gameMode.destroyBlock(target);
            return new Result(
                profileId,
                progress,
                minimumTicks,
                destroyed,
                connectionPresent,
                packetSink != null,
                packetSink == null ? 0 : packetSink.discardedPackets(),
                toolMayDestroy,
                actionRestricted,
                damageBefore,
                workingTool.getDamageValue(),
                events.counts(),
                "none",
                "none"
            );
        } catch (RuntimeException failure) {
            return new Result(
                profileId,
                progress,
                minimumTicks,
                false,
                connectionPresent,
                packetSink != null,
                packetSink == null ? 0 : packetSink.discardedPackets(),
                toolMayDestroy,
                actionRestricted,
                damageBefore,
                workingTool.getDamageValue(),
                events.counts(),
                "detached_player_interaction_failed:" + failure.getClass().getSimpleName(),
                failure.getStackTrace().length == 0
                    ? "unknown"
                    : failure.getStackTrace()[0].toString()
            );
        } finally {
            events.close();
        }
    }

    public static ProgressiveResult attemptProgressive(
        MinecraftServer server,
        ServerLevel level,
        BlockPos target,
        ItemStack tool,
        String companionId,
        int abortAfterActionTicks,
        int maximumActionTicks
    ) {
        return attemptProgressive(
            server,
            level,
            target,
            tool,
            companionId,
            abortAfterActionTicks,
            maximumActionTicks,
            MiningConditions.grounded(),
            ProgressGuard.ALLOW
        );
    }

    public static ProgressiveResult attemptProgressive(
        MinecraftServer server,
        ServerLevel level,
        BlockPos target,
        ItemStack tool,
        String companionId,
        int abortAfterActionTicks,
        int maximumActionTicks,
        MiningConditions conditions
    ) {
        return attemptProgressive(
            server, level, target, tool, companionId, abortAfterActionTicks,
            maximumActionTicks, conditions, ProgressGuard.ALLOW
        );
    }

    public static ProgressiveResult attemptProgressive(
        MinecraftServer server,
        ServerLevel level,
        BlockPos target,
        ItemStack tool,
        String companionId,
        int abortAfterActionTicks,
        int maximumActionTicks,
        MiningConditions conditions,
        ProgressGuard progressGuard
    ) {
        int onlinePlayersBefore = server.getPlayerList().getPlayerCount();
        UUID profileId = UUID.nameUUIDFromBytes(
            (PROFILE_NAMESPACE + companionId).getBytes(StandardCharsets.UTF_8)
        );
        ServerPlayer subject = new ServerPlayer(
            server,
            level,
            new GameProfile(profileId, "HelixSpike"),
            ClientInformation.createDefault()
        );
        subject.gameMode.changeGameModeForPlayer(GameType.SURVIVAL);
        subject.setPos(target.getX() + 0.5D, target.getY(), target.getZ() + 2.5D);
        subject.setOnGround(conditions.onGround());
        ItemStack workingTool = tool.copy();
        subject.setItemInHand(InteractionHand.MAIN_HAND, workingTool);
        BoundedPacketSinkConnection packetSink = new BoundedPacketSinkConnection(64);
        subject.connection = new ServerGamePacketListenerImpl(
            server,
            packetSink,
            subject,
            CommonListenerCookie.createInitial(subject.getGameProfile(), false)
        );
        if (conditions.submergedEyes()) {
            level.setBlockAndUpdate(
                BlockPos.containing(subject.getEyePosition()),
                Blocks.WATER.defaultBlockState()
            );
            subject.baseTick();
        }
        if (conditions.hasteAmplifier() >= 0) {
            subject.addEffect(new MobEffectInstance(
                MobEffects.HASTE,
                maximumActionTicks + 20,
                conditions.hasteAmplifier(),
                false,
                false
            ));
        }
        if (conditions.miningFatigueAmplifier() >= 0) {
            subject.addEffect(new MobEffectInstance(
                MobEffects.MINING_FATIGUE,
                maximumActionTicks + 20,
                conditions.miningFatigueAmplifier(),
                false,
                false
            ));
        }

        BlockState state = level.getBlockState(target);
        float progress = state.getDestroyProgress(subject, level, target);
        int expectedTicks = progress <= 0.0F
            ? Integer.MAX_VALUE
            : (int) Math.ceil(1.0F / progress);
        int damageBefore = workingTool.getDamageValue();
        boolean canInteractAtStart = subject.canInteractWithBlock(target, 1.0D);
        boolean worldMayInteractAtStart = level.mayInteract(subject, target);
        boolean restrictedAtStart = subject.blockActionRestricted(
            level,
            target,
            GameType.SURVIVAL
        );
        boolean eyeSubmergedAtStart = subject.isEyeInFluid(FluidTags.WATER);
        MiningEventProbe.Scope events = MiningEventProbe.open(target, false);
        int actionTicks = 1;
        boolean aborted = false;
        String interruptionCode = null;
        try {
            subject.gameMode.handleBlockBreakAction(
                target,
                ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                Direction.UP,
                level.getMaxY(),
                0
            );
            while (!level.getBlockState(target).isAir() && actionTicks < maximumActionTicks) {
                interruptionCode = progressGuard.rejectionCode(
                    actionTicks, subject, level, target
                );
                if (interruptionCode != null && !interruptionCode.isBlank()) {
                    subject.gameMode.handleBlockBreakAction(
                        target,
                        ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                        Direction.UP,
                        level.getMaxY(),
                        1
                    );
                    aborted = true;
                    break;
                }
                if (abortAfterActionTicks > 0 && actionTicks >= abortAfterActionTicks) {
                    subject.gameMode.handleBlockBreakAction(
                        target,
                        ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK,
                        Direction.UP,
                        level.getMaxY(),
                        1
                    );
                    aborted = true;
                    break;
                }
                subject.gameMode.tick();
                actionTicks += 1;
                if (actionTicks >= expectedTicks) {
                    subject.gameMode.handleBlockBreakAction(
                        target,
                        ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                        Direction.UP,
                        level.getMaxY(),
                        2
                    );
                }
            }
            if (aborted) {
                for (int settleTick = 0; settleTick < 3; settleTick++) {
                    subject.gameMode.tick();
                }
            }
            return new ProgressiveResult(
                progress,
                expectedTicks,
                actionTicks,
                canInteractAtStart,
                worldMayInteractAtStart,
                restrictedAtStart,
                eyeSubmergedAtStart,
                onlinePlayersBefore,
                server.getPlayerList().getPlayerCount(),
                server.getPlayerList().getPlayer(profileId) != null,
                aborted,
                level.getBlockState(target).isAir(),
                !tool.isEmpty() && workingTool.isEmpty(),
                !tool.isEmpty() && workingTool.isEmpty()
                    ? tool.getMaxDamage() - damageBefore
                    : workingTool.getDamageValue() - damageBefore,
                events.counts(),
                packetSink.discardedPackets(),
                interruptionCode == null ? "none" : interruptionCode
            );
        } catch (RuntimeException failure) {
            return new ProgressiveResult(
                progress,
                expectedTicks,
                actionTicks,
                canInteractAtStart,
                worldMayInteractAtStart,
                restrictedAtStart,
                eyeSubmergedAtStart,
                onlinePlayersBefore,
                server.getPlayerList().getPlayerCount(),
                server.getPlayerList().getPlayer(profileId) != null,
                aborted,
                level.getBlockState(target).isAir(),
                !tool.isEmpty() && workingTool.isEmpty(),
                !tool.isEmpty() && workingTool.isEmpty()
                    ? tool.getMaxDamage() - damageBefore
                    : workingTool.getDamageValue() - damageBefore,
                events.counts(),
                packetSink.discardedPackets(),
                "progressive_player_interaction_failed:"
                    + failure.getClass().getSimpleName()
            );
        } finally {
            events.close();
        }
    }
}
