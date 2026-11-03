package com.uxplima.craftwire.bedrock.raknet;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * The unconnected ping a server list sends: the pong's "MCPE;motd;protocol;version;players;max;guid;..." string
 * tells which Bedrock protocol the server speaks before a bot connects. Sent three times, 300 ms apart.
 */
public final class RakPing {
    /** What the pong said. `protocol` is -1 when the server did not say. */
    public record Status(String edition, String motd, int protocol, String version, int players, int maxPlayers) {}

    private RakPing() {}

    public static CompletableFuture<Status> ping(EventLoopGroup group, InetSocketAddress server, long timeoutMs) {
        CompletableFuture<Status> result = new CompletableFuture<>();
        ChannelFuture cf = new Bootstrap().group(group).channel(NioDatagramChannel.class).handler(new SimpleChannelInboundHandler<DatagramPacket>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket p) {
                ByteBuf b = p.content();
                if (b.readableBytes() < 35 || b.readUnsignedByte() != RakIds.UNCONNECTED_PONG) return;
                b.skipBytes(8 + 8 + RakIds.MAGIC.length);
                int n = b.readUnsignedShort();
                String s = b.toString(b.readerIndex(), Math.min(n, b.readableBytes()), StandardCharsets.UTF_8);
                result.complete(parse(s));
                ctx.close();
            }
        }).connect(server);
        cf.addListener(f -> {
            if (!f.isSuccess()) {
                result.completeExceptionally(f.cause());
                return;
            }
            long id = ThreadLocalRandom.current().nextLong();
            for (int i = 0; i < 3; i++) {
                cf.channel().eventLoop().schedule(() -> cf.channel().writeAndFlush(request(id)), i * 300L, TimeUnit.MILLISECONDS);
            }
            cf.channel().eventLoop().schedule(() -> {
                if (result.completeExceptionally(new IllegalStateException("no pong from " + server))) cf.channel().close();
            }, timeoutMs, TimeUnit.MILLISECONDS);
        });
        return result;
    }

    private static ByteBuf request(long guid) {
        ByteBuf b = Unpooled.buffer(33);
        b.writeByte(RakIds.UNCONNECTED_PING);
        b.writeLong(System.currentTimeMillis());
        b.writeBytes(RakIds.MAGIC);
        b.writeLong(guid);
        return b;
    }

    /** Fields are separated by ';', with '\' escaping the next character. */
    static Status parse(String s) {
        List<String> f = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean escape = false;
        for (char c : s.toCharArray()) {
            if (escape) {
                cur.append(c);
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == ';') {
                f.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        f.add(cur.toString());
        return new Status(at(f, 0), at(f, 1), number(at(f, 2)), at(f, 3), number(at(f, 4)), number(at(f, 5)));
    }

    private static String at(List<String> f, int i) {
        return i < f.size() ? f.get(i) : "";
    }

    private static int number(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
