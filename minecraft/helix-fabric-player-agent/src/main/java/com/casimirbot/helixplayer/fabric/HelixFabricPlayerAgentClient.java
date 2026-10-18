package com.casimirbot.helixplayer.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import com.casimirbot.helixsensor.pairing.ConnectorPairingClient;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class HelixFabricPlayerAgentClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger(
        "HelixFabricPlayerAgent"
    );
    private Minecraft minecraft;
    private volatile PlayerActionRuntime runtime;
    private volatile PlayerInteractionClient interactionClient;
    private final RealtimeTexturePackDebugRenderState realtimeTexturePackDebugRenderState =
        new RealtimeTexturePackDebugRenderState();
    private final AtomicBoolean connectorOperation = new AtomicBoolean(false);
    private int pairingInboxTicks;
    private int diagnosticInboxTicks;
    private int autoJoinInboxTicks;
    private int autoJoinTitleTicks;
    private PlayerAutoJoinInbox.AutoJoinRequest pendingAutoJoin;
    private volatile PlayerActionDiagnosticInbox.Scope diagnosticInboxScope =
        PlayerActionDiagnosticInbox.Scope.DISABLED;

    @Override
    public void onInitializeClient() {
        minecraft = Minecraft.getInstance();
        PlayerActionConfigLoader.LoadedConfig initialConfig = PlayerActionConfigLoader.loadAll(LOGGER);
        replaceRuntime(initialConfig.action(), initialConfig.interaction());
        restoreDiagnosticInboxScope();
        PlayerActionClientCommands.register(this);
        HudElementRegistry.attachElementBefore(
            VanillaHudElements.CHAT,
            ResourceLocation.fromNamespaceAndPath(
                "helix_fabric_player_agent",
                "realtime_texture_pack_debug"
            ),
            (graphics, tickCounter) -> renderRealtimeTexturePackDebugHud(graphics)
        );
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            runtime.tick();
            pollPairingInbox();
            pollDiagnosticInbox();
            pollAutoJoinInbox();
            maybeAutoJoin(client);
        });
        WorldRenderEvents.START.register(context -> {
            PlayerActionRuntime active = runtime;
            if (active != null) active.renderFrame(System.nanoTime());
            // Presentation-only debug seam. It observes an already-admitted
            // envelope and deliberately performs no texture/provider effect.
            realtimeTexturePackDebugRenderState.renderFrame(
                System.nanoTime(),
                System.currentTimeMillis()
            );
            updateRealtimeTexturePackTargetMatch();
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            PlayerActionRuntime active = runtime;
            if (active != null) active.close();
            PlayerInteractionClient interaction = interactionClient;
            if (interaction != null) interaction.close();
            realtimeTexturePackDebugRenderState.reset();
        });
        LOGGER.info(initialConfig.action().ready()
            ? "Helix Fabric Player Agent loaded with a separately paired action authority."
            : "Helix Fabric Player Agent loaded disabled; a separate action pairing is required before capabilities are advertised.");
    }

    PlayerActionController controllerForIntegration() {
        return runtime == null ? null : runtime.controllerForIntegration();
    }

    void admitRealtimeTexturePackDebugProjection(Map<String, Object> projection) {
        realtimeTexturePackDebugRenderState.admit(projection, System.currentTimeMillis());
    }

    private void updateRealtimeTexturePackTargetMatch() {
        if (!realtimeTexturePackDebugRenderState.active() || minecraft.level == null ||
            !(minecraft.hitResult instanceof BlockHitResult hit)) {
            realtimeTexturePackDebugRenderState.clearTargetMatch();
            return;
        }
        BlockPos position = hit.getBlockPos();
        BlockState state = minecraft.level.getBlockState(position);
        String blockType = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        String blockState = canonicalBlockState(state);
        String dimensionId = minecraft.level.dimension().location().toString();
        try {
            realtimeTexturePackDebugRenderState.matchTargetBlock(
                dimensionId,
                position.getX(), position.getY(), position.getZ(),
                blockType, blockState, System.currentTimeMillis()
            );
        } catch (IllegalArgumentException invalidProjectionMatch) {
            realtimeTexturePackDebugRenderState.clearTargetMatch();
        }
    }

    private void renderRealtimeTexturePackDebugHud(GuiGraphics graphics) {
        realtimeTexturePackDebugRenderState.hudSnapshot(System.currentTimeMillis()).ifPresent(snapshot -> {
            graphics.fill(6, 6, 226, 34, 0xB0000000);
            graphics.drawString(
                minecraft.font,
                "Realtime Texture Pack debug | instances " + snapshot.materialInstanceCount() +
                    " | slots " + snapshot.distinctVariationSlotCount(),
                10, 10, 0xFFFFFFFF, false
            );
            String target = snapshot.targetedVariationSlot() == null
                ? "target: no matched projected block"
                : "target: " + snapshot.targetedMaterialFamily() + " / variation " + snapshot.targetedVariationSlot();
            graphics.drawString(minecraft.font, target, 10, 22, 0xFF80E8FF, false);
        });
    }

    private static String canonicalBlockState(BlockState state) {
        if (state.getValues().isEmpty()) return "default";
        return state.getValues().entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().getName()))
            .map(entry -> entry.getKey().getName() + ":" +
                String.valueOf(entry.getValue()).toLowerCase(Locale.ROOT))
            .collect(Collectors.joining("."));
    }

    void pairAsync(String code, String endpointOverride) {
        if (!connectorOperation.compareAndSet(false, true)) {
            message("Another Helix player connector operation is already running.");
            return;
        }
        String pairingEndpoint = endpointOverride == null || endpointOverride.isBlank()
            ? PlayerActionConfigLoader.pairingEndpoint(LOGGER)
            : endpointOverride.trim();
        message("Helix is redeeming the one-time player-action pairing code...");
        CompletableFuture
            .supplyAsync(() -> {
                try {
                    String nonce = PlayerActionConfigLoader.loadOrCreateNonce(
                        code,
                        pairingEndpoint,
                        LOGGER
                    );
                    try (ConnectorPairingClient client = new ConnectorPairingClient()) {
                        return client.redeem(
                            pairingEndpoint,
                            code,
                            nonce,
                            PlayerActionConfig.DOMAIN_ADAPTER,
                            PlayerActionRuntime.ADAPTER_VERSION
                        );
                    }
                } catch (ConnectorPairingClient.PairingException error) {
                    throw new PlayerPairingFailure(error.code() + ": " + error.getMessage());
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new PlayerPairingFailure("Player-action pairing was interrupted.");
                } catch (java.io.IOException error) {
                    throw new PlayerPairingFailure("The local player-action pairing state could not be saved.");
                }
            })
            .whenComplete((paired, error) -> minecraft.execute(() -> {
                connectorOperation.set(false);
                if (error != null) {
                    Throwable cause = unwrap(error);
                    message(cause == null ? "Player-action pairing failed." : cause.getMessage());
                    return;
                }
                try {
                    PlayerActionConfigLoader.LoadedConfig config = PlayerActionConfigLoader.savePairedAction(
                        paired,
                        LOGGER
                    );
                    replaceRuntime(config.action(), config.interaction());
                    message(paired.replayed()
                        ? "Helix player-action pairing recovered and embodiment restarted."
                        : "Helix player-action pairing succeeded. The client companion is publishing its capabilities.");
                } catch (java.io.IOException failure) {
                    message("Helix paired the action authority, but the client configuration could not be saved. Retry the same code during its replay window.");
                }
            }));
    }

    void showStatus() {
        String action = runtime == null ? "Helix player embodiment is not initialized." : runtime.statusText();
        String interaction = interactionClient == null
            ? "Helix in-game Ask is not initialized."
            : interactionClient.statusText();
        message(action + " " + interaction);
    }

    void ask(String prompt) {
        PlayerInteractionClient interaction = interactionClient;
        if (interaction == null) {
            message("Helix in-game Ask is not initialized. Pair the player connector again.");
            return;
        }
        message("Helix accepted your room-bound request and is reasoning...");
        interaction.ask(prompt, answer -> minecraft.execute(() -> message(answer)));
    }

    void cancelAsk() {
        message(interactionClient == null
            ? "Helix in-game Ask is not initialized."
            : interactionClient.cancel());
    }

    void showDiagnosticStatus() {
        String runtimeStatus = runtime == null
            ? "Helix player embodiment is not initialized."
            : runtime.localDiagnosticStatusText();
        message(
            runtimeStatus + " Local diagnostic inbox scope: " +
            diagnosticInboxScope.wireName() + "."
        );
    }

    void armDiagnosticGuardian(int durationSeconds) {
        message(runtime == null
            ? "Helix player embodiment is not initialized."
            : runtime.armLocalViabilityGuardian(durationSeconds));
    }

    void disarmDiagnosticGuardian() {
        message(runtime == null
            ? "Helix player embodiment is not initialized."
            : runtime.disarmLocalViabilityGuardian());
    }

    void showDiagnosticGuardianStatus() {
        message(runtime == null
            ? "Helix player embodiment is not initialized."
            : runtime.localViabilityGuardianStatusText());
    }

    void enableDiagnosticInbox(PlayerActionDiagnosticInbox.Scope scope) {
        if (scope == PlayerActionDiagnosticInbox.Scope.DISABLED) {
            disableDiagnosticInbox();
            return;
        }
        try {
            // Clear before enabling so a request staged without the player's
            // prior opt-in can never become executable afterward.
            PlayerActionDiagnosticInbox.clearDefault();
            PlayerActionDiagnosticInbox.persistScope(scope);
            diagnosticInboxScope = scope;
            message(scope == PlayerActionDiagnosticInbox.Scope.FULL
                ? "Helix full local control is enabled across client restarts until disabled or emergency-stopped. Typed interaction, inventory, and world-mutation actions may execute."
                : "Helix movement-only local control is enabled across client restarts until disabled or emergency-stopped. Interaction, inventory, and world-mutation actions remain blocked.");
        } catch (IOException error) {
            diagnosticInboxScope = PlayerActionDiagnosticInbox.Scope.DISABLED;
            message("Helix could not safely save and enable local control.");
        }
    }

    void disableDiagnosticInbox() {
        diagnosticInboxScope = PlayerActionDiagnosticInbox.Scope.DISABLED;
        PlayerActionRuntime active = runtime;
        if (active != null) active.cancelLocalDiagnostic();
        try {
            PlayerActionDiagnosticInbox.clearDefault();
            PlayerActionDiagnosticInbox.persistScope(
                PlayerActionDiagnosticInbox.Scope.DISABLED
            );
            message("Helix local control is disabled across restarts and pending requests were cleared.");
        } catch (IOException error) {
            message("Helix disabled local control for this process, but could not clear all saved local state.");
        }
    }

    void startDiagnosticWalk(String direction, int durationMs, boolean sprint) {
        if (!List.of("forward", "back", "left", "right").contains(direction)) {
            message("Diagnostic walk direction must be forward, back, left, or right.");
            return;
        }
        message(runtime == null
            ? "Helix player embodiment is not initialized."
            : runtime.startLocalDiagnostic(
                "walk",
                Map.of(
                    "direction", direction,
                    "duration_ms", durationMs,
                    "sprint", sprint
                ),
                Math.max(20, (durationMs + 49L) / 50L + 20L)
            ));
    }

    void startDiagnosticJump(int count) {
        message(runtime == null
            ? "Helix player embodiment is not initialized."
            : runtime.startLocalDiagnostic(
                "jump",
                Map.of("count", count),
                Math.max(40, count * 30L)
            ));
    }

    void startDiagnosticRelativeLook(double yawDelta, double pitchDelta) {
        message(runtime == null
            ? "Helix player embodiment is not initialized."
            : runtime.startLocalDiagnostic(
                "look_at",
                Map.of(
                    "target", Map.of(
                        "target_kind", "relative_rotation",
                        "yaw_delta_degrees", yawDelta,
                        "pitch_delta_degrees", pitchDelta
                    ),
                    "max_turn_degrees_per_tick", 18.0
                ),
                100
            ));
    }

    void cancelDiagnostic() {
        message(runtime == null
            ? "Helix player embodiment is not initialized."
            : runtime.cancelLocalDiagnostic());
    }

    void emergencyStop() {
        diagnosticInboxScope = PlayerActionDiagnosticInbox.Scope.DISABLED;
        try {
            PlayerActionDiagnosticInbox.clearDefault();
            PlayerActionDiagnosticInbox.persistScope(
                PlayerActionDiagnosticInbox.Scope.DISABLED
            );
        } catch (IOException error) {
            LOGGER.warn("Could not clear all saved Helix local-control state during emergency stop.");
        }
        if (runtime != null) runtime.localEmergencyStop("The player invoked the local emergency stop.");
        message("Helix released every client control, disabled the direct diagnostic inbox, and latched the local emergency stop.");
    }

    private void restoreDiagnosticInboxScope() {
        try {
            // Never execute a request that was staged while the client was not
            // running, even when the operator has chosen a persistent scope.
            PlayerActionDiagnosticInbox.clearDefault();
            diagnosticInboxScope = PlayerActionDiagnosticInbox.loadPersistedScope();
            if (diagnosticInboxScope != PlayerActionDiagnosticInbox.Scope.DISABLED) {
                LOGGER.info(
                    "Helix restored {} local control from the operator's saved preference; disable and emergency-stop remain available.",
                    diagnosticInboxScope.wireName()
                );
            }
        } catch (IOException error) {
            diagnosticInboxScope = PlayerActionDiagnosticInbox.Scope.DISABLED;
            LOGGER.warn("Helix could not safely restore the saved local-control preference.");
        }
    }

    void disconnectLocal() {
        try {
            PlayerActionConfigLoader.clear(LOGGER);
            replaceRuntime(PlayerActionConfig.disabled(), PlayerInteractionConfig.disabled());
            message("The local Helix player connector is disconnected. Revoke or expire the room authority separately if it should no longer exist.");
        } catch (java.io.IOException error) {
            message("The local player connector could not clear its saved pairing state.");
        }
    }

    private void pollPairingInbox() {
        pairingInboxTicks = (pairingInboxTicks + 1) % 20;
        if (pairingInboxTicks != 0 || connectorOperation.get()) return;
        try {
            PlayerActionPairingInbox.PollResult result =
                PlayerActionPairingInbox.consumeDefault(System.currentTimeMillis());
            if (result.request() != null) {
                pairAsync(result.request().code(), result.request().endpointOverride());
            } else if (!result.failureCode().isBlank()) {
                LOGGER.warn(
                    "Ignored a local Helix player-pairing inbox entry: {}.",
                    result.failureCode()
                );
            }
        } catch (IOException error) {
            LOGGER.warn("Could not claim the local Helix player-pairing inbox.");
        }
    }

    private void pollDiagnosticInbox() {
        PlayerActionDiagnosticInbox.Scope scope = diagnosticInboxScope;
        if (scope == PlayerActionDiagnosticInbox.Scope.DISABLED) return;
        diagnosticInboxTicks = (diagnosticInboxTicks + 1) % 20;
        if (diagnosticInboxTicks != 0) return;
        try {
            PlayerActionDiagnosticInbox.PollResult result =
                PlayerActionDiagnosticInbox.consumeDefault(
                    System.currentTimeMillis(),
                    scope
                );
            if (result.request() != null) {
                PlayerActionDiagnosticInbox.DiagnosticRequest request = result.request();
                PlayerActionRuntime active = runtime;
                message(active == null
                    ? "Helix player embodiment is not initialized."
                    : active.startLocalDiagnostic(
                        request.actionKind(),
                        request.arguments(),
                        request.maxDurationTicks(),
                        request.controlEngine(),
                        request.requestId()
                    ));
            } else if (!result.failureCode().isBlank()) {
                LOGGER.warn(
                    "Ignored a local Helix direct-diagnostic inbox entry: {}.",
                    result.failureCode()
                );
                message("Helix rejected a staged direct diagnostic request (" +
                    result.failureCode() + ").");
            }
        } catch (IOException error) {
            LOGGER.warn("Could not claim the local Helix direct-diagnostic inbox.");
        }
    }

    private void pollAutoJoinInbox() {
        if (minecraft.player != null || minecraft.level != null || pendingAutoJoin != null) {
            return;
        }
        autoJoinInboxTicks = (autoJoinInboxTicks + 1) % 20;
        if (autoJoinInboxTicks != 0) return;
        try {
            PlayerAutoJoinInbox.PollResult result =
                PlayerAutoJoinInbox.consumeDefault(System.currentTimeMillis());
            if (result.request() != null) {
                pendingAutoJoin = result.request();
                autoJoinTitleTicks = 0;
                LOGGER.info(
                    "Helix accepted a one-shot loopback auto-join request for {}.",
                    result.request().address()
                );
            } else if (!result.failureCode().isBlank()) {
                LOGGER.warn(
                    "Ignored a local Helix auto-join inbox entry: {}.",
                    result.failureCode()
                );
            }
        } catch (IOException error) {
            LOGGER.warn("Could not claim the local Helix auto-join inbox.");
        }
    }

    private void maybeAutoJoin(Minecraft client) {
        PlayerAutoJoinInbox.AutoJoinRequest request = pendingAutoJoin;
        boolean safeConnectionScreen =
            client.screen instanceof TitleScreen ||
            client.screen instanceof DisconnectedScreen ||
            client.screen instanceof JoinMultiplayerScreen;
        if (
            request == null || client.player != null || client.level != null ||
            !safeConnectionScreen
        ) return;
        if (++autoJoinTitleTicks < 20) return;

        // Consume once before connecting. A failed connection returns control
        // to the user and never becomes an unattended reconnect loop.
        pendingAutoJoin = null;
        autoJoinTitleTicks = 0;
        ServerAddress address = ServerAddress.parseString(request.address());
        ServerData data = new ServerData(
            "Helix loopback",
            request.address(),
            ServerData.Type.OTHER
        );
        LOGGER.info("Helix is opening Minecraft's native loopback connection screen.");
        ConnectScreen.startConnecting(
            client.screen,
            client,
            address,
            data,
            false,
            null
        );
    }

    private void replaceRuntime(
        PlayerActionConfig config,
        PlayerInteractionConfig interactionConfig
    ) {
        PlayerActionRuntime prior = runtime;
        if (prior != null) prior.close();
        PlayerInteractionClient priorInteraction = interactionClient;
        if (priorInteraction != null) priorInteraction.close();
        runtime = new PlayerActionRuntime(config, minecraft, LOGGER, this::message);
        interactionClient = new PlayerInteractionClient(interactionConfig);
        runtime.start();
    }

    private void message(String text) {
        if (minecraft != null && minecraft.gui != null) {
            minecraft.gui.getChat().addMessage(Component.literal(text));
        }
        LOGGER.info("{}", text);
    }

    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (
            current instanceof java.util.concurrent.CompletionException &&
            current.getCause() != null
        ) current = current.getCause();
        return current;
    }

    private static final class PlayerPairingFailure extends RuntimeException {
        PlayerPairingFailure(String message) {
            super(message);
        }
    }
}
