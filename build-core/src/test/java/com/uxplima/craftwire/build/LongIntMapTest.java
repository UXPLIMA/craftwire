package com.uxplima.craftwire.build;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class LongIntMapTest {
    @Test
    void behavesLikeAHashMapThroughGrowth() {
        LongIntMap m = new LongIntMap();
        Map<Long, Integer> ref = new HashMap<>();
        SplittableRandom r = new SplittableRandom(7);
        for (int i = 0; i < 200_000; i++) {
            long k = Pos.pack(r.nextInt(-500, 500), r.nextInt(-64, 320), r.nextInt(-500, 500));
            int v = r.nextInt(0, 30_000);
            Integer old = ref.put(k, v);
            assertEquals(old == null ? LongIntMap.NONE : old, m.put(k, v));
        }
        assertEquals(ref.size(), m.size());
        ref.forEach((k, v) -> assertEquals(v, m.get(k)));
        int[] seen = {0};
        m.forEach((k, v) -> { assertEquals(ref.get(k), v); seen[0]++; });
        assertEquals(ref.size(), seen[0]);
    }

    @Test
    void missingKeysAreNone() {
        assertEquals(LongIntMap.NONE, new LongIntMap().get(Pos.pack(1, 2, 3)));
    }

    @Test
    void refusesNegativeValues() {
        assertThrows(IllegalArgumentException.class, () -> new LongIntMap().put(1L, -1));
    }

    @Test
    void copyIsIndependent() {
        LongIntMap a = new LongIntMap();
        a.put(1L, 5);
        LongIntMap b = a.copy();
        b.put(1L, 6);
        assertEquals(5, a.get(1L));
    }
}
