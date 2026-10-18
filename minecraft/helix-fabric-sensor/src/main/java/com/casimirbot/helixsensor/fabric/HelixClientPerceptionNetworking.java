package com.casimirbot.helixsensor.fabric;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

/** Registers the finite client-to-server UI observation channel. */
final class HelixClientPerceptionNetworking {
    private HelixClientPerceptionNetworking() {}

    static void register() {
        PayloadTypeRegistry.playC2S().register(
            ClientPerceptionPayload.TYPE,
            ClientPerceptionPayload.CODEC
        );
        ServerPlayNetworking.registerGlobalReceiver(
            ClientPerceptionPayload.TYPE,
            (payload, context) -> FabricClientPerceptionBridge.publish(
                context.player().getUUID(),
                new FabricClientPerceptionBridge.Snapshot(
                    payload.clientGameTick(),
                    context.player().level().getGameTime(),
                    payload.screenState(),
                    payload.screenKind(),
                    payload.inputActivity()
                )
            )
        );
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            FabricClientPerceptionBridge.clear(handler.getPlayer().getUUID())
        );
    }
}
