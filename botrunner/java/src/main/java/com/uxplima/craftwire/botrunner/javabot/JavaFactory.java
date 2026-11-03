package com.uxplima.craftwire.botrunner.javabot;

import com.uxplima.craftwire.botrunner.bot.JoinSpec;
import com.uxplima.craftwire.botrunner.bot.RunnerBot;
import com.uxplima.craftwire.botrunner.bot.RunnerServices;
import com.uxplima.craftwire.botrunner.java.JavaBotFactory;
import com.uxplima.craftwire.botrunner.javabot.bot.JavaBot;
import com.uxplima.craftwire.botrunner.javabot.world.JavaBlocks;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * The Java bots of the version this class loader holds (found by the runner with ServiceLoader). Bootstraps the
 * vanilla registries once, builds the block table, and joins bots on one Netty event loop group shared by all of them.
 */
public final class JavaFactory implements JavaBotFactory {
    private final ExecutorService blocking = Executors.newVirtualThreadPerTaskExecutor();
    private JavaContext context;
    private EventLoopGroup group;

    @Override
    public void start(RunnerServices services) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // 0 threads: Netty's default, two per core, which carries thousands of mostly idle connections.
        group = new MultiThreadIoEventLoopGroup(0, NioIoHandler.newFactory());
        context = new JavaContext(services, new JavaBlocks(), JavaCompat.load(), group, blocking, SharedConstants.getProtocolVersion());
    }

    @Override
    public String minecraftVersion() {
        return SharedConstants.getCurrentVersion().name();
    }

    @Override
    public int protocolVersion() {
        return SharedConstants.getProtocolVersion();
    }

    @Override
    public CompletableFuture<RunnerBot> join(JoinSpec spec) {
        return JavaBot.join(context, spec);
    }

    @Override
    public void close() {
        if (group != null) group.shutdownGracefully();
        blocking.shutdownNow();
    }
}
