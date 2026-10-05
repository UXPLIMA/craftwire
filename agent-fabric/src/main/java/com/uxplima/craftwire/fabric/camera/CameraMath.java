package com.uxplima.craftwire.fabric.camera;

/** Pure camera geometry (no Minecraft types) so it can be unit tested. */
public final class CameraMath {
    public record Vec(double x, double y, double z) {}

    public record Angles(float yaw, float pitch) {}

    private CameraMath() {}

    public static Angles lookAt(Vec from, Vec to) {
        double dx = to.x() - from.x(), dy = to.y() - from.y(), dz = to.z() - from.z();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal));
        return new Angles(yaw, pitch);
    }

    public static Vec direction(float yaw, float pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new Vec(-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p));
    }

    public static Vec frame(Vec min, Vec max, float yaw, float pitch, double fovDegrees, double scale) {
        Vec c = new Vec((min.x() + max.x()) / 2, (min.y() + max.y()) / 2, (min.z() + max.z()) / 2);
        double dx = max.x() - min.x(), dy = max.y() - min.y(), dz = max.z() - min.z();
        double radius = Math.sqrt(dx * dx + dy * dy + dz * dz) / 2;
        double distance = radius / Math.tan(Math.toRadians(fovDegrees) / 2) * scale;
        Vec d = direction(yaw, pitch);
        return new Vec(c.x() - d.x() * distance, c.y() - d.y() * distance, c.z() - d.z() * distance);
    }
}
