package com.uxplima.craftwire.botrunner.bot;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Params;
import com.uxplima.craftwire.bot.model.BotKind;
import com.uxplima.craftwire.bot.registry.BotRegistry;
import com.uxplima.craftwire.bot.spawn.SpawnBatch;
import com.uxplima.craftwire.bot.spawn.SpawnQueue;
import com.uxplima.craftwire.bot.spawn.SpawnRequest;
import com.uxplima.craftwire.botrunner.loop.RunnerLoop;
import com.uxplima.craftwire.core.AgentError;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Joins protocol bots a few per tick (perTick per 50 ms, a controlled join storm): each queued join starts its
 * connection on the driver; the bot is registered once it is in the world. Runner loop thread, except {@link #spawn}.
 */
final class RunnerSpawner {
    private record Join(JoinSpec spec, BotDriver driver, SpawnBatch batch) {}

    private final RunnerLoop loop;
    private final BotRegistry<RunnerBot> registry;
    private final Map<BotKind, BotDriver> drivers;
    private final int maxBots;
    private final SpawnQueue<Join> queue = new SpawnQueue<>();
    private volatile int connecting;

    RunnerSpawner(RunnerLoop loop, BotRegistry<RunnerBot> registry, Map<BotKind, BotDriver> drivers, int maxBots) {
        this.loop = loop;
        this.registry = registry;
        this.drivers = drivers;
        this.maxBots = maxBots;
    }

    int pending() {
        return queue.size() + connecting;
    }

    CompletableFuture<JsonObject> spawn(SpawnRequest req, BotKind kind) {
        BotDriver driver = drivers.get(kind);
        if (driver == null) {
            throw new AgentError("KIND_NOT_RUN", "This bot runner does not run " + kind.id() + " bots",
                    "Start a runner with that kind (botrunner_process {kinds}), or pick another kind.");
        }
        String address = Params.string(req.raw(), "address");
        String[] hp = JoinSpec.split(address, kind == BotKind.BEDROCK ? 19132 : 25565);
        int port;
        try {
            port = Integer.parseInt(hp[1]);
        } catch (NumberFormatException e) {
            throw new AgentError("INVALID_PARAMS", "Bad port in address " + address, "Use host or host:port.");
        }
        List<String> names = req.resolveNames(registry::taken);
        if (registry.size() + pending() + names.size() > maxBots) {
            throw new AgentError("RUNNER_FULL", "This runner takes at most " + maxBots + " bots (" + (registry.size() + pending()) + " in use)",
                    "Remove bots, spawn fewer, or connect another runner (the hub spreads spawns over runners).");
        }
        SpawnBatch batch = new SpawnBatch(names, req.detail(), System.currentTimeMillis());
        String account = Params.optString(req.raw(), "account").orElse(null);
        List<Join> joins = new ArrayList<>();
        for (String name : names) {
            if (!registry.claim(name)) {
                batch.failed(name, new AgentError("NAME_TAKEN", name + " is already a bot here", "Pick other names."));
                continue;
            }
            JoinSpec spec = new JoinSpec(name, req.group(), hp[0], port, req.profile(), req.settings(), req.resourcePack(), req.verifyPacks(),
                    account, req.raw());
            joins.add(new Join(spec, driver, batch));
        }
        queue.add(joins, req.perTick());
        return batch.done();
    }

    /** Starts this tick's joins. */
    void tick() {
        for (Join j : queue.drain()) start(j);
    }

    private void start(Join j) {
        connecting++;
        CompletableFuture<RunnerBot> f;
        try {
            f = j.driver().join(j.spec());
        } catch (RuntimeException e) {
            f = CompletableFuture.failedFuture(e);
        }
        f.whenComplete((bot, err) -> loop.execute(() -> {
            connecting--;
            String name = j.spec().name();
            if (err != null) {
                registry.release(name);
                j.batch().failed(name, err instanceof CompletionException && err.getCause() != null ? err.getCause() : err);
                return;
            }
            registry.joined(bot);
            j.batch().joined(name, bot.summary());
        }));
    }

    /** Drops the joins not started yet (remove all, shutdown); their names are free again. */
    void cancelPending() {
        for (Join j : queue.clear()) {
            registry.release(j.spec().name());
            j.batch().failed(j.spec().name(), new AgentError("CANCELLED", "The join was cancelled", "Spawn again."));
        }
    }
}
