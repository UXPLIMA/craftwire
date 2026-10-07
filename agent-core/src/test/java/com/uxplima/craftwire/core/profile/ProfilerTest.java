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
}
