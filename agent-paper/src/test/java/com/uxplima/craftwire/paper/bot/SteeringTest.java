package com.uxplima.craftwire.paper.bot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SteeringTest {
    @Test
    void yawFollowsMinecraftConventions() {
        assertEquals(0f, Steering.yaw(0, 1), 1e-4);      // south (+Z)
        assertEquals(-90f, Steering.yaw(1, 0), 1e-4);    // east (+X)
        assertEquals(90f, Steering.yaw(-1, 0), 1e-4);    // west
        assertEquals(180f, Math.abs(Steering.yaw(0, -1)), 1e-4); // north
    }

    @Test
    void pitchIsPositiveLookingDown() {
        assertEquals(45f, Steering.pitch(1, -1, 0), 1e-4);
        assertEquals(-45f, Steering.pitch(0, 1, 1), 1e-4);
        assertEquals(5.0, Steering.horizontal(3, 4), 1e-9);
    }

    @Test
    void progressReportsStuckOnlyAfterTheWindowWithoutGain() {
        Steering.Progress p = new Steering.Progress(0.3, 2000);
        assertFalse(p.stuck(10, 0));
        assertFalse(p.stuck(9, 1000));      // gained 1 block
        assertFalse(p.stuck(8.9, 2900));    // < 0.3 gain, but only 1.9 s since the last gain
        assertTrue(p.stuck(8.9, 3001));
    }
}
