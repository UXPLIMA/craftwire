package com.uxplima.craftwire.fabric.gametest;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.CraftwireClient;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

final class Calls {
    private Calls() {}

    // Gametests forbid Minecraft.getInstance() on the test thread; handlers use it, so dispatch from the client thread.
    static CompletableFuture<JsonElement> start(ClientGameTestContext ctx, String method, String paramsJson) {
        return ctx.computeOnClient(mc -> CraftwireClient.agent().dispatcher().dispatch(method, JsonParser.parseString(paramsJson).getAsJsonObject()));
    }

    static JsonElement call(ClientGameTestContext ctx, String method, String paramsJson) {
        CompletableFuture<JsonElement> f = start(ctx, method, paramsJson);
        ctx.waitFor(mc -> f.isDone(), 400);
        try {
            return f.join();
        } catch (CompletionException e) {
            throw new AssertionError(method + " failed: " + e.getCause(), e.getCause());
        }
    }

    static AgentError error(ClientGameTestContext ctx, String method, String paramsJson) {
        CompletableFuture<JsonElement> f = start(ctx, method, paramsJson);
        ctx.waitFor(mc -> f.isDone(), 400);
        try {
            f.join();
        } catch (CompletionException e) {
            Throwable c = e.getCause();
            if (c instanceof AgentError a) return a;
            throw new AssertionError(method + " threw non-AgentError " + c, c);
        }
        throw new AssertionError(method + " unexpectedly succeeded");
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
