package com.uxplima.craftwire.fabric;

/** Fault injection for gametests: lets a test make the next frame grab never complete. Never set in normal play. */
public final class CaptureFaults {
    public static volatile boolean stallNextGrab;
    public static volatile long grabTimeoutMillis = 10_000;

    private CaptureFaults() {}
}
