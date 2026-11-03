package com.uxplima.craftwire.botrunner.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.inbox.Message;
import com.uxplima.craftwire.bot.json.Jsons;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BotErrors;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.bot.move.Steering;
import com.uxplima.craftwire.bot.move.WalkOptions;
import com.uxplima.craftwire.bot.select.BotSelector;
import com.uxplima.craftwire.botrunner.bot.RunnerBot;
import com.uxplima.craftwire.botrunner.bot.RunnerBots;
import com.uxplima.craftwire.core.AgentError;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * bot.action for protocol bots: the verbs every kind shares, written once on the bot model, then the kind's own
 * verbs ({@link RunnerBot#action}). One bot by `bot`, or many by `bots` / `group`, in the plugin bot's result shape.
 */
public final class RunnerActions {
    private static final int MAX_RESULTS = 20;
    /** Eye height of a standing player, for look-at targets. */
    private static final double EYE = 1.62;

    private RunnerActions() {}

    public static CompletableFuture<JsonElement> run(JsonObject p, RunnerBots bots) {
        if (p.has("bot") && !p.get("bot").isJsonNull()) return one(p, bots, Params.string(p, "bot"));
        BotSelector s = BotSelector.parse(p);
        return bots.onEach(s, RunnerBot::name).thenCompose(names -> {
            if (names.isEmpty()) {
                return CompletableFuture.failedFuture(new AgentError("BOT_NOT_FOUND", "No bot matches the selector",
                        "bot_list shows the bots and their groups."));
            }
            List<CompletableFuture<JsonObject>> parts = new ArrayList<>();
            for (String n : names) parts.add(one(p, bots, n).handle((r, t) -> outcome(n, r, t)));
            return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new)).thenApply(v -> merged(names.size(), parts));
        });
    }

    private static JsonObject outcome(String name, JsonElement r, Throwable t) {
        JsonObject o = new JsonObject();
        o.addProperty("bot", name);
        Throwable cause = t != null && t.getCause() != null && !(t instanceof AgentError) ? t.getCause() : t;
        if (t == null) o.add("result", r);
        else o.addProperty("error", cause instanceof AgentError e ? e.code() + ": " + e.getMessage() : String.valueOf(cause.getMessage()));
        return o;
    }

    private static JsonElement merged(int count, List<CompletableFuture<JsonObject>> parts) {
        JsonArray results = new JsonArray();
        int failed = 0;
        for (CompletableFuture<JsonObject> f : parts) {
            JsonObject o = f.join();
            if (o.has("error")) failed++;
            if (results.size() < MAX_RESULTS) results.add(o);
        }
        JsonObject r = new JsonObject();
        r.addProperty("bots", count);
        r.addProperty("failed", failed);
        r.add("results", results);
        return r;
    }

    private static CompletableFuture<JsonElement> one(JsonObject p, RunnerBots bots, String name) {
        String action = Params.string(p, "action");
        return switch (action) {
            case "chat" -> bots.on(name, b -> {
                b.chat(Params.string(p, "text"));
                return (JsonElement) Jsons.of("sent", true);
            });
            case "command" -> command(p, bots, name);
            case "messages" -> bots.on(name, b -> {
                JsonObject r = new JsonObject();
                r.add("messages", Message.json(b.inbox().since(Params.optLong(p, "since").orElse(0L), Params.clampedInt(p, "limit", 50, 1, 200))));
                return (JsonElement) r;
            });
            case "look" -> bots.on(name, b -> look(b, p));
            case "move_to" -> {
                WalkOptions o = WalkOptions.parse(p);
                yield bots.on(name, b -> b.moveTo(o)).thenCompose(f -> f).thenApply(r -> (JsonElement) r);
            }
            case "stop" -> bots.on(name, b -> {
                boolean was = b.moving();
                b.stopMoving();
                return (JsonElement) Jsons.of("stopped", was);
            });
            case "state" -> bots.on(name, b -> (JsonElement) b.state().json());
            default -> RunnerGui.handles(action) ? RunnerGui.run(action, p, bots, name) : kindAction(action, p, bots, name);
        };
    }

    private static CompletableFuture<JsonElement> kindAction(String action, JsonObject p, RunnerBots bots, String name) {
        return bots.on(name, b -> {
            Optional<CompletableFuture<JsonElement>> r = b.action(action, p);
            if (r.isEmpty()) throw BotErrors.unsupported(b.kind(), action);
            return r.get();
        }).thenCompose(f -> f);
    }

    /**
     * Sends the command as a client does and collects what the bot was told for `collectMs`. A protocol bot cannot
     * see whether a plugin cancelled the command; the replies are what it has.
     */
    private static CompletableFuture<JsonElement> command(JsonObject p, RunnerBots bots, String name) {
        String raw = Params.string(p, "command").strip();
        String command = raw.startsWith("/") ? raw.substring(1) : raw;
        long collectMs = Params.clampedLong(p, "collectMs", 300L, 0L, 5000L);
        return bots.on(name, b -> {
                    long last = b.inbox().lastId();
                    b.command(command);
                    return last;
                })
                .thenCompose(last -> CompletableFuture.supplyAsync(() -> last,
                        CompletableFuture.delayedExecutor(Math.max(collectMs, 50L), TimeUnit.MILLISECONDS)))
                .thenCompose(last -> bots.on(name, b -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("command", command);
                    o.add("replies", Message.json(b.inbox().after(last)));
                    return (JsonElement) o;
                }));
    }

    private static JsonElement look(RunnerBot b, JsonObject p) {
        float yaw;
        float pitch;
        if (p.has("x")) {
            Position at = b.position();
            double dx = Params.number(p, "x") - at.x();
            double dy = Params.number(p, "y") - (at.y() + EYE);
            double dz = Params.number(p, "z") - at.z();
            yaw = Steering.yaw(dx, dz);
            pitch = Steering.pitch(dx, dy, dz);
        } else {
            yaw = (float) Params.number(p, "yaw");
            pitch = (float) Math.clamp(Params.number(p, "pitch"), -90, 90);
        }
        b.look(yaw, pitch);
        JsonObject r = new JsonObject();
        r.addProperty("yaw", Jsons.round(yaw));
        r.addProperty("pitch", Jsons.round(pitch));
        return r;
    }
}
