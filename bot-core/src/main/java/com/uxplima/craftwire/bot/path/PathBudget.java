package com.uxplima.craftwire.bot.path;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * How many A* nodes all bots of one process may expand per 50 ms tick window, so a hundred bots asked to move_to at
 * once spread their searches over a few ticks instead of stalling one tick. Each search slice gets at most
 * {@code perSearch} nodes and never fewer than {@code floor}, so no search starves. Thread-safe (Folia region
 * threads and bot runner threads share one budget).
 */
public final class PathBudget {
    private static final long WINDOW_NANOS = 50_000_000L;

    private final int perWindow;
    private final int perSearch;
    private final int floor;
    private final long sliceNanos;
    private final AtomicInteger left;
    private final AtomicLong windowStart = new AtomicLong(System.nanoTime());

    /**
     * @param perWindow  nodes for all searches per 50 ms
     * @param perSearch  the most one search may take in one slice
     * @param floor      what a search gets even when the window is used up
     * @param sliceMicros the wall-clock limit of one slice
     */
    public PathBudget(int perWindow, int perSearch, int floor, long sliceMicros) {
        this.perWindow = perWindow;
        this.perSearch = perSearch;
        this.floor = floor;
        this.sliceNanos = sliceMicros * 1000;
        this.left = new AtomicInteger(perWindow);
    }

    /**
     * 60k nodes per tick for everyone, 15k and 2 ms per search slice: a 40k-node search (the old move_to limit, run
     * in one go) now spreads over three ticks.
     */
    public static PathBudget standard() {
        return new PathBudget(60_000, 15_000, 500, 2_000);
    }

    /** The nodes this slice may expand. */
    public int take() {
        roll();
        while (true) {
            int have = left.get();
            int grant = Math.min(perSearch, Math.max(have, 0));
            if (left.compareAndSet(have, have - grant)) return Math.max(grant, floor);
        }
    }

    /** Returns what a slice did not use. */
    public void giveBack(int unused) {
        if (unused > 0) left.addAndGet(unused);
    }

    /** The System.nanoTime() at which a slice that starts now must stop. */
    public long deadline() {
        return System.nanoTime() + sliceNanos;
    }

    /** Runs one slice of `search` within this budget; true once it ended. */
    public boolean slice(PathSearch search) {
        int nodes = take();
        int before = search.explored();
        boolean done = search.run(nodes, deadline());
        giveBack(nodes - (search.explored() - before));
        return done;
    }

    private void roll() {
        long now = System.nanoTime();
        long start = windowStart.get();
        if (now - start >= WINDOW_NANOS && windowStart.compareAndSet(start, now)) left.set(perWindow);
    }
}
