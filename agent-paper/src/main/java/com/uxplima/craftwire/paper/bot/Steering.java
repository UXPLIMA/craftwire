package com.uxplima.craftwire.paper.bot;

/** Rotation maths in Minecraft's conventions (yaw 0 = south/+Z, -90 = east/+X; pitch > 0 looks down). */
public final class Steering {
    private Steering() {}

    public static float yaw(double dx, double dz) {
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    public static float pitch(double dx, double dy, double dz) {
        return (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
    }

    public static double horizontal(double dx, double dz) {
        return Math.hypot(dx, dz);
    }

    /** Detects a walker that stopped getting closer: no gain of `minGain` blocks for `windowMs`. */
    public static final class Progress {
        private final double minGain;
        private final long windowMs;
        private double best = Double.POSITIVE_INFINITY;
        private long bestAt;

        public Progress(double minGain, long windowMs) {
            this.minGain = minGain;
            this.windowMs = windowMs;
        }

        public boolean stuck(double distance, long now) {
            if (distance < best - minGain) {
                best = distance;
                bestAt = now;
                return false;
            }
            return now - bestAt > windowMs;
        }
    }
}
