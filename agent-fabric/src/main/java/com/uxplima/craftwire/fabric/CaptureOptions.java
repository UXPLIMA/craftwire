package com.uxplima.craftwire.fabric;

/** Render tweaks that last only while a screenshot is being captured. */
public final class CaptureOptions {
    /** screenshot {chat:false}: chat lines are left out of the frame. */
    public static volatile boolean hideChat;

    private CaptureOptions() {}
}
