package com.casimirbot.helixcompanion.spike;

import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * A bounded, non-networked client-update sink for a detached mechanics subject.
 * It never opens a channel and cannot receive packets or user input.
 */
public final class BoundedPacketSinkConnection extends Connection {
    private final int maximumPackets;
    private int discardedPackets;

    public BoundedPacketSinkConnection(int maximumPackets) {
        super(PacketFlow.SERVERBOUND);
        if (maximumPackets < 1 || maximumPackets > 256) {
            throw new IllegalArgumentException("maximumPackets must be 1-256");
        }
        this.maximumPackets = maximumPackets;
    }

    @Override
    public void send(Packet<?> packet) {
        recordDiscard();
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener) {
        recordDiscard();
    }

    @Override
    public void send(
        Packet<?> packet,
        ChannelFutureListener listener,
        boolean flush
    ) {
        recordDiscard();
    }

    public int discardedPackets() {
        return discardedPackets;
    }

    private void recordDiscard() {
        discardedPackets += 1;
        if (discardedPackets > maximumPackets) {
            throw new IllegalStateException("bounded_packet_sink_ceiling_exceeded");
        }
    }
}
