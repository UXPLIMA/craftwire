package com.uxplima.craftwire.bedrock.raknet;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * A RakNet client connection to a Bedrock server over its own UDP socket, as the game client opens it: MTU
 * discovery with OpenConnectionRequest1, OpenConnectionRequest2, then ConnectionRequest and NewIncomingConnection,
 * after which game packet batches flow reliably and in order. Pings, ACKs and resends run on a 50 ms tick. All
 * state lives on the channel's event loop; {@link #send} and {@link #close} may be called from any thread.
 */
public final class RakClient {
    /** Receives the game packet batches (starting with 0xfe) and the end of the connection, on the event loop. */
    public interface Listener {
        void batch(ByteBuf batch);

        void closed(String reason);
    }

    private static final long TICK_MS = 50;
    private static final long REQUEST_EVERY_MS = 500;
    private static final long IDLE_TIMEOUT_MS = 15_000;
    private static final long CONNECT_TIMEOUT_MS = 15_000;

    private final InetSocketAddress server;
    private final Listener listener;
    private final long guid = ThreadLocalRandom.current().nextLong();
    private final long start = System.currentTimeMillis();
    private final CompletableFuture<RakClient> connected = new CompletableFuture<>();
    private Channel channel;
    private RakHandshake handshake;
    private RakReliability reliability;
    private ScheduledFuture<?> ticker;
    private long lastHeard = System.currentTimeMillis();
    private int ticks;
    private boolean online;
    private boolean closed;

    private RakClient(InetSocketAddress server, Listener listener) {
        this.server = server;
        this.listener = listener;
    }

    public static CompletableFuture<RakClient> connect(EventLoopGroup group, InetSocketAddress server, Listener listener) {
        RakClient c = new RakClient(server, listener);
        ChannelFuture cf = new Bootstrap().group(group).channel(NioDatagramChannel.class).handler(c.new Inbound()).connect(server);
        cf.addListener(f -> {
            if (!f.isSuccess()) {
                c.connected.completeExceptionally(f.cause());
                return;
            }
            c.started(cf.channel());
        });
        return c.connected;
    }

