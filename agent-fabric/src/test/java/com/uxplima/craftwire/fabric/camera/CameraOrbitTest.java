package com.uxplima.craftwire.fabric.camera;

import static org.junit.jupiter.api.Assertions.*;

import com.uxplima.craftwire.fabric.camera.CameraMath.Vec;
import com.uxplima.craftwire.fabric.camera.CameraOverride.Pose;
import org.junit.jupiter.api.Test;

class CameraOrbitTest {
    static final Vec C = new Vec(100, 64, -50);

    @Test
    void angleZeroIsSouthOfTheCentre() {
        Pose p = CameraOrbit.of(C, 10, 5, 0, 360, 4000, false).at(0);
        assertEquals(100, p.x(), 1e-9);
        assertEquals(69, p.y(), 1e-9);
        assertEquals(-40, p.z(), 1e-9);
    }

    @Test
    void aQuarterOfTheTimeIsAQuarterTurn() {
        CameraOrbit o = CameraOrbit.of(C, 10, 0, 0, 360, 4000, false);
        Pose p = o.at(1000);
        assertEquals(100 - 10, p.x(), 1e-9);   // θ=90°: -sin θ · r
        assertEquals(-50, p.z(), 1e-9);
        assertEquals(4000, o.durationMs());
        assertEquals("orbit", o.kind());
    }

    @Test
    void theCameraAlwaysFacesTheCentre() {
        CameraOrbit o = CameraOrbit.of(C, 12, 6, 30, 270, 3000, true);
        for (int t = 0; t <= 3000; t += 250) {
            Pose p = o.at(t);
            CameraMath.Angles want = CameraMath.lookAt(new Vec(p.x(), p.y(), p.z()), C);
            assertEquals(want.yaw(), p.yaw(), 1e-3);
            assertEquals(want.pitch(), p.pitch(), 1e-3);
            assertEquals(12, Math.hypot(p.x() - C.x(), p.z() - C.z()), 1e-9);
        }
    }

    @Test
    void negativeDegreesTurnTheOtherWay() {
        Pose p = CameraOrbit.of(C, 10, 0, 0, -360, 4000, false).at(1000);
        assertEquals(110, p.x(), 1e-9);
    }

    @Test
    void startAngleOfAPointRoundTrips() {
        double a = CameraOrbit.angleOf(C, 93, -43);
        Pose p = CameraOrbit.of(C, Math.hypot(7, 7), 0, a, 360, 1000, false).at(0);
        assertEquals(93, p.x(), 1e-9);
        assertEquals(-43, p.z(), 1e-9);
    }

    @Test
    void afterTheEndTheCameraStays() {
        CameraOrbit o = CameraOrbit.of(C, 10, 0, 0, 90, 1000, false);
        Pose end = o.at(1000);
        Pose later = o.at(5000);
        assertEquals(end.x(), later.x(), 1e-9);
        assertEquals(end.z(), later.z(), 1e-9);
    }

    @Test
    void badOrbitsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> CameraOrbit.of(C, 0, 0, 0, 360, 1000, false));
        assertThrows(IllegalArgumentException.class, () -> CameraOrbit.of(C, 5, 0, 0, 360, 0, false));
    }
}
