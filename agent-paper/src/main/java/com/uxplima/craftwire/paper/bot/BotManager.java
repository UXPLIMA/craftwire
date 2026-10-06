package com.uxplima.craftwire.paper.bot;

import com.uxplima.craftwire.core.AgentError;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/** All bots of this server, ticked by one repeating task on the main thread. */
public final class BotManager {
    private final Plugin plugin;
    private final Map<String, Bot> bots = new LinkedHashMap<>();
    private BukkitTask task;

    public BotManager(Plugin plugin) {
        this.plugin = plugin;
    }

    Plugin plugin() {
        return plugin;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void shutdown() {
        if (task != null) task.cancel();
        removeAll();
    }

    public boolean isBot(String name) {
        return bots.containsKey(key(name));
    }

    public Bot get(String name) {
        Bot b = bots.get(key(name));
        if (b == null) {
            throw new AgentError("BOT_NOT_FOUND", "No bot named " + name,
                    "bot_spawn creates bots; world_query {action:'players'} lists them (bot:true).");
        }
        return b;
    }

    public List<Bot> spawn(List<String> names, Location at) {
        for (String n : names) {
            BotNames.validate(n);
            if (isBot(n) || Bukkit.getPlayerExact(n) != null) {
                throw new AgentError("NAME_TAKEN", n + " is already online", "Pick other names, or a different namePrefix.");
            }
        }
        List<Bot> out = new ArrayList<>();
        for (String n : names) {
            Bot b = Bot.join(n, at);
            bots.put(key(n), b);
            out.add(b);
        }
        return out;
    }

    public Bot remove(String name) {
        Bot b = get(name);
        bots.remove(key(name));
        b.remove();
        return b;
    }

    public List<String> removeAll() {
        List<String> names = new ArrayList<>();
        for (Bot b : List.copyOf(bots.values())) {
            names.add(b.name());
            try {
                b.remove();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Could not remove bot " + b.name(), e);
            }
        }
        bots.clear();
        return names;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Iterator<Bot> it = bots.values().iterator(); it.hasNext(); ) {
            Bot b = it.next();
            try {
                if (!b.tick(now)) it.remove();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Bot " + b.name() + " failed and was removed", e);
                it.remove();
                try { b.remove(); } catch (RuntimeException ignored) { /* already broken */ }
            }
        }
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
