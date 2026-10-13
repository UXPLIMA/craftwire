package com.uxplima.craftwire.build;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A named build: parts stacked in the order they were first run (a later part covers an earlier one), and under them
 * the world as it was before the project touched each position. A position shows its topmost part, else the original.
 */
public final class Project {
    public record Source(String code, String file) {}

    public record History(long seq, String part, LongIntMap previous, Source previousSource) {}

    private final String name;
    private final String world;
    private Transform frame;
    private final List<String> order = new ArrayList<>();
    private final Map<String, LongIntMap> parts = new HashMap<>();
    private final Map<String, Source> sources = new HashMap<>();
    private final LongIntMap original = new LongIntMap(1024);
    private final Deque<History> history = new ArrayDeque<>();
    private long nextSeq = 1;

    public Project(String name, String world, Transform frame) {
        this.name = name;
        this.world = world;
        this.frame = frame;
    }

    public String name() { return name; }
    public String world() { return world; }
    public synchronized Transform frame() { return frame; }
    public synchronized void setFrame(Transform t) { frame = t; }
    public synchronized List<String> parts() { return List.copyOf(order); }
    public synchronized LongIntMap part(String part) { return parts.get(part); }
    public synchronized Source source(String part) { return sources.get(part); }
    public LongIntMap original() { return original; }
    public Deque<History> history() { return history; }

    /** The state shown at pos if `part` held `replacement` (null = the part is gone); NONE when nothing is known. */
    private int visible(long pos, String part, LongIntMap replacement) {
        boolean listed = order.contains(part);
        if (!listed && replacement != null) {
            int s = replacement.get(pos);
            if (s != LongIntMap.NONE) return s;
        }
        for (int i = order.size() - 1; i >= 0; i--) {
            String n = order.get(i);
            LongIntMap m = n.equals(part) ? replacement : parts.get(n);
            if (m == null) continue;
            int s = m.get(pos);
            if (s != LongIntMap.NONE) return s;
        }
        return original.get(pos);
    }

    private boolean covered(long pos, String part, LongIntMap replacement) {
        if (replacement != null && replacement.containsKey(pos)) return true;
        for (String n : order) {
            if (n.equals(part)) continue;
            LongIntMap m = parts.get(n);
            if (m != null && m.containsKey(pos)) return true;
        }
        return false;
    }

    /** What changes in the world if `part` becomes `next` (null removes it). Nothing is committed. */
    public synchronized Changes stage(String part, LongIntMap next) {
        LongIntMap prev = parts.get(part);
        Changes c = new Changes();
        if (prev != null) prev.forEach((pos, s) -> consider(c, pos, part, prev, next));
        if (next != null) next.forEach((pos, s) -> {
            if (prev == null || !prev.containsKey(pos)) consider(c, pos, part, prev, next);
        });
        return c;
    }

    private void consider(Changes c, long pos, String part, LongIntMap prev, LongIntMap next) {
        int before = visible(pos, part, prev);
        int after = visible(pos, part, next);
        if (after == LongIntMap.NONE) return;   // goes back to a world we never changed
        if (before == after && before != LongIntMap.NONE && original.containsKey(pos)) return;
        c.put(pos, after, !covered(pos, part, next));
    }

    /** Makes `next` the part (null removes it) once its changes are in the world. */
    public synchronized void commit(String part, LongIntMap next, Source source, boolean record) {
        LongIntMap prev = parts.get(part);
        if (record) history.push(new History(nextSeq++, part, prev, sources.get(part)));
        if (next == null) {
            parts.remove(part);
            sources.remove(part);
            order.remove(part);
            return;
        }
        if (!parts.containsKey(part)) order.add(part);
        parts.put(part, next);
        if (source != null) sources.put(part, source);
    }

    public synchronized History popHistory() {
        return history.poll();
    }

    /** Every position the project changed, back to its original. */
    public synchronized Changes clearAll() {
        Changes c = new Changes();
        LongIntMap seen = new LongIntMap(1024);
        for (LongIntMap m : parts.values()) {
            m.forEach((pos, s) -> {
                if (seen.put(pos, 0) != LongIntMap.NONE) return;
                int o = original.get(pos);
                if (o != LongIntMap.NONE) c.put(pos, o, true);
            });
        }
        return c;
    }

    /** Forgets parts, history and originals (after clearAll was applied, or on commit). */
    public synchronized void reset() {
        parts.clear();
        sources.clear();
        order.clear();
        history.clear();
    }

    public synchronized void recordOriginal(long pos, int state) {
        if (!original.containsKey(pos)) original.put(pos, state);
    }

    public synchronized int originalOf(long pos) {
        return original.get(pos);
    }

    /** For the store: restores history in order (oldest first) and the sequence counter. */
    synchronized void restoreHistory(List<History> oldestFirst) {
        history.clear();
        for (History h : oldestFirst) {
            history.push(h);
            nextSeq = Math.max(nextSeq, h.seq() + 1);
        }
    }

    /** For the store: puts a part back as loaded. */
    synchronized void restorePart(String part, LongIntMap blocks, Source source) {
        if (!parts.containsKey(part)) order.add(part);
        parts.put(part, blocks);
        if (source != null) sources.put(part, source);
    }
}
