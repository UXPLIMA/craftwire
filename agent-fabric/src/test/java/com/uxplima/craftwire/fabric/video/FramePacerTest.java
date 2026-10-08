package com.uxplima.craftwire.fabric.video;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FramePacerTest {
    static final long MS = 1_000_000L;

    @Test
    void theFirstFrameIsAlwaysTaken() {
        assertEquals(1, new FramePacer(30).framesFor(0));
    }

    @Test
    void aGameFasterThanTheVideoSkipsFramesItDoesNotNeed() {
        FramePacer p = new FramePacer(30);
        int taken = 0;
        for (long t = 0; t < 1000 * MS; t += 5 * MS) taken += p.framesFor(t);   // 200 game fps
        assertEquals(30, taken);
        assertEquals(30, p.emitted());
    }

    @Test
    void aSlowGameRepeatsFramesSoTheVideoKeepsRealTime() {
        FramePacer p = new FramePacer(30);
        assertEquals(1, p.framesFor(0));
        assertEquals(3, p.framesFor(100 * MS));   // frames 1, 2 and 3 (at 33, 67 and 100 ms) all show this one
        assertEquals(0, p.framesFor(110 * MS));
        assertEquals(4, p.emitted());
    }

    @Test
    void stoppingPadsTheVideoToItsRealLength() {
        FramePacer p = new FramePacer(30);
        p.framesFor(0);
        p.framesFor(500 * MS);
        assertEquals(60 - p.emitted(), p.padTo(2000 * MS));
        assertEquals(60, p.emitted());
        assertEquals(0, p.padTo(1000 * MS), "never takes frames back");
    }

    @Test
    void aVeryShortRecordingStillHasOneFrame() {
        FramePacer p = new FramePacer(60);
        assertEquals(1, p.padTo(1 * MS));
        assertEquals(1, p.emitted());
    }

    @Test
    void frameCountMatchesTheDurationAtAnyFps() {
        for (int fps : new int[] {24, 30, 60, 120}) {
            FramePacer p = new FramePacer(fps);
            for (long t = 0; t < 3000 * MS; t += 7 * MS) p.framesFor(t);
            p.padTo(3000 * MS);
            assertEquals(3 * fps, p.emitted(), "fps " + fps);
        }
    }
}
