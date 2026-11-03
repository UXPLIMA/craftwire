package com.uxplima.craftwire.paper.bot;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.embedded.EmbeddedChannel;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

/**
 * A server-side connection with no client behind it. Every public send is overridden, so a packet is looked at by
 * {@link BotPackets} and dropped before Connection queues it or writes it to a channel: no pipeline work for
 * packets nobody reads, which every chunk and entity update of every bot used to go through. The listener a caller passes (a kick
 * closes the connection from one) still runs, as if the write had succeeded. The empty EmbeddedChannel only keeps
 * Connection's open/closed state. Any thread may send.
 */
final class BotConnection extends Connection {
    private final BotLink link;
    private final String name;

    private BotConnection(BotLink link, String name) {
        super(PacketFlow.SERVERBOUND);
        this.link = link;
        this.name = name;
    }

    static BotConnection create(BotLink link, String name) {
        BotConnection c = new BotConnection(link, name);
        c.channel = new EmbeddedChannel();
        c.address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        return c;
    }

    @Override
    public void send(Packet<?> packet) {
        take(packet, null);
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener) {
        take(packet, listener);
    }

    @Override
    public void send(Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        take(packet, listener);
    }

    @Override
    public void sendPacketAndScheduleNettyTask(Packet<?> packet, boolean flush, Runnable task) {
        take(packet, null);
        task.run();
    }

    private void take(Packet<?> packet, ChannelFutureListener listener) {
        try {
            BotPackets.accept(link, name, packet, System.currentTimeMillis());
        } catch (RuntimeException e) {
            // A packet the copies cannot apply (odd plugin ordering) must never break the bot's connection.
        }
        if (listener != null) {
            ChannelFuture done = channel.newSucceededFuture();
            try {
                listener.operationComplete(done);
            } catch (Exception e) {
                throw new IllegalStateException("A send listener failed for bot " + name, e);
            }
        }
    }
}
