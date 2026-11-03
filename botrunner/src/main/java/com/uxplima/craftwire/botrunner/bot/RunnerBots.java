package com.uxplima.craftwire.botrunner.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.model.BotKind;
import com.uxplima.craftwire.bot.registry.BotRegistry;
import com.uxplima.craftwire.bot.select.BotSelector;
import com.uxplima.craftwire.bot.spawn.NameRanges;
import com.uxplima.craftwire.bot.spawn.SpawnRequest;
import com.uxplima.craftwire.botrunner.loop.RunnerLoop;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Every bot of this runner, of every kind: spawning through the drivers, ticking (bot first, then its behavior) and
 * removal. All bot state is owned by the runner loop; hub requests reach it through {@link #on} and {@link #onEach}.
 */
public final class RunnerBots implements AutoCloseable {
    private static final Logger LOG = LogManager.getLogger("craftwire-botrunner");

    private final RunnerServices services;
    private final Map<BotKind, BotDriver> drivers = new EnumMap<>(BotKind.class);
    private final BotRegistry<RunnerBot> registry = new BotRegistry<>(RunnerBot::name, RunnerBot::group);
    private final RunnerSpawner spawner;
    private final int maxBots;

    public RunnerBots(RunnerServices services, List<BotDriver> kinds, int maxBots) {
        this.services = services;
        this.maxBots = maxBots;
        for (BotDriver d : kinds) drivers.put(d.kind(), d);
        this.spawner = new RunnerSpawner(services.loop(), registry, drivers, maxBots);
        services.loop().onTick(this::tick);
    }

    public RunnerServices services() {
        return services;
    }

    public List<BotKind> kinds() {
        return List.copyOf(drivers.keySet());
    }

    public int maxBots() {
        return maxBots;
    }

    public int count() {
        return registry.size();
    }

    public CompletableFuture<JsonObject> spawn(SpawnRequest req, BotKind kind) {
        return services.loop().call(() -> spawner.spawn(req, kind)).thenCompose(f -> f);
    }

    /** Runs the work on the loop with the named bot. */
    public <T> CompletableFuture<T> on(String name, Function<RunnerBot, T> work) {
        return services.loop().call(() -> work.apply(registry.get(name)));
    }

    /** Runs the work on the loop for each selected bot, in name order. */
    public <T> CompletableFuture<List<T>> onEach(BotSelector s, Function<RunnerBot, T> work) {
        return services.loop().call(() -> {
            List<T> out = new ArrayList<>();
            for (RunnerBot b : registry.select(s)) out.add(work.apply(b));
            return out;
        });
    }

    public CompletableFuture<JsonObject> remove(BotSelector s, boolean listNames) {
        return services.loop().call(() -> removeAll(registry.select(s), listNames));
    }

    public CompletableFuture<JsonObject> removeAll(boolean listNames) {
        return services.loop().call(() -> {
            spawner.cancelPending();
            return removeAll(List.copyOf(registry.all()), listNames);
        });
    }

    private JsonObject removeAll(List<RunnerBot> bots, boolean listNames) {
        List<String> names = new ArrayList<>();
        for (RunnerBot b : bots) {
            registry.remove(b);
            try {
                b.behavior().clear(b);
                b.leave("removed");
            } catch (RuntimeException e) {
                LOG.warn("Bot {} did not leave cleanly", b.name(), e);
            }
            names.add(b.name());
        }
        JsonObject r = new JsonObject();
        r.addProperty("removed", names.size());
        if (listNames) r.addProperty("names", NameRanges.compact(names));
        return r;
    }

    public CompletableFuture<JsonObject> list(BotSelector s, int limit) {
        return services.loop().call(() -> {
            List<RunnerBot> bots = s == null
                    ? registry.all().stream().sorted((a, c) -> NameRanges.natural(a.name(), c.name())).toList()
                    : registry.select(s);
            JsonArray a = new JsonArray();
            for (RunnerBot b : bots.subList(0, Math.min(limit, bots.size()))) a.add(b.summary());
            JsonObject r = new JsonObject();
            r.addProperty("count", bots.size());
            r.addProperty("joining", spawner.pending());
            r.add("bots", a);
            return r;
        });
    }

    /** Runner loop, every 50 ms. */
    private void tick(long now) {
        spawner.tick();
        for (RunnerBot b : registry.all()) tickOne(b, now);
    }

    /** A bot that throws is dropped so it cannot fail every tick; one that left on its own leaves the registry. */
    private void tickOne(RunnerBot b, long now) {
        try {
            if (b.tick(now) == RunnerBot.Tick.GONE) {
                registry.remove(b);
                return;
            }
            b.behavior().tick(b, now);
        } catch (RuntimeException e) {
            LOG.warn("Bot {} failed and was removed", b.name(), e);
            registry.remove(b);
            try {
                b.leave("failed: " + e);
            } catch (RuntimeException ignored) {
                // already broken; it is out of the registry either way
            }
        }
    }

    @Override
    public void close() {
        try {
            removeAll(false).get();
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.warn("Could not remove every bot on shutdown", e);
        }
        drivers.values().forEach(BotDriver::close);
        services.packs().close();
    }
}
