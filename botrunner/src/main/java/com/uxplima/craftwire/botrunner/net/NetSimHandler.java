package com.uxplima.craftwire.botrunner.net;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.util.concurrent.TimeUnit;

/**
 * Bedrock's network simulation: delays and drops one RakNet connection's datagrams both ways, first in its pipeline.
 * Datagrams may arrive in any order, as on a real network; RakNet orders and resends them. Runs on the channel's
 * event loop (Netty calls handlers there, and the delayed work is scheduled there).
 */
public final class NetSimHandler extends ChannelDuplexHandler {
    private final NetSim sim;

    public NetSimHandler(NetSim sim) {
        this.sim = sim;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (sim.lose()) {
            ReferenceCountUtil.release(msg);
            return;
        }
        ctx.executor().schedule(() -> {
            if (!ctx.channel().isActive()) {
                ReferenceCountUtil.release(msg);
                return;
            }
            ctx.fireChannelRead(msg);
            ctx.fireChannelReadComplete();
        }, sim.delayNanos(false), TimeUnit.NANOSECONDS);
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        // Each delayed datagram fires its own read-complete when it is released.
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (sim.lose()) {
            ReferenceCountUtil.release(msg);
            promise.trySuccess();
            return;
        }
        ctx.executor().schedule(() -> ctx.writeAndFlush(msg, promise), sim.delayNanos(false), TimeUnit.NANOSECONDS);
    }
}
