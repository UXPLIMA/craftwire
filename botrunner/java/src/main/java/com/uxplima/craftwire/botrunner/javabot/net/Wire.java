package com.uxplima.craftwire.botrunner.javabot.net;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javax.crypto.SecretKey;
import net.minecraft.network.CipherDecoder;
import net.minecraft.network.CipherEncoder;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.Varint21FrameDecoder;
import net.minecraft.network.Varint21LengthFieldPrepender;
import net.minecraft.network.protocol.Packet;
import net.minecraft.util.Crypt;
import net.minecraft.util.CryptException;

/**
 * One bot's TCP connection, built from vanilla's own pipeline pieces (frames, compression, encryption) around our
 * codec. Everything that changes the pipeline or the protocol runs on the channel's event loop, in packet order.
 */
public final class Wire {
    /** Called on the event loop, in arrival order. */
    public interface Listener {
        void packet(Packet<?> packet);

        void closed(String reason);
    }

    /** Vanilla's read timeout: a server that sends nothing (not even keep-alives) for 30 s is gone. */
    private static final int TIMEOUT_S = 30;

    private final Channel channel;
    private final PacketCodec codec;
    private volatile String closeReason;

    private Wire(Channel channel, PacketCodec codec) {
        this.channel = channel;
        this.codec = codec;
    }

    public static CompletableFuture<Wire> connect(EventLoopGroup group, String host, int port, ProtocolInfo<?> in, ProtocolInfo<?> out,
            Listener listener) {
        PacketCodec codec = new PacketCodec(in, out);
        CompletableFuture<Wire> result = new CompletableFuture<>();
        Wire[] self = new Wire[1];
        Bootstrap b = new Bootstrap().group(group).channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast("timeout", new ReadTimeoutHandler(TIMEOUT_S))
                                .addLast("splitter", new Varint21FrameDecoder(null))
                                .addLast("decoder", codec.new Decoder())
                                .addLast("prepender", new Varint21LengthFieldPrepender())
                                .addLast("encoder", codec.new Encoder())
                                .addLast("handler", new Handler(listener, () -> self[0]));
                    }
                });
        ChannelFuture f = b.connect(host, port);
        f.addListener(done -> {
            if (!done.isSuccess()) {
                result.completeExceptionally(done.cause());
                return;
            }
            self[0] = new Wire(f.channel(), codec);
            result.complete(self[0]);
        });
        return result;
    }

    private static final class Handler extends SimpleChannelInboundHandler<Packet<?>> {
        private final Listener listener;
        private final Supplier<Wire> wire;

        Handler(Listener listener, Supplier<Wire> wire) {
            this.listener = listener;
            this.wire = wire;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Packet<?> packet) {
            listener.packet(packet);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            Wire w = wire.get();
            listener.closed(w != null && w.closeReason != null ? w.closeReason : "connection closed");
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            Wire w = wire.get();
            if (w != null && w.closeReason == null) w.closeReason = cause.toString();
            ctx.close();
        }
    }

    public EventLoop loop() {
        return channel.eventLoop();
    }

    public boolean open() {
        return channel.isOpen();
    }

    /** Sends in order from any thread; from the event loop the packet goes out with the current protocol at once. */
    public void send(Packet<?> packet) {
        if (channel.eventLoop().inEventLoop()) {
            channel.writeAndFlush(packet, channel.voidPromise());
        } else {
            channel.eventLoop().execute(() -> channel.writeAndFlush(packet, channel.voidPromise()));
        }
    }

    /** Event loop only. */
    public void inbound(ProtocolInfo<?> info) {
        codec.inbound(info);
    }

    /** Event loop only. */
    public void outbound(ProtocolInfo<?> info) {
        codec.outbound(info);
    }

    /** Event loop only: from ClientboundLoginCompressionPacket on, frames at or above the threshold are zlib-compressed. */
    public void compression(int threshold) {
        ChannelPipeline p = channel.pipeline();
        if (threshold < 0) {
            if (p.get("decompress") != null) p.remove("decompress");
            if (p.get("compress") != null) p.remove("compress");
            return;
        }
        if (p.get("decompress") instanceof CompressionDecoder d) {
            d.setThreshold(threshold, true);
        } else {
            p.addBefore("decoder", "decompress", new CompressionDecoder(threshold, true));
        }
        if (p.get("compress") instanceof CompressionEncoder e) {
            e.setThreshold(threshold);
        } else {
            p.addBefore("encoder", "compress", new CompressionEncoder(threshold));
        }
    }

    /** Event loop only, right after ServerboundKeyPacket went out: everything after it is AES/CFB8 with the shared secret. */
    public void encryption(SecretKey key) throws CryptException {
        channel.pipeline().addBefore("splitter", "decrypt", new CipherDecoder(Crypt.getCipher(2, key)));
        channel.pipeline().addBefore("prepender", "encrypt", new CipherEncoder(Crypt.getCipher(1, key)));
    }

    public void close(String reason) {
        if (closeReason == null) closeReason = reason;
        channel.close();
    }
}
