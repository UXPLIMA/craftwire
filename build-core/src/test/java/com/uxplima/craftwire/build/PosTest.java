package com.uxplima.craftwire.build;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class PosTest {
    @Test
    void packsAndUnpacksTheWholeRange() {
        int[][] cases = {{0, 0, 0}, {-1, -1, -1}, {Pos.MIN_XZ, Pos.MIN_Y, Pos.MIN_XZ}, {Pos.MAX_XZ, Pos.MAX_Y, Pos.MAX_XZ}, {123456, -64, -987654}};
        for (int[] c : cases) {
            long p = Pos.pack(c[0], c[1], c[2]);
            assertArrayEquals(c, new int[] {Pos.x(p), Pos.y(p), Pos.z(p)});
            assertNotEquals(Long.MIN_VALUE, p);
        }
    }

    @Test
    void matchesMojangLayout() {
        // BlockPos.asLong(1, 2, 3) = (1 << 38) | (3 << 12) | 2
        assertEquals((1L << 38) | (3L << 12) | 2L, Pos.pack(1, 2, 3));
    }
}
