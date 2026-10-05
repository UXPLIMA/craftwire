package com.uxplima.craftwire.fabric.camera;

/** Render-only camera pose applied after vanilla camera setup each frame. Null = vanilla camera. */
public final class CameraOverride {
    public static final CameraOverride INSTANCE = new CameraOverride();

    public record Pose(double x, double y, double z, float yaw, float pitch) {}

    private volatile Pose pose;

    private CameraOverride() {}

    public Pose get() {
        return pose;
    }

    public void set(Pose p) {
        pose = p;
    }

    public void clear() {
        pose = null;
    }
}
