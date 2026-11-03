package com.uxplima.craftwire.botrunner.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BotErrors;
import com.uxplima.craftwire.bot.model.BotKind;
import com.uxplima.craftwire.bot.select.BotSelector;
import com.uxplima.craftwire.bot.spawn.SpawnRequest;
import com.uxplima.craftwire.botrunner.bot.RunnerBots;
import com.uxplima.craftwire.core.Dispatcher;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** The runner's RPC methods: the bot.* set the hub's bot tools call, plus runner.stats for load reports. */
public final class RunnerMethods {
    private RunnerMethods() {}

    public static void register(Dispatcher d, RunnerBots bots) {
        d.register("bot.spawn", p -> spawn(p, bots));
        d.register("bot.remove", p -> remove(p, bots));
        d.register("bot.list", p -> {
            boolean any = p.has("bot") || p.has("bots") || p.has("group");
            int limit = Params.clampedInt(p, "limit", 100, 1, 1000);
            return bots.list(any ? BotSelector.parse(p) : null, limit).thenApply(r -> (JsonElement) r);
        });
        d.register("bot.action", p -> RunnerActions.run(p, bots));
        d.register("bot.behavior", p -> RunnerBehaviors.handle(p, bots));
        d.register("bot.form", p -> form(p, bots));
        d.register("runner.stats", p -> bots.services().loop().call(() -> (JsonElement) stats(bots)));
    }

    private static CompletableFuture<JsonElement> spawn(JsonObject p, RunnerBots bots) {
        BotKind kind = BotKind.parse(Params.optString(p, "kind").orElse("java"));
        if (kind == BotKind.PLUGIN) throw BotErrors.invalid("plugin bots live in a Paper server, not in a bot runner");
        return bots.spawn(SpawnRequest.parse(p), kind).thenApply(r -> (JsonElement) r);
    }

    private static CompletableFuture<JsonElement> remove(JsonObject p, RunnerBots bots) {
        boolean names = Params.bool(p, "names", false);
        if (Params.bool(p, "all", false)) return bots.removeAll(names).thenApply(r -> (JsonElement) r);
        BotSelector s = p.has("name") ? BotSelector.one(Params.string(p, "name")) : BotSelector.parse(p);
        return bots.remove(s, names).thenApply(r -> (JsonElement) r);
    }

    /** Bedrock forms are a bot's own action; a Java bot answers UNSUPPORTED_FOR_KIND. */
    private static CompletableFuture<JsonElement> form(JsonObject p, RunnerBots bots) {
        String name = Params.string(p, "bot");
        return bots.on(name, b -> {
            Optional<CompletableFuture<JsonElement>> r = b.action("form", p);
            if (r.isEmpty()) throw BotErrors.unsupported(b.kind(), "bot_form");
            return r.get();
        }).thenCompose(f -> f);
    }

    private static JsonObject stats(RunnerBots bots) {
        JsonObject o = new JsonObject();
        o.addProperty("bots", bots.count());
        o.addProperty("maxBots", bots.maxBots());
        o.addProperty("tickMs", Math.round(bots.services().loop().lastTickMs() * 100) / 100.0);
        Runtime rt = Runtime.getRuntime();
        o.addProperty("heapMb", (rt.totalMemory() - rt.freeMemory()) / 1048576);
        o.add("worldCache", bots.services().worlds().stats());
        return o;
    }
}
