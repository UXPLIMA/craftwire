package com.uxplima.craftwire.fabric.gametest;

import static com.uxplima.craftwire.fabric.gametest.Calls.check;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.CraftwireClient;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;

/** client_eval: GraalJS downloaded and checked on first use, scripts on the render thread with the game at hand. */
final class EvalChecks {
    private EvalChecks() {}

    private static CompletableFuture<JsonElement> start(ClientGameTestContext ctx, String json) {
        return ctx.computeOnClient(mc -> CraftwireClient.agent().dispatcher().dispatch("client.eval", JsonParser.parseString(json).getAsJsonObject()));
    }

    /** The first call may download GraalJS: up to 5 minutes. */
    private static JsonObject eval(ClientGameTestContext ctx, String code) {
        JsonObject p = new JsonObject();
        p.addProperty("code", code);
        CompletableFuture<JsonElement> f = start(ctx, p.toString());
        ctx.waitFor(mc -> f.isDone(), 6000);
        try {
            return f.join().getAsJsonObject();
        } catch (CompletionException e) {
            throw new AssertionError("client.eval failed: " + e.getCause(), e.getCause());
        }
    }

    private static AgentError error(ClientGameTestContext ctx, String json) {
        CompletableFuture<JsonElement> f = start(ctx, json);
        ctx.waitFor(mc -> f.isDone(), 400);
        try {
            f.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof AgentError a) return a;
            throw new AssertionError("non-AgentError " + e.getCause(), e.getCause());
        }
        throw new AssertionError("unexpectedly succeeded: " + json);
    }

    static void run(ClientGameTestContext ctx) {
        check(eval(ctx, "1 + 1").get("result").getAsInt() == 2, "evaluates JavaScript");
        JsonObject thread = eval(ctx, "Java.type('java.lang.Thread').currentThread().getName()");
        check("Render thread".equals(thread.get("result").getAsString()), "runs on the render thread: " + thread);
        JsonObject name = eval(ctx, "player.getName().getString()");
        check(name.get("result").getAsString().length() >= 3, "player is the local player: " + name);
        check(eval(ctx, "level !== null && screen() === null").get("result").getAsBoolean(), "level and screen() in a world without a menu");
        JsonObject version = eval(ctx, "Java.type('com.uxplima.craftwire.fabric.CraftwireAgent').VERSION");
        check(!version.get("result").getAsString().isEmpty(), "mod classes are reachable: " + version);
        JsonObject printed = eval(ctx, "print('hello'); 3");
        check(printed.get("output").getAsString().contains("hello"), "captures print: " + printed);

        eval(ctx, "globalThis.kept = 41");
        check(eval(ctx, "kept + 1").get("result").getAsInt() == 42, "globals survive between calls");
        check("EVAL_ERROR".equals(error(ctx, "{\"code\":\"kept\",\"reset\":true}").code()), "reset drops the globals");
        check("EVAL_ERROR".equals(error(ctx, "{\"code\":\"throw new Error('boom')\"}").code()), "a script error is EVAL_ERROR");
        check("TIMEOUT".equals(error(ctx, "{\"code\":\"while (true) {}\",\"timeoutMs\":300}").code()), "a runaway script is cancelled");
        check(eval(ctx, "2 * 21").get("result").getAsInt() == 42, "and the next script runs");
    }
}
