package com.uxplima.craftwire.fabric.camera;

import static org.junit.jupiter.api.Assertions.*;

import com.uxplima.craftwire.fabric.camera.CameraMath.Angles;
import com.uxplima.craftwire.fabric.camera.CameraMath.Vec;
import org.junit.jupiter.api.Test;

class CameraMathTest {
    static final Vec O = new Vec(0, 0, 0);

    @Test
    void lookAtUsesMinecraftYaw() {
        assertEquals(0f, CameraMath.lookAt(O, new Vec(0, 0, 5)).yaw(), 1e-4);    // south
        assertEquals(90f, CameraMath.lookAt(O, new Vec(-5, 0, 0)).yaw(), 1e-4);  // west
        assertEquals(-90f, CameraMath.lookAt(O, new Vec(5, 0, 0)).yaw(), 1e-4);  // east
    }

    @Test
    void lookAtPitchIsPositiveDownwards() {
        assertEquals(45f, CameraMath.lookAt(O, new Vec(0, -5, 5)).pitch(), 1e-4);
        assertEquals(-90f, CameraMath.lookAt(O, new Vec(0, 5, 0)).pitch(), 1e-4);
    }

    @Test
    void directionIsInverseOfLookAt() {
        Vec d = CameraMath.direction(30f, 20f);
        Angles a = CameraMath.lookAt(O, d);
        assertEquals(30f, a.yaw(), 1e-3);
        assertEquals(20f, a.pitch(), 1e-3);
    }

    @Test
    void framedCameraLooksAtTheBoxCentreFromFarEnough() {
        Vec min = new Vec(-8, 60, -8), max = new Vec(8, 76, 8);
        Vec cam = CameraMath.frame(min, max, 45f, 30f, 70, 1.0);
        Angles a = CameraMath.lookAt(cam, new Vec(0, 68, 0));
        assertEquals(45f, a.yaw(), 1e-3);
        assertEquals(30f, a.pitch(), 1e-3);
        double dist = Math.sqrt(cam.x() * cam.x() + (cam.y() - 68) * (cam.y() - 68) + cam.z() * cam.z());
        double radius = Math.sqrt(16 * 16 * 3) / 2;
        assertTrue(dist >= radius / Math.tan(Math.toRadians(35)) - 1e-6, "too close: " + dist);
    }
}
