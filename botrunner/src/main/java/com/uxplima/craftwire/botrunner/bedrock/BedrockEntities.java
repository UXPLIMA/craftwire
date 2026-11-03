package com.uxplima.craftwire.botrunner.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bedrock.packet.EntityPackets;
import com.uxplima.craftwire.bot.json.Jsons;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.botrunner.world.SharedWorld;
import com.uxplima.craftwire.botrunner.world.TrackedEntity;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The entities one Bedrock bot sees, in the shared world of its dimension, by runtime id. Bedrock moves carry
 * absolute coordinates (MoveActorDelta sends only the axes that changed), so any bot may apply them. Player
 * positions in Bedrock packets are at eye height; they are stored at the feet like every other entity. Runner loop
 * thread.
 */
final class BedrockEntities {
    static final double EYE = 1.62;

    private final Supplier<SharedWorld> world;
    private final Object holder;
    private final Map<Long, Long> runtimeByUnique = new HashMap<>();
    private final Set<Long> seen = new HashSet<>();

    BedrockEntities(Supplier<SharedWorld> world, Object holder) {
        this.world = world;
        this.holder = holder;
    }

    void clear() {
        runtimeByUnique.clear();
        seen.clear();
    }

    void add(EntityPackets.AddEntity a, long self) {
        SharedWorld w = world.get();
        if (w == null || a.runtimeId() == self) return;
        boolean player = a.type().equals("minecraft:player");
        UUID uuid = a.uuid() != null ? a.uuid() : UUID.nameUUIDFromBytes(("bedrock-entity:" + a.uniqueId()).getBytes(StandardCharsets.UTF_8));
        TrackedEntity e = new TrackedEntity(a.runtimeId(), uuid, a.type(), a.x(), a.y() - (player ? EYE : 0), a.z());
        if (a.name() != null) e.name(a.name());
        w.seeEntity(e, holder);
        runtimeByUnique.put(a.uniqueId(), a.runtimeId());
        seen.add(a.runtimeId());
    }

    void remove(long uniqueId) {
        SharedWorld w = world.get();
        Long rid = runtimeByUnique.remove(uniqueId);
        if (rid == null) return;
        seen.remove(rid);
        if (w != null) w.forgetEntity(rid, holder);
    }

    void move(EntityPackets.MoveEntity m) {
        TrackedEntity e = entity(m.runtimeId());
        if (e == null) return;
        double eye = e.type().equals("minecraft:player") ? EYE : 0;
        double x = Float.isNaN(m.x()) ? e.x() : m.x();
        double y = Float.isNaN(m.y()) ? e.y() : m.y() - eye;
        double z = Float.isNaN(m.z()) ? e.z() : m.z();
        e.moveTo(x, y, z);
        float yaw = Float.isNaN(m.yaw()) ? e.yaw() : m.yaw();
        float pitch = Float.isNaN(m.pitch()) ? e.pitch() : m.pitch();
        e.rotate(yaw, pitch);
    }

    /** MovePlayer for another player (eye height, like every player position). */
    void movePlayer(EntityPackets.MoveSelf m) {
        TrackedEntity e = entity(m.runtimeId());
        if (e == null) return;
        e.moveTo(m.x(), m.y() - EYE, m.z());
        e.rotate(m.yaw(), m.pitch());
    }

    /** The runtime id of an entity by its unique id (NPC dialogues name the NPC by unique id), or -1. */
    long runtimeOf(long uniqueId) {
        return runtimeByUnique.getOrDefault(uniqueId, -1L);
    }

    TrackedEntity entity(long runtimeId) {
        SharedWorld w = world.get();
        return w == null || !seen.contains(runtimeId) ? null : w.entity(runtimeId);
    }

    TrackedEntity entity(UUID id) {
        SharedWorld w = world.get();
        if (w == null) return null;
        for (long rid : seen) {
            TrackedEntity e = w.entity(rid);
            if (e != null && e.uuid().equals(id)) return e;
        }
        return null;
    }

    Optional<Position> player(String name) {
        SharedWorld w = world.get();
        if (w == null) return Optional.empty();
        TrackedEntity e = w.player(name);
        if (e == null || !seen.contains(e.id())) return Optional.empty();
        return Optional.of(new Position(e.x(), e.y(), e.z()));
    }

    /** What this bot sees, nearest first, optionally of one type. */
    List<TrackedEntity> near(Position at, String type, double range) {
        List<TrackedEntity> out = new ArrayList<>();
        SharedWorld w = world.get();
        if (w == null) return out;
        for (long rid : seen) {
            TrackedEntity e = w.entity(rid);
            if (e == null || (type != null && !e.type().equals(type))) continue;
            if (distance(e, at) <= range) out.add(e);
        }
        out.sort(Comparator.comparingDouble(e -> distance(e, at)));
        return out;
    }

    JsonArray nearJson(Position at, String type, double range, int limit) {
        JsonArray a = new JsonArray();
        for (TrackedEntity e : near(at, type, range)) {
            if (a.size() >= limit) break;
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id());
            o.addProperty("uuid", e.uuid().toString());
            o.addProperty("type", e.type());
            if (e.name() != null) o.addProperty("name", e.name());
            o.addProperty("x", Jsons.round(e.x()));
            o.addProperty("y", Jsons.round(e.y()));
            o.addProperty("z", Jsons.round(e.z()));
            o.addProperty("distance", Jsons.round(distance(e, at)));
            a.add(o);
        }
        return a;
    }

    static double distance(TrackedEntity e, Position at) {
        double dx = e.x() - at.x();
        double dy = e.y() - at.y();
        double dz = e.z() - at.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
