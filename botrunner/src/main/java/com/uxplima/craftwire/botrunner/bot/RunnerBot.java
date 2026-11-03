package com.uxplima.craftwire.botrunner.bot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.behavior.BehaviorSlot;
import com.uxplima.craftwire.bot.behavior.BotControl;
import com.uxplima.craftwire.bot.model.BotState;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * A protocol bot in the runner (a Java or a Bedrock connection). The common actions work on {@link BotControl};
 * what only one kind can do goes through {@link #action}. Runner loop thread, except {@link #connected}.
 */
public interface RunnerBot extends BotControl {
    /** What a tick left the bot as: still playing, or gone (disconnected, kicked, removed). */
    enum Tick { ALIVE, GONE }

    String group();

    /** The address of the server it plays on, as given at spawn. */
    String address();

    Tick tick(long now);

    BehaviorSlot behavior();

    BotState state();

    /** The bot_list entry: name, kind, group, position, and the dimension it is in. */
    JsonObject summary();

    /** A kind-specific action, or empty when this kind has none of that name (the caller reports UNSUPPORTED_FOR_KIND). */
    Optional<CompletableFuture<JsonElement>> action(String action, JsonObject p);

    /** Selects a hotbar slot 0-8 (the set-carried-item packet). */
    void selectHotbar(int slot);

    /** Disconnects; the bot is gone after its next tick. */
    void leave(String reason);

    /** Why the bot left (kicked, disconnected, removed), once {@link #tick} has returned GONE. */
    String goneReason();

    boolean connected();
}
