package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.paper.Sync;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

/**
 * All bots of this server. Each bot ticks from its own entity scheduler and every action runs there too: on Paper
 * that is the main thread, on Folia the thread of the region the bot is in (it follows the bot across regions).
 */
public final class BotManager {
    private final Plugin plugin;
    private final Sync sync;
    private final Map<String, Bot> bots = new ConcurrentHashMap<>();
    /** Names of bots between the name check and their join, so two spawns cannot take the same name. */
    private final Set<String> joining = ConcurrentHashMap.newKeySet();

    public BotManager(Plugin plugin, Sync sync) {
        this.plugin = plugin;
        this.sync = sync;
    }

    Plugin plugin() {
        return plugin;
    }

    Sync sync() {
        return sync;
    }

    public void shutdown() {
        if (Sync.folia()) {
            // The server is stopping and no region ticks any more: nothing can run on a bot's thread now.
            bots.values().forEach(Bot::forgetPlayerFiles);
            bots.clear();
            return;
        }
        for (Bot b : List.copyOf(bots.values())) {
            try {
                b.remove();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Could not remove bot " + b.name(), e);
            }
        }
        bots.clear();
    }

    public boolean isBot(String name) {
        String k = key(name);
        return bots.containsKey(k) || joining.contains(k);
    }

    public Bot get(String name) {
        Bot b = bots.get(key(name));
        if (b == null) {
            throw new AgentError("BOT_NOT_FOUND", "No bot named " + name,
                    "bot_spawn creates bots; world_query {action:'players'} lists them (bot:true).");
        }
        return b;
    }

    /** Runs the work on the named bot's own thread. */
    public <T> CompletableFuture<T> on(String name, Function<Bot, T> work) {
        Bot b;
        try {
            b = get(name);
        } catch (AgentError e) {
            return CompletableFuture.failedFuture(e);
        }
        return sync.entity(b.bukkit(), () -> work.apply(b))
                .exceptionallyCompose(t -> CompletableFuture.failedFuture(gone(unwrap(t), name)));
    }

    /**
     * Joins the bots at `at` on the thread that owns that spot, after its chunk is loaded, and starts their ticks.
     * Completes with each bot's summary.
     */
    public CompletableFuture<List<JsonObject>> spawn(List<String> names, Location at) {
        List<String> claimed = new ArrayList<>();
        for (String n : names) {
            BotNames.validate(n);
            if (isBot(n) || Bukkit.getPlayerExact(n) != null || !joining.add(key(n))) {
                claimed.forEach(c -> joining.remove(key(c)));
                return CompletableFuture.failedFuture(
                        new AgentError("NAME_TAKEN", n + " is already online", "Pick other names, or a different namePrefix."));
            }
            claimed.add(n);
        }
        return at.getWorld().getChunkAtAsync(at)
                .thenCompose(chunk -> sync.region(at, () -> {
                    List<JsonObject> out = new ArrayList<>();
                    for (String n : names) {
                        Bot b = Bot.join(n, at);
                        bots.put(key(n), b);
                        joining.remove(key(n));
                        startTicking(b);
                        out.add(BotJson.summary(b));
                    }
                    return out;
                }))
                .whenComplete((r, t) -> names.forEach(n -> joining.remove(key(n))));
    }

    public CompletableFuture<String> remove(String name) {
        return on(name, b -> {
            bots.remove(key(b.name()), b);
            b.remove();
            return b.name();
        });
    }

    /** Removes every bot; a bot that left on its own meanwhile counts as removed. */
    public CompletableFuture<List<String>> removeAll() {
        List<CompletableFuture<String>> parts = new ArrayList<>();
        for (Bot b : List.copyOf(bots.values())) {
            parts.add(remove(b.name()).exceptionally(t -> {
                bots.remove(key(b.name()), b);
                return b.name();
            }));
        }
        return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new))
                .thenApply(v -> parts.stream().map(CompletableFuture::join).toList());
    }

    private void startTicking(Bot b) {
        sync.repeatEntity(b.bukkit(), task -> {
            try {
                switch (b.tick(System.currentTimeMillis())) {
                    case ALIVE -> { }
                    case GONE -> {
                        task.cancel();
                        bots.remove(key(b.name()), b);
                    }
                    // The server is taking the player out; the retire callback below finishes the bot.
                    case LEAVING -> bots.remove(key(b.name()), b);
                }
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Bot " + b.name() + " failed and was removed", e);
                task.cancel();
                bots.remove(key(b.name()), b);
                try { b.remove(); } catch (RuntimeException ignored) { /* already broken */ }
            }
        }, () -> {
            // The player entity is gone (removed, kicked, or the server took it out): the bot is too.
            bots.remove(key(b.name()), b);
            b.retired();
        });
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }

    private static Throwable gone(Throwable t, String name) {
        if (t instanceof AgentError e && e.code().equals("ENTITY_GONE")) {
            return new AgentError("BOT_NOT_FOUND", "No bot named " + name + " (it left the server)",
                    "bot_spawn creates bots; world_query {action:'players'} lists them (bot:true).");
        }
        return t;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
