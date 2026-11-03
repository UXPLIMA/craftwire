package com.uxplima.craftwire.botrunner.bedrock;

import com.uxplima.craftwire.bot.model.BotKind;
import com.uxplima.craftwire.botrunner.bot.BotDriver;
import com.uxplima.craftwire.botrunner.bot.JoinSpec;
import com.uxplima.craftwire.botrunner.bot.RunnerBot;
import com.uxplima.craftwire.botrunner.bot.RunnerServices;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bedrock protocol bots (protocol 2225, game version 1.26.60). One set of UDP event loops serves every bot; each bot
 * has its own socket, as each game client does. Blocking setup runs on virtual threads, never on the runner loop.
 */
public final class BedrockDriver implements BotDriver {
    private final ExecutorService blocking = Executors.newVirtualThreadPerTaskExecutor();
    private final MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(0, NioIoHandler.newFactory());
    private final BedrockContext ctx;

    public BedrockDriver(RunnerServices services) {
        this.ctx = new BedrockContext(services, new BedrockBlocks(services.home()), group, blocking);
    }

    @Override
    public BotKind kind() {
        return BotKind.BEDROCK;
    }

    @Override
    public CompletableFuture<RunnerBot> join(JoinSpec spec) {
        return BedrockJoin.start(ctx, spec);
    }

    @Override
    public void close() {
        group.shutdownGracefully();
        blocking.shutdownNow();
    }
}
