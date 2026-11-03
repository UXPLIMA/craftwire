package com.uxplima.craftwire.bot.path;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Recent complete paths by (world, start block, goal block, options): when many bots spawned at one spot are sent to
 * one target, the first search serves them all. Entries expire after a few seconds because blocks change.
 * Thread-safe.
 */
public final class PathCache {
    private record Key(String world, int sx, int sy, int sz, int gx, int gy, int gz, Pathfinder.Options options) {}

    private record Cached(Pathfinder.Result result, long until) {}

    private final int capacity;
    private final long ttlMs;
    private final Map<Key, Cached> entries;

    public PathCache(int capacity, long ttlMs) {
        this.capacity = capacity;
        this.ttlMs = ttlMs;
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<Key, Cached> eldest) {
                return size() > PathCache.this.capacity;
            }
        };
    }

    public static PathCache standard() {
        return new PathCache(256, 5_000);
    }

    public synchronized Pathfinder.Result get(String world, int sx, int sy, int sz, int gx, int gy, int gz, Pathfinder.Options o, long now) {
        Key k = new Key(world, sx, sy, sz, gx, gy, gz, o);
        Cached e = entries.get(k);
        if (e == null) return null;
        if (e.until() < now) {
            entries.remove(k);
            return null;
        }
        return e.result();
    }

    /** Keeps complete paths only: an incomplete one depends on which chunks were loaded. */
    public synchronized void put(String world, int sx, int sy, int sz, int gx, int gy, int gz, Pathfinder.Options o, Pathfinder.Result r, long now) {
        if (!r.complete()) return;
        entries.put(new Key(world, sx, sy, sz, gx, gy, gz, o), new Cached(r, now + ttlMs));
    }
}
