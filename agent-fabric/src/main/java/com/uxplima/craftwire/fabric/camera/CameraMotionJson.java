package com.uxplima.craftwire.fabric.camera;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.fabric.Params;
import com.uxplima.craftwire.fabric.camera.CameraMath.Vec;
import java.util.ArrayList;
import java.util.List;

/** Reads a camera path / orbit from tool parameters (camera tool, record start's camera). */
public final class CameraMotionJson {
    private CameraMotionJson() {}

    /** @param current the camera pose now; an orbit without startAngle starts from where it is */
    public static CameraMotion parse(JsonObject p, CameraOverride.Pose current) {
        String action = Params.optString(p, "action").orElseThrow(() -> Params.invalid("camera.action is required (path or orbit)"));
        try {
            return switch (action) {
                case "path" -> path(p);
                case "orbit" -> orbit(p, current);
                default -> throw Params.invalid("a camera motion is \"path\" or \"orbit\", not \"" + action + "\"");
            };
        } catch (IllegalArgumentException e) {
            throw Params.invalid(e.getMessage());
        }
    }

    private static CameraPath path(JsonObject p) {
        if (!p.has("keyframes") || !p.get("keyframes").isJsonArray()) throw Params.invalid("path needs keyframes [{t,x,y,z,yaw,pitch}]");
        List<CameraPath.Keyframe> keys = new ArrayList<>();
        int i = 0;
        for (JsonElement e : p.getAsJsonArray("keyframes")) {
            if (!e.isJsonObject()) throw Params.invalid("keyframe " + i + " is not an object");
            JsonObject k = e.getAsJsonObject();
            for (String f : new String[] {"t", "x", "y", "z"}) {
                if (!k.has(f)) throw Params.invalid("keyframe " + i + " needs " + f);
            }
            keys.add(new CameraPath.Keyframe(k.get("t").getAsDouble(), k.get("x").getAsDouble(), k.get("y").getAsDouble(), k.get("z").getAsDouble(),
                    Params.optDouble(k, "yaw").map(Double::floatValue).orElse(null),
                    Params.optDouble(k, "pitch").map(Double::floatValue).orElse(null)));
            i++;
        }
        boolean smooth = !"linear".equals(Params.optString(p, "interpolation").orElse("smooth"));
        boolean ease = "inOut".equals(Params.optString(p, "ease").orElse("inOut"));
        Vec lookAt = p.has("lookAt") && p.get("lookAt").isJsonObject() ? vec(p.getAsJsonObject("lookAt"), "lookAt") : null;
        return CameraPath.of(keys, smooth, ease, lookAt);
    }

    private static CameraOrbit orbit(JsonObject p, CameraOverride.Pose current) {
        if (!p.has("center") || !p.get("center").isJsonObject()) throw Params.invalid("orbit needs center {x,y,z}");
        Vec center = vec(p.getAsJsonObject("center"), "center");
        double radius = Params.optDouble(p, "radius").orElseThrow(() -> Params.invalid("orbit needs radius"));
        long duration = Params.optDouble(p, "durationMs").map(Math::round).orElseThrow(() -> Params.invalid("orbit needs durationMs"));
        double height = Params.optDouble(p, "height").orElse(radius / 2);
        double start = Params.optDouble(p, "startAngle")
                .orElseGet(() -> current == null ? 0 : CameraOrbit.angleOf(center, current.x(), current.z()));
        double degrees = Params.optDouble(p, "degrees").orElse(360.0);
        boolean ease = "inOut".equals(Params.optString(p, "ease").orElse("none"));
        return CameraOrbit.of(center, radius, height, start, degrees, duration, ease);
    }

    private static Vec vec(JsonObject o, String name) {
        if (!o.has("x") || !o.has("y") || !o.has("z")) throw Params.invalid(name + " needs x, y and z");
        return new Vec(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble());
    }
}
