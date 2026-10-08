package com.uxplima.craftwire.fabric.camera;

import static org.junit.jupiter.api.Assertions.*;

import com.uxplima.craftwire.fabric.camera.CameraMath.Vec;
import com.uxplima.craftwire.fabric.camera.CameraOverride.Pose;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class CameraOverrideTest {
    final CameraOverride o = CameraOverride.INSTANCE;

    @AfterEach
    void clear() {
        o.clear();
    }

    @Test
    void aPlayingMotionIsEvaluatedAtTheElapsedTime() {
        CameraOrbit orbit = CameraOrbit.of(new Vec(0, 0, 0), 10, 0, 0, 360, 4000, false);
        o.play(orbit, 1_000_000_000L);
        Pose p = o.poseAt(1_000_000_000L + 1_000_000_000L);   // one second in: a quarter turn
        assertEquals(-10, p.x(), 1e-9);
        assertSame(orbit, o.motion());
    }

    @Test
    void setEndsTheMotion() {
        o.play(CameraOrbit.of(new Vec(0, 0, 0), 10, 0, 0, 360, 4000, false), 0);
        o.set(new Pose(1, 2, 3, 4, 5));
        assertNull(o.motion());
        assertEquals(1, o.get().x(), 0);
    }

    @Test
    void clearEndsEverything() {
        o.play(CameraOrbit.of(new Vec(0, 0, 0), 10, 0, 0, 360, 4000, false), 0);
        o.clear();
        assertNull(o.get());
        assertNull(o.motion());
    }
}
