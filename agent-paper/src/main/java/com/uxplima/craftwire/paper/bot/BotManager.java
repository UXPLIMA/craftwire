package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.behavior.BehaviorFactory;
import com.uxplima.craftwire.bot.path.PathBudget;
import com.uxplima.craftwire.bot.path.PathCache;
import com.uxplima.craftwire.bot.registry.BotRegistry;
import com.uxplima.craftwire.bot.resourcepack.PackVerifier;
import com.uxplima.craftwire.bot.select.BotSelector;
import com.uxplima.craftwire.bot.spawn.NameRanges;
import com.uxplima.craftwire.bot.spawn.SpawnRequest;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.core.HubConfig;
import com.uxplima.craftwire.paper.Sync;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.logging.Level;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;

/**
 * All bots of this server. On Paper one global loop ticks every bot on the main thread, instead of one scheduler
 * task per bot; on Folia each bot ticks from its own entity scheduler, on the thread of the region it is in. The
 * loop also lets queued joins in, a few per tick.
 */
public final class BotManager {
    private final Plugin plugin;
    private final Sync sync;
    private final BotRegistry<Bot> registry = new BotRegistry<>(Bot::name, Bot::group);
    private final BotServices services;
    private final BotSpawner spawner;
    private final ScheduledTask loop;

    public BotManager(Plugin plugin, Sync sync) {
        this.plugin = plugin;
        this.sync = sync;
        this.services = new BotServices(plugin, sync, PathBudget.standard(), PathCache.standard(), new BehaviorFactory(),
                new PackVerifier(HubConfig.defaultHome().resolve("cache").resolve("resourcepacks")));
        this.spawner = new BotSpawner(this);
        this.loop = sync.repeatGlobal(task -> loop(), 1, 1);
    }

    Plugin plugin() {
        return plugin;
    }

    Sync sync() {
        return sync;
    }

    BotRegistry<Bot> registry() {
        return registry;
    }

    BotServices services() {
        return services;
    }

    public void shutdown() {
        loop.cancel();
        spawner.cancelPending();
        services.packs().close();
        if (Sync.folia()) {
            // The server is stopping and no region ticks any more: nothing can run on a bot's thread now.
            registry.all().forEach(Bot::forgetPlayerFiles);
            registry.clear();
            return;
        }
        for (Bot b : registry.all()) {
            try {
                b.remove();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Could not remove bot " + b.name(), e);
            }
        }
        registry.clear();
    }

    public boolean isBot(String name) {
        return registry.taken(name);
    }

    public Bot get(String name) {
        return registry.get(name);
    }

    public int count() {
        return registry.size();
    }

    /** Runs the work on the named bot's own thread. */
    public <T> CompletableFuture<T> on(String name, Function<Bot, T> work) {
        Bot b;
        try {
            b = get(name);
        } catch (AgentError e) {
            return CompletableFuture.failedFuture(e);
        }
        return on(b, work);
    }

    <T> CompletableFuture<T> on(Bot b, Function<Bot, T> work) {
        return sync.entity(b.bukkit(), () -> work.apply(b))
                .exceptionallyCompose(t -> CompletableFuture.failedFuture(gone(unwrap(t), b.name())));
    }

    /** Runs the work on each selected bot's thread; completes with the results in name order. */
    public <T> CompletableFuture<List<T>> onEach(BotSelector s, Function<Bot, T> work) {
        List<Bot> bots;
        try {
            bots = registry.select(s);
        } catch (AgentError e) {
            return CompletableFuture.failedFuture(e);
        }
        List<CompletableFuture<T>> parts = new ArrayList<>();
        for (Bot b : bots) parts.add(on(b, work));
        return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new))
                .thenApply(v -> parts.stream().map(CompletableFuture::join).toList());
    }

    /** Queues the joins; completes once every bot joined or failed, with the compact SpawnBatch result. */
    public CompletableFuture<JsonObject> spawn(SpawnRequest req, Location base) {
        return spawner.spawn(req, base);
    }

    /** Removes the selected bots; a bot that left on its own meanwhile counts as removed. */
    public CompletableFuture<JsonObject> remove(BotSelector s, boolean listNames) {
        List<Bot> bots;
        try {
            bots = registry.select(s);
        } catch (AgentError e) {
            return CompletableFuture.failedFuture(e);
        }
        return removeAll(bots, listNames);
    }

    public CompletableFuture<JsonObject> removeAll(boolean listNames) {
        spawner.cancelPending();
        return removeAll(List.copyOf(registry.all()), listNames);
    }

    private CompletableFuture<JsonObject> removeAll(List<Bot> bots, boolean listNames) {
        List<CompletableFuture<String>> parts = new ArrayList<>();
        for (Bot b : bots) {
            parts.add(on(b, x -> {
                registry.remove(x);
                x.remove();
                return x.name();
            }).exceptionally(t -> {
                registry.remove(b);
                return b.name();
            }));
        }
        return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new)).thenApply(v -> {
            List<String> names = parts.stream().map(CompletableFuture::join).toList();
            JsonObject r = new JsonObject();
            r.addProperty("removed", names.size());
            if (listNames) r.addProperty("names", NameRanges.compact(names));
            return r;
        });
    }

    /** The bots as summaries (at most `limit`), plus the count; runs on the global thread with racy positions. */
    public JsonObject list(BotSelector s, int limit) {
        List<Bot> bots = s == null
                ? registry.all().stream().sorted((a, c) -> NameRanges.natural(a.name(), c.name())).toList()
                : registry.select(s);
        JsonArray a = new JsonArray();
        for (Bot b : bots.subList(0, Math.min(limit, bots.size()))) a.add(BotJson.summary(b));
        JsonObject r = new JsonObject();
        r.addProperty("count", bots.size());
        r.addProperty("joining", spawner.pending());
        r.add("bots", a);
        return r;
    }

    /** Global region thread, every tick. */
    private void loop() {
        spawner.tick();
        if (Sync.folia()) return;
        long now = System.currentTimeMillis();
        for (Bot b : registry.all()) tickOne(b, now);
    }

    /** One bot tick; a bot that throws is removed so it cannot fail every tick. Bot thread. */
    private Bot.Tick tickOne(Bot b, long now) {
        try {
            Bot.Tick t = b.tick(now);
            if (t != Bot.Tick.ALIVE) registry.remove(b);
            return t;
        } catch (RuntimeException e) {
            plugin.getLogger().log(Level.WARNING, "Bot " + b.name() + " failed and was removed", e);
            registry.remove(b);
            try {
                b.remove();
            } catch (RuntimeException ignored) {
                // already broken
            }
            return Bot.Tick.GONE;
        }
    }

    /** Folia: the bot's own entity scheduler ticks it; on Paper the global loop does. */
    void startTicking(Bot b) {
        if (!Sync.folia()) return;
        sync.repeatEntity(b.bukkit(), task -> {
            // While LEAVING the region finishes the disconnect; the retire callback below then ends the bot.
            if (tickOne(b, System.currentTimeMillis()) == Bot.Tick.GONE) task.cancel();
        }, () -> {
            // The player entity is gone (removed, kicked, or the server took it out): the bot is too.
            registry.remove(b);
            b.retired();
        });
    }

    private static Throwable unwrap(Throwable t) {
        return t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
    }

    private static Throwable gone(Throwable t, String name) {
        if (t instanceof AgentError e && e.code().equals("ENTITY_GONE")) {
            return new AgentError("BOT_NOT_FOUND", "No bot named " + name + " (it left the server)",
                    "bot_spawn creates bots; bot_list shows the bots of every kind.");
        }
        return t;
    }
}
