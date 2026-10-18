package com.casimirbot.helixsensor.fabric;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Bounded, observation-only client UI state sent to the logical server. */
public record ClientPerceptionPayload(
    long clientGameTick,
    String screenState,
    String screenKind,
    boolean inputActivity
) implements CustomPacketPayload {
    static final int MAX_SCREEN_KIND_LENGTH = 96;
    static final Type<ClientPerceptionPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(
            HelixFabricSensorMod.MOD_ID,
            "client_perception"
        )
    );
    static final StreamCodec<RegistryFriendlyByteBuf, ClientPerceptionPayload> CODEC =
        new StreamCodec<>() {
            @Override
            public ClientPerceptionPayload decode(RegistryFriendlyByteBuf buffer) {
                return new ClientPerceptionPayload(
                    buffer.readVarLong(),
                    buffer.readUtf(8),
                    buffer.readUtf(MAX_SCREEN_KIND_LENGTH),
                    buffer.readBoolean()
                );
            }

            @Override
            public void encode(
                RegistryFriendlyByteBuf buffer,
                ClientPerceptionPayload payload
            ) {
                buffer.writeVarLong(Math.max(0L, payload.clientGameTick()));
                buffer.writeUtf(payload.screenState(), 8);
                buffer.writeUtf(payload.screenKind(), MAX_SCREEN_KIND_LENGTH);
                buffer.writeBoolean(payload.inputActivity());
            }
        };

    public ClientPerceptionPayload {
        clientGameTick = Math.max(0L, clientGameTick);
        screenState = "open".equals(screenState) ? "open" : "closed";
        String boundedKind = screenKind == null ? "unknown" : screenKind.trim();
        if (boundedKind.isEmpty()) boundedKind = "unknown";
        screenKind = boundedKind.substring(
            0,
            Math.min(boundedKind.length(), MAX_SCREEN_KIND_LENGTH)
        );
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
