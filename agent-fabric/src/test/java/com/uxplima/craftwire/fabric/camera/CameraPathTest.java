package com.uxplima.craftwire.fabric.camera;

import static org.junit.jupiter.api.Assertions.*;

import com.uxplima.craftwire.fabric.camera.CameraMath.Vec;
import com.uxplima.craftwire.fabric.camera.CameraOverride.Pose;
import com.uxplima.craftwire.fabric.camera.CameraPath.Keyframe;
import java.util.List;
import org.junit.jupiter.api.Test;

class CameraPathTest {
    static Keyframe k(double t, double x, double y, double z, float yaw, float pitch) {
        return new Keyframe(t, x, y, z, yaw, pitch);
    }

    static Keyframe k(double t, double x, double y, double z) {
        return new Keyframe(t, x, y, z, null, null);
    }

    @Test
    void withoutEasingEveryKeyframeIsReachedAtItsTime() {
        CameraPath p = CameraPath.of(List.of(k(0, 0, 70, 0, 0, 0), k(1000, 10, 72, 0, 90, 10), k(3000, 10, 72, 20, 180, -10)), true, false, null);
        assertPose(p.at(0), 0, 70, 0, 0, 0);
        assertPose(p.at(1000), 10, 72, 0, 90, 10);
        assertPose(p.at(3000), 10, 72, 20, 180, -10);
        assertEquals(3000, p.durationMs());
        assertEquals("path", p.kind());
    }

    @Test
    void theFirstKeyframeTimeIsTheStart() {
        CameraPath p = CameraPath.of(List.of(k(500, 0, 0, 0, 0, 0), k(1500, 10, 0, 0, 0, 0)), false, false, null);
        assertEquals(1000, p.durationMs());
        assertEquals(5, p.at(500).x(), 1e-9);
    }

    @Test
    void linearMovesAtConstantSpeedBetweenKeyframes() {
        CameraPath p = CameraPath.of(List.of(k(0, 0, 0, 0, 0, 0), k(1000, 10, 0, 0, 0, 0), k(2000, 10, 0, 10, 0, 0)), false, false, null);
        assertEquals(2.5, p.at(250).x(), 1e-9);
        assertEquals(5, p.at(1500).z(), 1e-9);
    }

    @Test
    void smoothCurvesThroughTheCornerInsteadOfCuttingStraight() {
        List<Keyframe> keys = List.of(k(0, 0, 0, 0, 0, 0), k(1000, 10, 0, 0, 0, 0), k(2000, 10, 0, 10, 0, 0));
        Pose linear = CameraPath.of(keys, false, false, null).at(1500);
        Pose smooth = CameraPath.of(keys, true, false, null).at(1500);
        assertEquals(10, linear.x(), 1e-9);
        assertNotEquals(10, smooth.x(), 1e-3);   // Catmull-Rom bulges out of the corner
        assertEquals(5, smooth.z(), 0.6);
    }

    @Test
    void easingStartsAndStopsGentlyButKeepsTheEnds() {
        CameraPath p = CameraPath.of(List.of(k(0, 0, 0, 0, 0, 0), k(1000, 100, 0, 0, 0, 0)), false, true, null);
        assertEquals(0, p.at(0).x(), 1e-9);
        assertEquals(100, p.at(1000).x(), 1e-9);
        assertEquals(50, p.at(500).x(), 1e-9);
        assertTrue(p.at(10).x() < 0.1, "barely moving right after the start");
        assertTrue(p.at(990).x() > 99.9, "barely moving right before the end");
    }

    @Test
    void outsideTheTimelineTheCameraHoldsTheEndPoses() {
        CameraPath p = CameraPath.of(List.of(k(0, 1, 2, 3, 10, 5), k(1000, 4, 5, 6, 20, 6)), true, true, null);
        assertPose(p.at(-50), 1, 2, 3, 10, 5);
        assertPose(p.at(99_999), 4, 5, 6, 20, 6);
    }

    @Test
    void yawTurnsTheShortWayRound() {
        CameraPath p = CameraPath.of(List.of(k(0, 0, 0, 0, 170, 0), k(1000, 0, 0, 0, -170, 0)), false, false, null);
        float mid = p.at(500).yaw();
        assertEquals(180, Math.abs(CameraPath.wrap(mid)), 1e-3, "halfway through a 20 degree turn across 180, got " + mid);
    }

    @Test
    void lookAtAimsAtThePointFromEveryPosition() {
        Vec target = new Vec(0, 64, 0);
        CameraPath p = CameraPath.of(List.of(k(0, 20, 70, 0), k(1000, 0, 70, 20), k(2000, -20, 70, 0)), true, true, target);
        for (double t : new double[] {0, 333, 1000, 1700, 2000}) {
            Pose pose = p.at(t);
            CameraMath.Angles want = CameraMath.lookAt(new Vec(pose.x(), pose.y(), pose.z()), target);
            assertEquals(want.yaw(), pose.yaw(), 1e-3);
            assertEquals(want.pitch(), pose.pitch(), 1e-3);
        }
    }

    @Test
    void pitchStaysWithinStraightUpAndDown() {
        CameraPath p = CameraPath.of(List.of(k(0, 0, 0, 0, 0, 0), k(1000, 0, 0, 0, 0, 90), k(2000, 0, 0, 0, 0, 0)), true, false, null);
        for (int t = 0; t <= 2000; t += 50) assertTrue(p.at(t).pitch() <= 90f, "pitch " + p.at(t).pitch() + " at " + t);
    }

    @Test
    void badKeyframesAreRefusedWithAReason() {
        assertThrows(IllegalArgumentException.class, () -> CameraPath.of(List.of(k(0, 0, 0, 0, 0, 0)), true, true, null), "one keyframe");
        IllegalArgumentException order = assertThrows(IllegalArgumentException.class,
                () -> CameraPath.of(List.of(k(0, 0, 0, 0, 0, 0), k(0, 1, 0, 0, 0, 0)), true, true, null));
        assertTrue(order.getMessage().contains("increasing"), order.getMessage());
        IllegalArgumentException angles = assertThrows(IllegalArgumentException.class,
                () -> CameraPath.of(List.of(k(0, 0, 0, 0), k(1000, 1, 0, 0)), true, true, null));
        assertTrue(angles.getMessage().contains("lookAt"), angles.getMessage());
        assertThrows(IllegalArgumentException.class, () -> CameraPath.of(java.util.Collections.nCopies(65, k(0, 0, 0, 0, 0, 0)), true, true, null));
    }

    static void assertPose(Pose p, double x, double y, double z, float yaw, float pitch) {
        assertEquals(x, p.x(), 1e-6, "x");
        assertEquals(y, p.y(), 1e-6, "y");
        assertEquals(z, p.z(), 1e-6, "z");
        assertEquals(yaw, p.yaw(), 1e-3, "yaw");
        assertEquals(pitch, p.pitch(), 1e-3, "pitch");
    }
}
