package com.uxplima.craftwire.bot.behavior;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Params;
import java.util.SplittableRandom;

/**
 * "Every interval, give or take jitter": when a behavior acts next. Many bots with the same interval drift apart
 * through the jitter instead of all acting on the same tick.
 */
final class Cadence {
    private final long intervalMs;
    private final long jitterMs;
    private final SplittableRandom random;
    private long next;

    Cadence(long intervalMs, long jitterMs, SplittableRandom random) {
        this.intervalMs = intervalMs;
        this.jitterMs = jitterMs;
        this.random = random;
    }

    /** `intervalMs` (default `interval`, at least 50) and `jitterMs` (default a fifth of the interval). */
    static Cadence parse(JsonObject p, long interval, SplittableRandom random) {
        long i = Params.clampedLong(p, "intervalMs", interval, 50L, 3_600_000L);
        long j = Params.clampedLong(p, "jitterMs", i / 5, 0L, i);
        return new Cadence(i, j, random);
    }

    /** True when it is time to act; the next time is scheduled at once. The first call fires after a random part of one interval. */
    boolean due(long now) {
        if (next == 0) {
            next = now + random.nextLong(Math.max(1, intervalMs));
            return false;
        }
        if (now < next) return false;
        long spread = jitterMs == 0 ? 0 : random.nextLong(-jitterMs, jitterMs + 1);
        next = now + Math.max(50, intervalMs + spread);
        return true;
    }

    /** Pushes the next action `ms` from now. */
    void delay(long now, long ms) {
        next = now + ms;
    }

    long intervalMs() {
        return intervalMs;
    }

    /** A per-bot random: the same bot name gives the same sequence, so runs repeat. */
    static SplittableRandom randomFor(String name, long seed) {
        return new SplittableRandom(name.hashCode() * 0x9E3779B97F4A7C15L ^ seed);
    }
}
