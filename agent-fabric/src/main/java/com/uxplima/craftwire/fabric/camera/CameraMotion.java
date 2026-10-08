package com.uxplima.craftwire.fabric.camera;

/** A camera move over time (camera path / orbit). Pure: the pose depends only on the elapsed time. */
public interface CameraMotion {
    /** The pose {@code ms} milliseconds after the start; before 0 and after the end it holds the end poses. */
    CameraOverride.Pose at(double ms);

    long durationMs();

    /** "path" or "orbit", as the camera tool names it. */
    String kind();

    /** Smoothstep: zero speed at both ends, the middle stays in the middle. */
    static double easeInOut(double f) {
        return f * f * (3 - 2 * f);
    }

    static double clamp01(double f) {
        return f < 0 ? 0 : f > 1 ? 1 : f;
    }
}
