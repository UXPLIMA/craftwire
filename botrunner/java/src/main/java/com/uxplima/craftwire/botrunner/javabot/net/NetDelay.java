package com.uxplima.craftwire.botrunner.javabot.net;

import com.uxplima.craftwire.botrunner.net.NetSim;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.util.concurrent.TimeUnit;

/**
 * The Java bot's network simulation, first in its TCP pipeline: every read and write waits as {@link NetSim} says,
 * but never ahead of the one before it, since a stream keeps its order whatever the jitter. Built here against the
 * version's own Netty (the runner's NetSimHandler cannot join this pipeline). Event loop only.
 */
final class NetDelay extends ChannelDuplexHandler {
    private final NetSim sim;
    private long inboundAt;
    private long outboundAt;

    NetDelay(NetSim sim) {
        this.sim = sim;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        long now = System.nanoTime();
        inboundAt = Math.max(now + sim.delayNanos(true), inboundAt);
        ctx.executor().schedule(() -> {
            if (!ctx.channel().isActive()) {
                ReferenceCountUtil.release(msg);
                return;
            }
            ctx.fireChannelRead(msg);
            ctx.fireChannelReadComplete();
        }, inboundAt - now, TimeUnit.NANOSECONDS);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        // Each delayed read fires its own read-complete when it is released.
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        long now = System.nanoTime();
        outboundAt = Math.max(now + sim.delayNanos(true), outboundAt);
        ctx.executor().schedule(() -> ctx.writeAndFlush(msg, promise), outboundAt - now, TimeUnit.NANOSECONDS);
    }
}
