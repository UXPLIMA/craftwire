package com.uxplima.craftwire.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BackoffTest {
    @Test
    void doublesUpToMaxAndResets() {
        Backoff b = new Backoff(1000, 30_000);
        assertEquals(1000, b.nextDelayMillis());
        assertEquals(2000, b.nextDelayMillis());
        assertEquals(4000, b.nextDelayMillis());
        for (int i = 0; i < 10; i++) b.nextDelayMillis();
        assertEquals(30_000, b.nextDelayMillis());
        b.reset();
        assertEquals(1000, b.nextDelayMillis());
    }
}
