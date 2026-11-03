package com.uxplima.craftwire.paper.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.select.BotSelector;
import com.uxplima.craftwire.bot.spawn.SpawnRequest;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.CraftwirePlugin;
import com.uxplima.craftwire.paper.bot.BotActions;
import com.uxplima.craftwire.paper.bot.BotBehaviors;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/** bot.spawn, bot.remove, bot.list, bot.action and bot.behavior for the plugin's in-server bots. */
final class BotHandler {
    private BotHandler() {}

    static CompletableFuture<JsonElement> spawn(JsonObject p, CraftwirePlugin self) {
        self.agentConfig().require(self.agentConfig().allowBots(), "allow-bots");
        SpawnRequest req = SpawnRequest.parse(p);
        return self.sync().global(() -> location(p)).thenCompose(at -> self.bots().spawn(req, at)).thenApply(r -> (JsonElement) r);
    }

    /** `all`, or a selector (bot, bots, group); the old `name` still names one bot. Returns the count, names on request. */
    static CompletableFuture<JsonElement> remove(JsonObject p, CraftwirePlugin self) {
        self.agentConfig().require(self.agentConfig().allowBots(), "allow-bots");
        boolean names = Params.bool(p, "names", false);
        if (Params.bool(p, "all", false)) return self.bots().removeAll(names).thenApply(r -> (JsonElement) r);
        BotSelector s = p.has("name") ? BotSelector.one(Params.string(p, "name")) : BotSelector.parse(p);
        return self.bots().remove(s, names).thenApply(r -> (JsonElement) r);
    }

    static CompletableFuture<JsonElement> list(JsonObject p, CraftwirePlugin self) {
        boolean any = p.has("bot") || p.has("bots") || p.has("group");
        int limit = Params.clampedInt(p, "limit", 100, 1, 1000);
        return self.sync().global(() -> (JsonElement) self.bots().list(any ? BotSelector.parse(p) : null, limit));
    }

    static CompletableFuture<JsonElement> action(JsonObject p, CraftwirePlugin self) {
        self.agentConfig().require(self.agentConfig().allowBots(), "allow-bots");
        return BotActions.run(p, self.bots());
    }

    static CompletableFuture<JsonElement> behavior(JsonObject p, CraftwirePlugin self) {
        self.agentConfig().require(self.agentConfig().allowBots(), "allow-bots");
        return BotBehaviors.handle(p, self.bots());
    }

    /** `location` {world?, x, y, z, yaw?, pitch?}, else the main world's spawn (block centre). */
    private static Location location(JsonObject p) {
        if (!p.has("location")) {
            World w = Bukkit.getWorlds().getFirst();
            return w.getSpawnLocation().toCenterLocation().subtract(0, 0.5, 0);
        }
        JsonObject l = Args.object(p, "location");
        World w = Args.world(l);
        float yaw = (float) (l.has("yaw") ? l.get("yaw").getAsDouble() : 0);
        float pitch = (float) (l.has("pitch") ? l.get("pitch").getAsDouble() : 0);
        return new Location(w, Args.number(l, "x"), Args.number(l, "y"), Args.number(l, "z"), yaw, pitch);
    }
}
