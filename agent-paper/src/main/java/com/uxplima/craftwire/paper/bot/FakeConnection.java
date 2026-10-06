package com.uxplima.craftwire.paper.bot;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

/**
 * A server-side connection with no client: outbound packets are dropped after the bot has looked at them.
 * Chat and action-bar messages are copied into the bot's inbox, HUD packets update its BotHud, and teleports
 * are remembered so the bot can confirm them.
 * EmbeddedChannel runs writes inline, so nothing queues up.
 */
final class FakeConnection {
    private FakeConnection() {}

    static Connection create(BotInbox inbox, BotHud hud, AtomicInteger pendingTeleport) {
        Connection c = new Connection(PacketFlow.SERVERBOUND);
        c.channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                try {
                    record(inbox, hud, pendingTeleport, msg, System.currentTimeMillis());
                } finally {
                    ReferenceCountUtil.release(msg);
                    promise.setSuccess();
                }
            }
        });
        c.address = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        return c;
    }

    static void record(BotInbox inbox, BotHud hud, AtomicInteger pendingTeleport, Object msg, long now) {
        if (!(msg instanceof ClientboundBundlePacket)) {
            try {
                hud.accept(msg, now);
            } catch (RuntimeException e) {
                // A packet the HUD copy cannot apply (odd plugin ordering) must never break the bot's connection.
            }
        }
        if (msg instanceof ClientboundPlayerPositionPacket p) {
            pendingTeleport.set(p.id());
        } else if (msg instanceof ClientboundSystemChatPacket p) {
            inbox.add(p.overlay() ? "actionbar" : "system", p.content().getString(), null, now);
        } else if (msg instanceof ClientboundPlayerChatPacket p) {
            String text = p.unsignedContent() != null ? p.unsignedContent().getString() : p.body().content();
            inbox.add("chat", text, p.chatType().name().getString(), now);
        } else if (msg instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> sub : bundle.subPackets()) record(inbox, hud, pendingTeleport, sub, now);
        }
    }
}
