package com.uxplima.craftwire.paper.handlers;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Comparator;
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
        return o;
    }

    private static double round(double v) {
        return Math.round(v * 100) / 100.0;
    }
}
