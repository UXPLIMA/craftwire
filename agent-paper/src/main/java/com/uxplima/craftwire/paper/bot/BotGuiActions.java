package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.Sync;
import java.util.concurrent.CompletableFuture;

final class BotGuiActions {
    private BotGuiActions() {}

    static CompletableFuture<JsonElement> run(String action, JsonObject p, BotManager bots, Sync sync, String name) {
        return CompletableFuture.failedFuture(Args.invalid("Unknown bot action: " + action));
    }
}
