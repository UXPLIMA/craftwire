package com.uxplima.craftwire.paper.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.CraftwirePlugin;
import com.uxplima.craftwire.paper.bot.Bot;
import com.uxplima.craftwire.paper.bot.BotActions;
import com.uxplima.craftwire.paper.bot.BotJson;
import com.uxplima.craftwire.paper.bot.BotNames;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

final class BotHandler {
    private BotHandler() {}

    static CompletableFuture<JsonElement> spawn(JsonObject p, CraftwirePlugin self) {
        self.agentConfig().require(self.agentConfig().allowBots(), "allow-bots");
        return self.sync().global(() -> {
            List<String> names = new ArrayList<>();
            if (p.has("names")) {
                for (JsonElement e : Args.array(p, "names")) names.add(BotNames.validate(e.getAsString()));
            } else {
                int count = Math.clamp(Args.optInt(p, "count").orElse(1), 1, 20);
                String prefix = Args.optString(p, "namePrefix").orElse("Bot");
                names = BotNames.allocate(prefix, count, n -> self.bots().isBot(n) || Bukkit.getPlayerExact(n) != null);
            }
            JsonArray out = new JsonArray();
            for (Bot b : self.bots().spawn(names, location(p))) out.add(BotJson.summary(b));
            JsonObject r = new JsonObject();
            r.add("bots", out);
            return r;
        });
    }

    static CompletableFuture<JsonElement> remove(JsonObject p, CraftwirePlugin self) {
        self.agentConfig().require(self.agentConfig().allowBots(), "allow-bots");
        return self.sync().global(() -> {
            JsonArray removed = new JsonArray();
            if (Args.bool(p, "all", false)) {
                self.bots().removeAll().forEach(removed::add);
            } else {
                removed.add(self.bots().remove(Args.string(p, "name")).name());
            }
            JsonObject r = new JsonObject();
            r.add("removed", removed);
            return r;
        });
    }

    static CompletableFuture<JsonElement> action(JsonObject p, CraftwirePlugin self) {
        self.agentConfig().require(self.agentConfig().allowBots(), "allow-bots");
        return BotActions.run(p, self.bots(), self.sync());
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
