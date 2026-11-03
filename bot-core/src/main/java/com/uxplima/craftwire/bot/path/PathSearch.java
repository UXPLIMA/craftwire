package com.uxplima.craftwire.bot.path;

import com.uxplima.craftwire.bot.path.Pathfinder.Move;
import com.uxplima.craftwire.bot.path.Pathfinder.Step;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * One A* search that can be run in slices: {@link #run} expands at most a number of nodes (or until a deadline) and
 * returns whether the search ended. Owned by the thread of the bot that searches; the block view is read only from
 * there.
 */
public final class PathSearch {
    private record Entry(Step node, double g, long parent) {}

    private record Open(Step node, double f, long key) {}

    private static final long NONE = Long.MIN_VALUE;
    /** How often a slice checks the clock: reading nanoTime per node would cost more than the nodes. */
    private static final int CLOCK_EVERY = 64;

    private final MoveRules rules;
    private final Pathfinder.Options o;
    private final int gx, gy, gz;
    private final Map<Long, Entry> seen = new HashMap<>();
    private final PriorityQueue<Open> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
    private long closestKey;
    private double closestH;
    private int explored;
    private Pathfinder.Result result;

    PathSearch(BlockView view, int sx, int sy, int sz, int gx, int gy, int gz, Pathfinder.Options o) {
        this.rules = new MoveRules(view, o.maxFall(), o.openDoors());
        this.o = o;
        this.gx = gx;
        this.gy = gy;
        this.gz = gz;
        Step start = new Step(sx, sy, sz, rules.standY(sx, sy, sz), Move.START);
        long k = key(sx, sy, sz);
        seen.put(k, new Entry(start, 0, NONE));
        closestKey = k;
        closestH = h(start);
        open.add(new Open(start, closestH, k));
    }

    /** Expands up to `nodes` nodes, stopping early at `deadlineNanos` (System.nanoTime). True once the search ended. */
    public boolean run(int nodes, long deadlineNanos) {
        if (result != null) return true;
        for (int i = 0; i < nodes; i++) {
            if (i % CLOCK_EVERY == CLOCK_EVERY - 1 && System.nanoTime() > deadlineNanos) return false;
            if (open.isEmpty() || explored >= o.maxNodes()) {
                result = finish(closestKey, false);
                return true;
            }
            Open cur = open.poll();
            Entry e = seen.get(cur.key());
            Step n = e.node();
            if (cur.f() > e.g() + h(n) + 1e-9) continue;   // a stale entry
            if (n.x() == gx && n.z() == gz && Math.abs(n.y() - gy) <= 1) {
                result = finish(cur.key(), true);
                return true;
            }
            explored++;
            double hn = h(n);
            if (hn < closestH) {
                closestKey = cur.key();
                closestH = hn;
            }
            expand(n, e.g(), cur.key());
        }
        return false;
    }

    private void expand(Step n, double gn, long from) {
        for (Step next : rules.neighbours(n)) {
            long k = key(next.x(), next.y(), next.z());
            double cost = gn + cost(n, next);
            Entry known = seen.get(k);
            if (known != null && known.g() <= cost) continue;
            // A cheaper way in may arrive with another move (a walk instead of a drop): the newest entry wins.
            seen.put(k, new Entry(next, cost, from));
            open.add(new Open(next, cost + h(next), k));
        }
    }

    /** Set once {@link #run} returned true. */
    public Pathfinder.Result result() {
        return result;
    }

    public int explored() {
        return explored;
    }

    private Pathfinder.Result finish(long end, boolean complete) {
        List<Step> out = new ArrayList<>();
        long k = end;
        while (k != NONE) {
            Entry e = seen.get(k);
            out.add(e.node());
            k = e.parent();
        }
        Collections.reverse(out);
        return new Pathfinder.Result(List.copyOf(out), complete, seen.get(end).node(), Math.min(explored, o.maxNodes()), rules.unloaded());
    }

    private double h(Step n) {
        double dx = n.x() - gx;
        double dy = n.y() - gy;
        double dz = n.z() - gz;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double cost(Step a, Step b) {
        double d = Math.hypot(b.x() - a.x(), b.z() - a.z());
        return switch (b.move()) {
            case JUMP -> d + 0.5;
            case DROP -> d + 0.4 * (a.y() - b.y());
            case SWIM -> Math.max(d, 1) * 2;
            case CLIMB -> 1.5;
            case DOOR -> d + 2;
            default -> d;
        };
    }

    static long key(int x, int y, int z) {
        return ((long) (x + (1 << 25)) << 38) | ((long) (y + 2048) << 26) | (z + (1 << 25));
    }
}
