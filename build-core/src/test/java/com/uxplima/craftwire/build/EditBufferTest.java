package com.uxplima.craftwire.build;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class EditBufferTest {
    @Test
    void tracksBoundingBox() {
        EditBuffer b = new EditBuffer(100);
        assertNull(b.bbox());
        b.put(3, 10, -2, 1);
        b.put(-1, 12, 5, 1);
        assertArrayEquals(new int[] {-1, 10, -2, 3, 12, 5}, b.bbox());
        assertEquals(2, b.size());
    }

    @Test
    void laterPutWins() {
        EditBuffer b = new EditBuffer(100);
        b.put(0, 0, 0, 1);
        b.put(0, 0, 0, 2);
        assertEquals(2, b.get(0, 0, 0));
        assertEquals(1, b.size());
    }

    @Test
    void bufferRefusesBeyondLimit() {
        EditBuffer b = new EditBuffer(3);
        b.put(0, 0, 0, 1);
        b.put(1, 0, 0, 1);
        b.put(2, 0, 0, 1);
        b.put(2, 0, 0, 2);   // overwrite: still 3
        BuildError e = assertThrows(BuildError.class, () -> b.put(3, 0, 0, 1));
        assertEquals("TOO_LARGE", e.code());
    }
}
