package com.uxplima.craftwire.core;

public final class Backoff {
    private final long initial;
    private final long max;
    private long next;

    public Backoff(long initialMillis, long maxMillis) {
        this.initial = initialMillis;
        this.max = maxMillis;
        this.next = initialMillis;
    }

    public synchronized long nextDelayMillis() {
        long current = next;
        next = Math.min(max, next * 2);
        return current;
    }

    public synchronized void reset() {
        next = initial;
    }
}