    private void started(Channel ch) {
        channel = ch;
        handshake = new RakHandshake(this, server, guid);
        ticker = ch.eventLoop().scheduleAtFixedRate(this::tick, 0, TICK_MS, TimeUnit.MILLISECONDS);
        ch.eventLoop().schedule(() -> {
            if (!connected.isDone()) close("no answer from " + server + " (is a Bedrock server listening on that UDP port?)");
        }, CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    }

    public EventLoop loop() {
        return channel.eventLoop();
    }

    public double rttMs() {
        RakReliability r = reliability;
        return r == null ? 0 : r.rttMs();
    }

    /** Sends one game packet batch, reliable and ordered. */
    public void send(ByteBuf batch) {
        byte[] bytes = new byte[batch.readableBytes()];
        batch.readBytes(bytes);
        batch.release();
        channel.eventLoop().execute(() -> {
            if (online && !closed) reliability.send(bytes, Frame.RELIABLE_ORDERED, now());
        });
    }

    /** Tells the server and closes; the listener hears `reason`. */
    public void close(String reason) {
        Channel ch = channel;
        if (ch == null) {
            connected.completeExceptionally(new IllegalStateException(reason));
            return;
        }
        ch.eventLoop().execute(() -> shut(reason, true));
    }

    private void shut(String reason, boolean notify) {
        if (closed) return;
        closed = true;
        if (notify && online) reliability.send(new byte[] {(byte) RakIds.DISCONNECT_NOTIFICATION}, Frame.RELIABLE_ORDERED, now());
        if (ticker != null) ticker.cancel(false);
        // A moment for the notification to leave before the socket closes.
        channel.eventLoop().schedule(() -> channel.close(), notify ? 100 : 0, TimeUnit.MILLISECONDS);
        if (!connected.isDone()) connected.completeExceptionally(new IllegalStateException(reason));
        listener.closed(reason);
    }

    long now() {
        return System.currentTimeMillis() - start;
    }

    void raw(ByteBuf b) {
        channel.writeAndFlush(b);
    }

    /** The offline handshake finished with this MTU: open the reliable session and request the connection. */
    void open(int mtu) {
        reliability = new RakReliability(mtu, this::raw, this::message);
        ByteBuf b = Unpooled.buffer(18);
        b.writeByte(RakIds.CONNECTION_REQUEST);
        b.writeLong(guid);
        b.writeLong(now());
        b.writeBoolean(false);
        sendControl(b);
    }

    void refused(String reason) {
        shut(reason, false);
    }

    private void sendControl(ByteBuf b) {
        byte[] bytes = new byte[b.readableBytes()];
        b.readBytes(bytes);
        b.release();
        reliability.send(bytes, Frame.RELIABLE_ORDERED, now());
    }

    private void tick() {
        if (closed) return;
        ticks++;
        long wall = System.currentTimeMillis();
        if (wall - lastHeard > IDLE_TIMEOUT_MS) {
            shut("the server stopped answering (timed out)", false);
            return;
        }
        if (reliability == null) {
            if (ticks % (REQUEST_EVERY_MS / TICK_MS) == 1) handshake.retry();
            return;
        }
        reliability.tick(now(), ticks % 6 == 0);
        if (ticks % 10 == 0) {
            ByteBuf ping = Unpooled.buffer(9);
            ping.writeByte(RakIds.CONNECTED_PING);
            ping.writeLong(now());
            sendControl(ping);
        }
    }

    /** A whole message from the reliable session: RakNet control or a game packet batch. */
    private void message(byte[] m) {
        int id = m[0] & 0xff;
        ByteBuf b = Unpooled.wrappedBuffer(m, 1, m.length - 1);
        switch (id) {
            case RakIds.CONNECTION_REQUEST_ACCEPTED -> accepted(b);
            case RakIds.CONNECTED_PING -> {
                long pingTime = b.readLong();
                ByteBuf pong = Unpooled.buffer(17);
                pong.writeByte(RakIds.CONNECTED_PONG);
                pong.writeLong(pingTime);
                pong.writeLong(now());
                byte[] bytes = new byte[pong.readableBytes()];
                pong.readBytes(bytes);
                reliability.send(bytes, Frame.UNRELIABLE, now());
            }
            case RakIds.DISCONNECT_NOTIFICATION -> shut("the server closed the connection", false);
            case RakIds.GAME_PACKET -> {
                if (online) listener.batch(Unpooled.wrappedBuffer(m));
            }
            default -> { }
        }
    }

    /** ConnectionRequestAccepted: answer NewIncomingConnection with its pong time; the connection is up. */
    private void accepted(ByteBuf b) {
        if (online) return;
        RakAddress.skip(b);
        b.skipBytes(2);
        while (b.readableBytes() > 16) RakAddress.skip(b);
        b.skipBytes(8);
        long pongTime = b.readLong();
        ByteBuf nic = Unpooled.buffer(256);
        nic.writeByte(RakIds.NEW_INCOMING_CONNECTION);
        RakAddress.write(nic, server);
        for (int i = 0; i < 20; i++) RakAddress.writeEmpty(nic);
        nic.writeLong(pongTime);
        nic.writeLong(now());
        sendControl(nic);
        online = true;
        connected.complete(this);
    }

    private final class Inbound extends SimpleChannelInboundHandler<DatagramPacket> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket p) {
            ByteBuf b = p.content();
            if (!b.isReadable() || closed) return;
            lastHeard = System.currentTimeMillis();
            int flags = b.readUnsignedByte();
            try {
                if (reliability != null && (flags & RakIds.FLAG_DATAGRAM) != 0) reliability.receive(flags, b, now());
                else if (reliability == null) handshake.reply(flags, b);
            } catch (RuntimeException e) {
                // One malformed datagram is dropped, as RakNet does; the next resend repairs what it carried.
            }
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            shut("the connection closed", false);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            shut("network error: " + cause.getMessage(), false);
        }
    }
}
