package com.uxplima.craftwire.fabric.handlers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.Params;
import com.uxplima.craftwire.fabric.camera.CameraMath;
import com.uxplima.craftwire.fabric.camera.CameraMotion;
import com.uxplima.craftwire.fabric.camera.CameraMotionJson;
import com.uxplima.craftwire.fabric.camera.CameraOverride;
import com.uxplima.craftwire.fabric.camera.CameraOverride.Pose;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

final class CameraHandler {
    private CameraHandler() {}

    static JsonElement handle(JsonObject p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) throw Params.notInWorld();
        String action = Params.optString(p, "action").orElseThrow(() -> Params.invalid("action is required"));
        Pose current = currentPose(mc);
        switch (action) {
            case "set" -> CameraOverride.INSTANCE.set(new Pose(
                    Params.optDouble(p, "x").orElse(current.x()),
                    Params.optDouble(p, "y").orElse(current.y()),
                    Params.optDouble(p, "z").orElse(current.z()),
                    Params.optDouble(p, "yaw").map(Double::floatValue).orElse(current.yaw()),
                    Params.optDouble(p, "pitch").map(Double::floatValue).orElse(current.pitch())));
            case "look_at" -> {
                CameraMath.Vec target = vec(p.getAsJsonObject("target"), "target");
                CameraMath.Angles a = CameraMath.lookAt(new CameraMath.Vec(current.x(), current.y(), current.z()), target);
                CameraOverride.INSTANCE.set(new Pose(current.x(), current.y(), current.z(), a.yaw(), a.pitch()));
            }
            case "frame_area" -> {
                JsonObject area = p.getAsJsonObject("area");
                if (area == null) throw Params.invalid("area {min,max} is required");
                frame(mc, p, current, vec(area.getAsJsonObject("min"), "area.min"), vec(area.getAsJsonObject("max"), "area.max"));
            }
            case "frame_entity" -> {
                Entity e = findEntity(mc, Params.optString(p, "entity").orElseThrow(() -> Params.invalid("entity is required")));
                AABB box = e.getBoundingBox().inflate(1.0);
                frame(mc, p, current, new CameraMath.Vec(box.minX, box.minY, box.minZ), new CameraMath.Vec(box.maxX, box.maxY, box.maxZ));
            }
            case "path", "orbit" -> CameraOverride.INSTANCE.play(CameraMotionJson.parse(p, current), System.nanoTime());
            case "freecam_on" -> CameraOverride.INSTANCE.set(current);
            case "freecam_off", "reset" -> CameraOverride.INSTANCE.clear();
            default -> throw Params.invalid("unknown action: " + action);
        }
        return describe(mc);
    }

    private static void frame(Minecraft mc, JsonObject p, Pose current, CameraMath.Vec min, CameraMath.Vec max) {
        float yaw = Params.optDouble(p, "yaw").map(Double::floatValue).orElse(current.yaw());
        float pitch = Params.optDouble(p, "pitch").map(Double::floatValue).orElse(30f);
        double scale = Params.optDouble(p, "distanceScale").orElse(1.0);
        CameraMath.Vec pos = CameraMath.frame(min, max, yaw, pitch, mc.options.fov().get(), scale);
        CameraOverride.INSTANCE.set(new Pose(pos.x(), pos.y(), pos.z(), yaw, pitch));
    }

    private static Entity findEntity(Minecraft mc, String key) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e.getUUID().toString().equalsIgnoreCase(key) || e.getName().getString().equalsIgnoreCase(key)) return e;
        }
        throw new AgentError("ENTITY_NOT_FOUND", "No loaded entity matches \"" + key + "\".",
                "Use a UUID or exact name; the entity must be within render distance.");
    }

    private static CameraMath.Vec vec(JsonObject o, String name) {
        if (o == null || !o.has("x") || !o.has("y") || !o.has("z")) throw Params.invalid(name + " needs x, y and z");
        return new CameraMath.Vec(o.get("x").getAsDouble(), o.get("y").getAsDouble(), o.get("z").getAsDouble());
    }

    static Pose currentPose(Minecraft mc) {
        Pose o = CameraOverride.INSTANCE.get();
        if (o != null) return o;
        Camera cam = mc.gameRenderer.mainCamera();
        Vec3 pos = cam.position();
        return new Pose(pos.x, pos.y, pos.z, cam.yRot(), cam.xRot());
    }

    private static JsonObject describe(Minecraft mc) {
        Pose p = currentPose(mc);
        JsonObject o = new JsonObject();
        o.addProperty("active", CameraOverride.INSTANCE.get() != null);
        o.addProperty("x", p.x());
        o.addProperty("y", p.y());
        o.addProperty("z", p.z());
        o.addProperty("yaw", p.yaw());
        o.addProperty("pitch", p.pitch());
        CameraMotion m = CameraOverride.INSTANCE.motion();
        if (m != null) {
            JsonObject motion = new JsonObject();
            motion.addProperty("kind", m.kind());
            motion.addProperty("durationMs", m.durationMs());
            o.add("motion", motion);
        }
        double limit = mc.options.renderDistance().get() * 16.0;
        double dist = mc.player.getEyePosition().distanceTo(new Vec3(p.x(), p.y(), p.z()));
        if (dist > limit) o.addProperty("warning", String.format("Camera is %.0f blocks from the player; chunks beyond render distance (%.0f) are not drawn.", dist, limit));
        return o;
    }
}
