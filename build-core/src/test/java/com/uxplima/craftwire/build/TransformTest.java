package com.uxplima.craftwire.build;

import static org.junit.jupiter.api.Assertions.*;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class TransformTest {
    final FakePalette pal = new FakePalette();

    static int[] map(Transform t, int x, int y, int z) {
        return new int[] {t.worldX(x, z), t.worldY(y), t.worldZ(x, z)};
    }

    @Test
    void quarterTurnIsClockwiseFromAbove() {
        Transform t = Transform.of(0, 0, 0, 1, "");
        assertArrayEquals(new int[] {1, 0, 0}, map(t, 0, 0, -1));   // north -> east
        assertArrayEquals(new int[] {0, 0, 1}, map(t, 1, 0, 0));    // east -> south
    }

    @Test
    void mirrorZFlipsNorthAndSouth() {
        Transform t = Transform.of(0, 0, 0, 0, "z");
        assertArrayEquals(new int[] {3, 0, -2}, map(t, 3, 0, 2));
        assertEquals("minecraft:oak_stairs[facing=south]", pal.name(t.state(pal.id("oak_stairs[facing=north]"), pal)));
        assertEquals("minecraft:oak_stairs[facing=east]", pal.name(t.state(pal.id("oak_stairs[facing=east]"), pal)));
    }

    @Test
    void statesTurnWithTheFrame() {
        Transform t = Transform.of(5, 0, 5, 1, "");
        assertEquals("minecraft:oak_stairs[facing=east]", pal.name(t.state(pal.id("oak_stairs[facing=north]"), pal)));
        assertEquals("minecraft:oak_log[axis=z]", pal.name(t.state(pal.id("oak_log[axis=x]"), pal)));
    }

    @Test
    void compositionEqualsApplyingOneAfterTheOther() {
        SplittableRandom r = new SplittableRandom(3);
        String[] mirrors = {"", "x", "z"};
        for (int i = 0; i < 500; i++) {
            Transform a = Transform.of(r.nextInt(-9, 9), r.nextInt(-9, 9), r.nextInt(-9, 9), r.nextInt(4), mirrors[r.nextInt(3)]);
            Transform b = Transform.of(r.nextInt(-9, 9), r.nextInt(-9, 9), r.nextInt(-9, 9), r.nextInt(4), mirrors[r.nextInt(3)]);
            int x = r.nextInt(-20, 20), y = r.nextInt(-20, 20), z = r.nextInt(-20, 20);
            int[] inner = map(b, x, y, z);
            assertArrayEquals(map(a, inner[0], inner[1], inner[2]), map(a.then(b), x, y, z));
            int s = pal.id("oak_stairs[facing=" + new String[] {"north", "east", "south", "west"}[r.nextInt(4)] + "]");
            assertEquals(a.state(b.state(s, pal), pal), a.then(b).state(s, pal));
        }
    }
}
