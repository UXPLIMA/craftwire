package com.uxplima.craftwire.build;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PicksTest {
    @Test
    void mixFollowsTheWeights() {
        BlockPick m = Picks.mix(new int[] {1, 2}, new int[] {9, 1}, 42);
        int ones = 0;
        for (int x = 0; x < 100; x++) for (int z = 0; z < 100; z++) if (m.pick(x, 0, z) == 1) ones++;
        assertTrue(ones > 8_700 && ones < 9_300, "ones=" + ones);
    }

    @Test
    void mixIsTheSameOnEveryRun() {
        BlockPick a = Picks.mix(new int[] {1, 2, 3}, new int[] {1, 1, 1}, 5);
        BlockPick b = Picks.mix(new int[] {1, 2, 3}, new int[] {1, 1, 1}, 5);
        for (int i = 0; i < 1000; i++) assertEquals(a.pick(i, i * 3, -i), b.pick(i, i * 3, -i));
    }

    @Test
    void anotherSeedGivesAnotherPattern() {
        BlockPick a = Picks.mix(new int[] {1, 2}, new int[] {1, 1}, 1);
        BlockPick b = Picks.mix(new int[] {1, 2}, new int[] {1, 1}, 2);
        int same = 0;
        for (int i = 0; i < 1000; i++) if (a.pick(i, 0, 0) == b.pick(i, 0, 0)) same++;
        assertTrue(same < 700);
    }

    @Test
    void gradientGoesFromFirstToLast() {
        BlockPick g = Picks.gradient('y', 0, 9, new int[] {1, 2, 3}, 0);
        assertEquals(1, g.pick(0, 0, 0));
        assertEquals(3, g.pick(0, 9, 0));
        assertEquals(1, g.pick(0, -5, 0));
    }
}
