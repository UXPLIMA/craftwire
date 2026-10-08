package com.uxplima.craftwire.fabric.camera;

import com.uxplima.craftwire.fabric.camera.CameraMath.Vec;
import com.uxplima.craftwire.fabric.camera.CameraOverride.Pose;

/**
 * Circles a point while looking at it. Angle θ (degrees) puts the camera at center + (-sin θ·r, height, cos θ·r):
 * the game's yaw convention, so θ = 0 is south of the center.
 */
public final class CameraOrbit implements CameraMotion {
    private final Vec center;
    private final double radius, height, startAngle, degrees;
    private final long durationMs;
    private final boolean ease;

    private CameraOrbit(Vec center, double radius, double height, double startAngle, double degrees, long durationMs, boolean ease) {
        this.center = center;
        this.radius = radius;
        this.height = height;
        this.startAngle = startAngle;
        this.degrees = degrees;
        this.durationMs = durationMs;
        this.ease = ease;
    }

    /** @throws IllegalArgumentException with a message for the caller */
    public static CameraOrbit of(Vec center, double radius, double height, double startAngle, double degrees, long durationMs, boolean ease) {
        if (!(radius > 0)) throw new IllegalArgumentException("radius must be greater than 0");
        if (durationMs <= 0) throw new IllegalArgumentException("durationMs must be greater than 0");
        return new CameraOrbit(center, radius, height, startAngle, degrees, durationMs, ease);
    }

    /** The orbit angle at which a camera at (x, z) already is, seen from the center. */
    public static double angleOf(Vec center, double x, double z) {
        return Math.toDegrees(Math.atan2(-(x - center.x()), z - center.z()));
    }

    @Override
    public Pose at(double ms) {
        double f = CameraMotion.clamp01(ms / durationMs);
        double theta = Math.toRadians(startAngle + degrees * (ease ? CameraMotion.easeInOut(f) : f));
        double px = center.x() - Math.sin(theta) * radius;
        double py = center.y() + height;
        double pz = center.z() + Math.cos(theta) * radius;
        CameraMath.Angles a = CameraMath.lookAt(new Vec(px, py, pz), center);
        return new Pose(px, py, pz, a.yaw(), a.pitch());
    }

    @Override
    public long durationMs() {
        return durationMs;
    }

    @Override
    public String kind() {
        return "orbit";
    }
}
