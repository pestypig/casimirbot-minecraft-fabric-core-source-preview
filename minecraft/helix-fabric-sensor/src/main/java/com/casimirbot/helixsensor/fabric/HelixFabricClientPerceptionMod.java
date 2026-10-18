package com.casimirbot.helixsensor.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Captures only bounded client UI/input facts; it performs no input or action. */
public final class HelixFabricClientPerceptionMod implements ClientModInitializer {
    private long lastSentTick = Long.MIN_VALUE;
    private String lastSemanticState = "";

    @Override
    public void onInitializeClient() {
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            lastSentTick = Long.MIN_VALUE;
            lastSemanticState = "";
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.level == null || client.player == null) {
                return;
            }
            boolean inputActivity =
                client.options.keyUp.isDown() ||
                client.options.keyDown.isDown() ||
                client.options.keyLeft.isDown() ||
                client.options.keyRight.isDown() ||
                client.options.keyJump.isDown() ||
                client.options.keyShift.isDown() ||
                client.options.keySprint.isDown() ||
                client.options.keyAttack.isDown() ||
                client.options.keyUse.isDown();
            long clientTick = client.level.getGameTime();
            String screenState = client.screen == null ? "closed" : "open";
            String screenKind = client.screen == null
                ? "none"
                : client.screen.getClass().getSimpleName();
            String semanticState = screenState + "\n" + screenKind + "\n" + inputActivity;
            if (
                !ClientPlayNetworking.canSend(ClientPerceptionPayload.TYPE) ||
                (semanticState.equals(lastSemanticState) && clientTick - lastSentTick < 5L)
            ) {
                return;
            }
            ClientPlayNetworking.send(
                new ClientPerceptionPayload(
                    clientTick,
                    screenState,
                    screenKind,
                    inputActivity
                )
            );
            lastSentTick = clientTick;
            lastSemanticState = semanticState;
        });
    }
}
