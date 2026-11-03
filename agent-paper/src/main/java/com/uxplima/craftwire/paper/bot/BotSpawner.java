package com.uxplima.craftwire.paper.bot;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.spawn.SpawnBatch;
import com.uxplima.craftwire.bot.spawn.SpawnQueue;
import com.uxplima.craftwire.bot.spawn.SpawnRequest;
import com.uxplima.craftwire.bot.spawn.Spread;
import com.uxplima.craftwire.core.AgentError;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Turns a spawn into queued joins and lets them in `perTick` at a time: each join loads its chunk asynchronously,
 * then runs on the thread that owns that spot. Spread spawns put each bot on the surface of a random column.
 */
final class BotSpawner {
    /** One queued join. `surface`: find the top block of the column at `at` instead of using its y. */
    private record Pending(JoinSpec spec, Location at, boolean surface, SpawnBatch batch) {}

    private final BotManager bots;
    private final SpawnQueue<Pending> queue = new SpawnQueue<>();

    BotSpawner(BotManager bots) {
        this.bots = bots;
    }

    int pending() {
        return queue.size();
    }

    /** Claims the names (all or none) and queues the joins. Any thread. */
    CompletableFuture<JsonObject> spawn(SpawnRequest req, Location base) {
        List<String> names = req.resolveNames(n -> bots.registry().taken(n) || Bukkit.getPlayerExact(n) != null);
        List<String> claimed = new ArrayList<>();
        for (String n : names) {
            if (Bukkit.getPlayerExact(n) != null || !bots.registry().claim(n)) {
                claimed.forEach(bots.registry()::release);
                return CompletableFuture.failedFuture(new AgentError("NAME_TAKEN", n + " is already online",
                        "Pick other names, or a different namePrefix."));
            }
            claimed.add(n);
        }
        SpawnBatch batch = new SpawnBatch(names, req.detail(), System.currentTimeMillis());
        List<Pending> joins = new ArrayList<>();
        List<Spread.Column> columns = req.spreadRadius() > 0
                ? Spread.columns(base.getX(), base.getZ(), req.spreadRadius(), names.size(), req.seed()) : List.of();
        for (int i = 0; i < names.size(); i++) {
            Location at = base.clone();
            if (!columns.isEmpty()) {
                at.setX(columns.get(i).x() + 0.5);
                at.setZ(columns.get(i).z() + 0.5);
            }
            joins.add(new Pending(JoinSpec.of(names.get(i), req), at, !columns.isEmpty(), batch));
        }
        queue.add(joins, req.perTick());
        return batch.done();
    }

    /** Global thread, every tick: starts this tick's joins. */
    void tick() {
        for (Pending p : queue.drain()) start(p);
    }

    /** Drops joins that have not started; their names are freed and their spawns report them as failed. */
    void cancelPending() {
        for (Pending p : queue.clear()) {
            bots.registry().release(p.spec().name());
            p.batch().failed(p.spec().name(), new AgentError("CANCELLED", "The spawn was cancelled", "Spawn again."));
        }
    }

    private void start(Pending p) {
        World w = p.at().getWorld();
        int cx = p.at().getBlockX() >> 4;
        int cz = p.at().getBlockZ() >> 4;
        w.getChunkAtAsync(cx, cz)
                .thenCompose(chunk -> bots.sync().region(w, cx, cz, () -> join(p)))
                .whenComplete((bot, t) -> {
                    if (t == null) return;
                    bots.registry().release(p.spec().name());
                    p.batch().failed(p.spec().name(), t instanceof CompletionException && t.getCause() != null ? t.getCause() : t);
                });
    }

    /** On the thread that owns the spot. */
    private Bot join(Pending p) {
        Location at = p.surface() ? surface(p.at()) : p.at();
        Bot b = BotJoin.join(p.spec(), at, bots.services());
        bots.registry().joined(b);
        bots.startTicking(b);
        p.batch().joined(b.name(), BotJson.summary(b));
        return b;
    }

    /** Standing on the column's top solid block (leaves do not count, so bots do not end up in tree tops). */
    private static Location surface(Location at) {
        World w = at.getWorld();
        int y = w.getHighestBlockYAt(at.getBlockX(), at.getBlockZ(), HeightMap.MOTION_BLOCKING_NO_LEAVES);
        return new Location(w, at.getX(), y + 1, at.getZ(), at.getYaw(), at.getPitch());
    }
}
