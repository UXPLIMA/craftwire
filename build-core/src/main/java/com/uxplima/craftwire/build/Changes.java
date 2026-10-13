package com.uxplima.craftwire.build;

import java.util.HashMap;
import java.util.Map;

/** What a stage wants the world to become: position -> state, and how many of those go back to the original. */
public final class Changes {
    private final LongIntMap targets = new LongIntMap(256);
    private int removed;

    void put(long pos, int state, boolean backToOriginal) {
        targets.put(pos, state);
        if (backToOriginal) removed++;
    }

    public LongIntMap targets() {
        return targets;
    }

    public int size() {
        return targets.size();
    }

    public int removed() {
        return removed;
    }

    public static long chunkKey(int cx, int cz) {
        return (long) cx << 32 | (cz & 0xFFFFFFFFL);
    }

    public Map<Long, long[]> byChunk() {
        Map<Long, int[]> counts = new HashMap<>();
        targets.forEach((pos, s) -> counts.computeIfAbsent(chunkKey(Pos.x(pos) >> 4, Pos.z(pos) >> 4), k -> new int[1])[0]++);
        Map<Long, long[]> out = new HashMap<>();
        Map<Long, int[]> fill = new HashMap<>();
        counts.forEach((k, n) -> {
            out.put(k, new long[n[0]]);
            fill.put(k, new int[1]);
        });
        targets.forEach((pos, s) -> {
            long k = chunkKey(Pos.x(pos) >> 4, Pos.z(pos) >> 4);
            out.get(k)[fill.get(k)[0]++] = pos;
        });
        return out;
    }
}
