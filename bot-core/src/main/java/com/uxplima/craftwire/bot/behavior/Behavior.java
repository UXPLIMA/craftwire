package com.uxplima.craftwire.bot.behavior;

import com.google.gson.JsonObject;

/**
 * Something a bot keeps doing on its own, tick by tick, without an MCP call per step (wander, follow, chat, …).
 * One instance per bot. Runs on the bot's own thread.
 */
public interface Behavior {
    String type();

    void tick(BotControl bot, long now);

    /** Called once when the behavior is replaced or cleared, on the bot's thread. */
    default void stop(BotControl bot) {
        if (bot.moving()) bot.stopMoving();
    }

    /** Its parameters and progress, for bot_behavior {action:'status'}. */
    JsonObject describe();
}
