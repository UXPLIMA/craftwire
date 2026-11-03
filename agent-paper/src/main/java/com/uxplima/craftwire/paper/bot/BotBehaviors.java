package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.behavior.Behavior;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BotErrors;
import com.uxplima.craftwire.bot.select.BotSelector;
import com.uxplima.craftwire.script.bot.BotScript;
import com.uxplima.craftwire.script.bot.ScriptBehavior;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * bot.behavior: give the selected bots a behavior (set), stop it (stop), or read how they are doing (status). The
 * behavior then runs in each bot's own tick, with no MCP call per step.
 */
public final class BotBehaviors {
    private static final int MAX_DETAILS = 20;

    private BotBehaviors() {}

    public static CompletableFuture<JsonElement> handle(JsonObject p, BotManager bots) {
        BotSelector s = BotSelector.parse(p);
        String action = Params.optString(p, "action").orElse("set");
        long now = System.currentTimeMillis();
        return switch (action) {
            case "set" -> set(p, bots, s, now);
            case "stop" -> bots.onEach(s, b -> {
                b.behavior().clear(b);
                return true;
            }).thenApply(list -> (JsonElement) count("stopped", list.size()));
            case "status" -> bots.onEach(s, b -> named(b.name(), b.behavior().describe(now))).thenApply(BotBehaviors::status);
            default -> CompletableFuture.failedFuture(BotErrors.invalid("action must be set, stop or status"));
        };
    }

    private static CompletableFuture<JsonElement> set(JsonObject p, BotManager bots, BotSelector s, long now) {
        String type = Params.string(p, "type");
        JsonObject params = Params.optObject(p, "params").orElseGet(JsonObject::new);
        Function<Bot, Behavior> make;
        if (type.equals("script")) {
            BotScript script = BotScript.load(Params.string(params, "code"), bots.plugin().getClass().getClassLoader());
            make = b -> ScriptBehavior.attach(script, b);
        } else {
            bots.services().behaviors().check(type, params);
            long seed = Params.optLong(p, "seed").orElse(0L);
            make = b -> bots.services().behaviors().create(type, params, b.name(), seed);
        }
        return bots.onEach(s, b -> {
            b.behavior().set(b, make.apply(b), now);
            return true;
        }).thenApply(list -> {
            JsonObject r = count("bots", list.size());
            r.addProperty("type", type);
            return (JsonElement) r;
        });
    }

    private static JsonObject named(String name, JsonObject o) {
        o.addProperty("bot", name);
        return o;
    }

    /** Counts by behavior type, plus the first details. */
    private static JsonElement status(List<JsonObject> all) {
        Map<String, Integer> byType = new TreeMap<>();
        JsonArray details = new JsonArray();
        for (JsonObject o : all) {
            byType.merge(o.get("type").getAsString(), 1, Integer::sum);
            if (details.size() < MAX_DETAILS) details.add(o);
        }
        JsonObject counts = new JsonObject();
        byType.forEach(counts::addProperty);
        JsonObject r = new JsonObject();
        r.addProperty("bots", all.size());
        r.add("byType", counts);
        r.add("details", details);
        return r;
    }

    private static JsonObject count(String key, int n) {
        JsonObject r = new JsonObject();
        r.addProperty(key, n);
        return r;
    }
}
