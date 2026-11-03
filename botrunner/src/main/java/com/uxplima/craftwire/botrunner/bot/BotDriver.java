package com.uxplima.craftwire.botrunner.bot;

import com.uxplima.craftwire.bot.model.BotKind;
import java.util.concurrent.CompletableFuture;

/**
 * One bot kind in the runner (java or bedrock): connects a bot and hands it over once it is in the world. Drivers
 * do their networking on their own threads and touch bots only through the runner loop.
 */
public interface BotDriver extends AutoCloseable {
    BotKind kind();

    /** Completes with the bot once it has spawned (play state, first position known), or fails with why it could not join. */
    CompletableFuture<RunnerBot> join(JoinSpec spec);

    @Override
    void close();
}
