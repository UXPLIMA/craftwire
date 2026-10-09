package com.uxplima.craftwire.core.profile;

import static org.junit.jupiter.api.Assertions.*;

import java.util.SplittableRandom;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class ProfilerTest {
    private static final long MS = 1_000_000L;
    private static final long TICK = 50 * MS;

    /** Whether samples from `firstSample` on, `step` apart, land in the first `windowMs` of any 50 ms tick within 3 s. */
    private static boolean sees(long firstSample, int windowMs, LongSupplier step) {
        for (long t = firstSample; t < 3_000 * MS; t += step.getAsLong()) {
            if (t % TICK < windowMs * MS) return true;
        }
        return false;
    }

    @Test
    void aFixedTenMillisecondRateMissesWorkAtTheSameMomentOfEveryTick() {
        assertFalse(sees(8 * MS, 8, () -> 10 * MS));
    }

    @Test
    void workAtTheSameMomentOfEveryTickIsSampledWhateverTheSamplersPhase() {
        RandomGenerator random = new SplittableRandom(7);
        for (long phase = 0; phase < TICK; phase += MS / 2) {
            assertTrue(sees(phase, 8, () -> Profiler.nextDelayNanos(10, random)), "phase " + phase);
        }
    }

    @Test
    void theDelayStaysWithinHalfAnIntervalOfItAndAveragesIt() {
        RandomGenerator random = new SplittableRandom(7);
        long sum = 0;
        int n = 100_000;
        for (int i = 0; i < n; i++) {
            long d = Profiler.nextDelayNanos(10, random);
            assertTrue(d >= 5 * MS && d < 15 * MS, String.valueOf(d));
            sum += d;
        }
        assertEquals(10.0, sum / (double) n / MS, 0.05);
    }

    /** Spins on a thread with this name until the returned handle is interrupted. */
    private static Thread busy(String name) {
        Thread t = new Thread(() -> {
            long x = 0;
            while (!Thread.currentThread().isInterrupted()) x += System.nanoTime() & 7;
            if (x == 42) System.out.print("");
        }, name);
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static com.google.gson.JsonObject profile(String label, java.util.function.Predicate<String> threads) throws InterruptedException {
        Profiler.Session s = Profiler.start(label, threads, 5, OwnerIndex.builder().build(), f -> null);
        Thread.sleep(300);
        return s.finish(5);
    }

    @Test
    void threadsMatchedByAPatternAreListedEvenWhenThereIsOnlyOne() throws InterruptedException {
        Thread t = busy("Test Region Thread #0");
        try {
            com.google.gson.JsonObject r = profile("Test Region Thread #*", n -> n.startsWith("Test Region Thread"));
            assertEquals("Test Region Thread #0", r.getAsJsonArray("threads").get(0).getAsJsonObject().get("name").getAsString(), r.toString());
            assertTrue(r.getAsJsonArray("threads").get(0).getAsJsonObject().get("samples").getAsInt() > 0, r.toString());
        } finally {
            t.interrupt();
        }
    }

    @Test
    void theOneNamedGameThreadIsNotListed() throws InterruptedException {
        Thread t = busy("Test Server thread");
        try {
            com.google.gson.JsonObject r = profile("Test Server thread", "Test Server thread"::equals);
            assertFalse(r.has("threads"), r.toString());
            assertTrue(r.get("samples").getAsInt() > 0, r.toString());
        } finally {
            t.interrupt();
        }
    }
}
