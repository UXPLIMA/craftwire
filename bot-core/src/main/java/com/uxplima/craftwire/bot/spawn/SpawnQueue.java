package com.uxplima.craftwire.bot.spawn;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Joins waiting for their tick: a spawn of 1000 bots joins `perTick` of them per tick instead of all in one tick
 * (which would stall the server for seconds). Thread-safe: spawns add from the hub connection, the tick drains.
 *
 * @param <T> one pending join
 */
public final class SpawnQueue<T> {
    private record Pending<T>(T join, int perTick) {}

    private final Queue<Pending<T>> pending = new ConcurrentLinkedQueue<>();

    public void add(List<T> joins, int perTick) {
        for (T j : joins) pending.add(new Pending<>(j, perTick));
    }

    /** The joins due this tick: as many as the oldest pending spawn's rate allows. */
    public List<T> drain() {
        Pending<T> head = pending.peek();
        if (head == null) return List.of();
        List<T> out = new ArrayList<>();
        for (int i = 0; i < head.perTick(); i++) {
            Pending<T> p = pending.poll();
            if (p == null) break;
            out.add(p.join());
        }
        return out;
    }

    public int size() {
        return pending.size();
    }

    /** Takes every pending join (on removal of all bots or shutdown), so their names can be released. */
    public List<T> clear() {
        List<T> out = new ArrayList<>();
        Pending<T> p;
        while ((p = pending.poll()) != null) out.add(p.join());
        return out;
    }
}
