package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.gui.ScreenAction;
import com.uxplima.craftwire.bot.inbox.Message;
import com.uxplima.craftwire.bot.json.Jsons;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.ClientSettings;
import com.uxplima.craftwire.bot.move.Steering;
import com.uxplima.craftwire.bot.move.WalkOptions;
import com.uxplima.craftwire.bot.select.BotSelector;
import com.uxplima.craftwire.core.AgentError;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * The bot.action verbs of plugin bots. One bot by `bot`, or many by `bots` / `group` (the action runs on each, the
 * result lists each bot's outcome). Game work runs on each bot's own thread.
 */
public final class BotActions {
    /** How many per-bot results a multi-bot action returns in full; the rest are counted. */
    private static final int MAX_RESULTS = 20;

    private BotActions() {}

    public static CompletableFuture<JsonElement> run(JsonObject p, BotManager bots) {
        if (p.has("bot") && !p.get("bot").isJsonNull()) return one(p, bots, Params.string(p, "bot"));
        BotSelector s = BotSelector.parse(p);
        List<String> names = new ArrayList<>();
        bots.registry().select(s).forEach(b -> names.add(b.name()));
        if (names.isEmpty()) {
            return CompletableFuture.failedFuture(new AgentError("BOT_NOT_FOUND", "No bot matches the selector",
                    "bot_list shows the bots and their groups."));
        }
        List<CompletableFuture<JsonObject>> parts = new ArrayList<>();
        for (String n : names) {
            parts.add(one(p, bots, n).handle((r, t) -> {
                JsonObject o = new JsonObject();
                o.addProperty("bot", n);
                if (t == null) o.add("result", r);
                else o.addProperty("error", t instanceof AgentError e ? e.code() + ": " + e.getMessage() : String.valueOf(t.getMessage()));
                return o;
            }));
        }
        return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new)).thenApply(v -> {
            JsonArray results = new JsonArray();
            int failed = 0;
            for (CompletableFuture<JsonObject> f : parts) {
                JsonObject o = f.join();
                if (o.has("error")) failed++;
                if (results.size() < MAX_RESULTS) results.add(o);
            }
            JsonObject r = new JsonObject();
            r.addProperty("bots", names.size());
            r.addProperty("failed", failed);
            r.add("results", results);
            return (JsonElement) r;
        });
    }

    private static CompletableFuture<JsonElement> one(JsonObject p, BotManager bots, String name) {
        String action = Params.string(p, "action");
        if (ScreenAction.handles(action)) return BotScreenActions.run(ScreenAction.parse(action, p), bots, name);
        return switch (action) {
            case "chat" -> bots.on(name, b -> {
                b.chat(Params.string(p, "text"));
                return (JsonElement) Jsons.of("sent", true);
            });
            case "command" -> BotCommands.run(p, bots, name);
            case "messages" -> bots.on(name, b -> {
                long since = Params.optLong(p, "since").orElse(0L);
                int limit = Params.clampedInt(p, "limit", 50, 1, 200);
                JsonObject r = new JsonObject();
                r.add("messages", Message.json(b.inbox().since(since, limit)));
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
            case "break_block" -> bots.on(name, b -> {
                        JsonObject at = Params.object(p, "block");
                        Direction face = Params.optString(p, "face").map(f -> Direction.byName(f.toLowerCase(Locale.ROOT))).orElse(null);
                        return b.breakBlock(bots.plugin(), new BlockPos(Params.integer(at, "x"), Params.integer(at, "y"), Params.integer(at, "z")),
                                face, Params.clampedLong(p, "timeoutMs", 30_000L, 500L, 120_000L));
                    })
                    .thenCompose(f -> f).thenApply(r -> (JsonElement) r);
            case "state" -> bots.on(name, b -> (JsonElement) BotJson.state(b).json());
            case "hud_read" -> bots.on(name, b -> (JsonElement) b.hud());
            case "resource_packs" -> bots.on(name, b -> (JsonElement) b.link().packs.json());
            case "settings" -> {
                ClientSettings s = ClientSettings.parse(Params.object(p, "settings"));
                yield bots.on(name, b -> {
                    b.settings(s);
                    return (JsonElement) s.json();
                });
            }
            case "jump", "sneak", "sprint", "drop", "swap_hands", "give", "select_hotbar" -> BotInventoryActions.run(action, p, bots, name);
            case "use", "attack" -> BotInteract.run(action, p, bots, name);
            default -> BotGuiActions.run(action, p, bots, name);
        };
    }

    private static JsonElement look(Bot b, JsonObject p) {
        float yaw;
        float pitch;
        if (p.has("x")) {
            Vec3 eye = b.player().getEyePosition();
            double dx = Params.number(p, "x") - eye.x;
            double dy = Params.number(p, "y") - eye.y;
            double dz = Params.number(p, "z") - eye.z;
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
