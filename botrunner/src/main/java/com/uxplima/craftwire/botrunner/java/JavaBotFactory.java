package com.uxplima.craftwire.botrunner.java;

import com.uxplima.craftwire.botrunner.bot.JoinSpec;
import com.uxplima.craftwire.botrunner.bot.RunnerBot;
import com.uxplima.craftwire.botrunner.bot.RunnerServices;
import java.util.concurrent.CompletableFuture;

/**
 * The Java bots of one Minecraft version, implemented by the javabot jar inside that version's class loader and
 * found with ServiceLoader. The runner core never sees a Minecraft class; it talks to the version through this.
 */
public interface JavaBotFactory extends AutoCloseable {
    /** Bootstraps the version's registries once; called before the first join. */
    void start(RunnerServices services);

    String minecraftVersion();

    /** The protocol number this version speaks (SharedConstants.getProtocolVersion). */
    int protocolVersion();

    CompletableFuture<RunnerBot> join(JoinSpec spec);

    @Override
    void close();
}
