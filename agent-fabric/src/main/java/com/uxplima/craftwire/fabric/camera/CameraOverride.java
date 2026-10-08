package com.uxplima.craftwire.fabric.camera;

/**
 * Render-only camera pose applied after vanilla camera setup each frame. Null = vanilla camera. Either a fixed pose
 * or a motion (camera path / orbit), evaluated from real time on every frame so a slow frame skips ahead instead of
 * making the camera stutter.
 */
public final class CameraOverride {
    public static final CameraOverride INSTANCE = new CameraOverride();

    public record Pose(double x, double y, double z, float yaw, float pitch) {}

    private record Playing(CameraMotion motion, long startNanos) {}

    private volatile Pose pose;
    private volatile Playing playing;

    private CameraOverride() {}

    public Pose get() {
        return poseAt(System.nanoTime());
    }

    public Pose poseAt(long nanos) {
        Playing p = playing;
        if (p != null) return p.motion().at((nanos - p.startNanos()) / 1e6);
        return pose;
    }

    /** The motion playing (also after it reached its end and holds the last pose), or null. */
    public CameraMotion motion() {
        Playing p = playing;
        return p == null ? null : p.motion();
    }

    public void set(Pose p) {
        playing = null;
        pose = p;
    }

    public void play(CameraMotion motion, long startNanos) {
        pose = null;
        playing = new Playing(motion, startNanos);
    }

    public void clear() {
        playing = null;
        pose = null;
    }
}
