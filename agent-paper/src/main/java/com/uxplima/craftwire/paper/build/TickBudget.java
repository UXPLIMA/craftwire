package com.uxplima.craftwire.paper.build;

/** At most 8 ms of build writes per 50 ms window on each thread (Paper: the main thread; Folia: each region thread). */
final class TickBudget {
    static final long BUDGET_NANOS = 8_000_000L;
    private static final long WINDOW_NANOS = 50_000_000L;
    private static final ThreadLocal<long[]> WINDOW = ThreadLocal.withInitial(() -> new long[] {System.nanoTime(), 0});

    private TickBudget() {}

    static boolean exhausted() {
        long[] w = WINDOW.get();
        long now = System.nanoTime();
        if (now - w[0] >= WINDOW_NANOS) {
            w[0] = now;
            w[1] = 0;
        }
        return w[1] >= BUDGET_NANOS;
    }

    static void spend(long nanos) {
        WINDOW.get()[1] += nanos;
    }
}
