package com.uxplima.craftwire.fabric.video;

/**
 * Real-time pacing: the video clock is the wall clock from the first frame. Output frame k belongs at k / fps; a
 * frame the game rendered at time t stands for every output frame due by then that has not been written yet.
 */
public final class FramePacer {
    private final int fps;
    private long emitted;

    public FramePacer(int fps) {
        this.fps = fps;
    }

    /** How many output frames a frame rendered {@code nanos} after the start stands for: 0 (not needed), 1, or more (repeats). */
    public int framesFor(long nanos) {
        long due = Math.floorDiv(nanos * fps, 1_000_000_000L) + 1;
        if (due <= emitted) return 0;
        int n = (int) (due - emitted);
        emitted = due;
        return n;
    }

    /** Frames to add at the end so the video lasts {@code nanos} (at least one frame in all). */
    public int padTo(long nanos) {
        long total = Math.max(1, Math.round(nanos * (double) fps / 1e9));
        if (total <= emitted) return 0;
        int n = (int) (total - emitted);
        emitted = total;
        return n;
    }

    public long emitted() {
        return emitted;
    }

    public int fps() {
        return fps;
    }
}
