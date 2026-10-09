package com.uxplima.craftwire.paper.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Comparator;
import com.uxplima.craftwire.paper.Sync;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

final class ServerInfoHandler {
    private ServerInfoHandler() {}

    static JsonElement read() {
        Server s = Bukkit.getServer();
        JsonObject o = new JsonObject();
        o.addProperty("name", s.getName());
        o.addProperty("version", s.getVersion());
        o.addProperty("minecraftVersion", s.getMinecraftVersion());
        JsonArray tps = new JsonArray();
        for (double t : s.getTPS()) tps.add(round(Math.min(t, 20.0)));
        o.add("tps", tps);
        o.addProperty("mspt", round(s.getAverageTickTime()));

        Runtime rt = Runtime.getRuntime();
        JsonObject memory = new JsonObject();
        memory.addProperty("usedMb", (rt.totalMemory() - rt.freeMemory()) >> 20);
        memory.addProperty("maxMb", rt.maxMemory() >> 20);
        o.add("memory", memory);

        JsonObject players = new JsonObject();
        players.addProperty("online", s.getOnlinePlayers().size());
        players.addProperty("max", s.getMaxPlayers());
        JsonArray names = new JsonArray();
        for (Player p : s.getOnlinePlayers()) names.add(p.getName());
        players.add("names", names);
        o.add("players", players);

        JsonArray worlds = new JsonArray();
        for (World w : s.getWorlds()) {
            JsonObject wo = new JsonObject();
            wo.addProperty("name", w.getName());
            wo.addProperty("environment", w.getEnvironment().name().toLowerCase());
            wo.addProperty("players", w.getPlayers().size());
            wo.addProperty("loadedChunks", w.getLoadedChunks().length);
            worlds.add(wo);
        }
        o.add("worlds", worlds);

        JsonArray plugins = new JsonArray();
        Arrays.stream(s.getPluginManager().getPlugins())
                .sorted(Comparator.comparing(Plugin::getName, String.CASE_INSENSITIVE_ORDER))
                .forEach(p -> plugins.add(PluginJson.summary(p)));
        o.add("plugins", plugins);
        if (Sync.folia()) folia(o, s);
        return o;
    }

    /**
     * Folia ticks each region on its own: `tps` above is the global region's. Adds the TPS of the regions at each
     * world's spawn and at every player ([5s, 15s, 1m, 5m, 15m]) and the slowest of them by its 1-minute value.
     */
    private static void folia(JsonObject o, Server s) {
        o.addProperty("folia", true);
        JsonArray regions = new JsonArray();
        JsonObject slowest = null;
        for (World w : s.getWorlds()) {
            Location spawn = w.getSpawnLocation();
            JsonObject r = regionTps(s, w, spawn.getBlockX() >> 4, spawn.getBlockZ() >> 4);
            if (r == null) continue;
            r.addProperty("at", "spawn");
            regions.add(r);
        }
        for (Player p : s.getOnlinePlayers()) {
            Location at = p.getLocation();
            JsonObject r = regionTps(s, at.getWorld(), at.getBlockX() >> 4, at.getBlockZ() >> 4);
            if (r == null) continue;
            r.addProperty("at", "player");
            r.addProperty("player", p.getName());
            regions.add(r);
        }
        for (var e : regions) {
            JsonObject r = e.getAsJsonObject();
            if (slowest == null || minute(r) < minute(slowest)) slowest = r;
        }
        o.add("regions", regions);
        if (slowest != null) o.add("slowestRegion", slowest);
    }

    private static JsonObject regionTps(Server s, World w, int chunkX, int chunkZ) {
        double[] tps;
        try {
            // Folia's API only: the Paper API this plugin compiles against does not have it.
            tps = (double[]) s.getClass().getMethod("getRegionTPS", World.class, int.class, int.class).invoke(s, w, chunkX, chunkZ);
        } catch (ReflectiveOperationException e) {
            return null;
        }
        if (tps == null) return null;   // no region there (nothing loaded)
        JsonObject r = new JsonObject();
        r.addProperty("world", w.getName());
        r.addProperty("chunkX", chunkX);
        r.addProperty("chunkZ", chunkZ);
        JsonArray a = new JsonArray();
        for (double t : tps) a.add(round(Math.min(t, 20.0)));
        r.add("tps", a);
        return r;
    }

    private static double minute(JsonObject region) {
        JsonArray tps = region.getAsJsonArray("tps");
        return tps.size() > 2 ? tps.get(2).getAsDouble() : tps.get(0).getAsDouble();
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
