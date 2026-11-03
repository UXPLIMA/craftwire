package com.uxplima.craftwire.botrunner.javabot.bot;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.json.Jsons;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.bot.path.BlockView;
import com.uxplima.craftwire.bot.path.Cell;
import com.uxplima.craftwire.botrunner.javabot.JavaCompat;
import com.uxplima.craftwire.botrunner.javabot.world.JavaBlocks;
import com.uxplima.craftwire.botrunner.world.ChunkColumn;
import com.uxplima.craftwire.botrunner.world.SharedWorld;
import com.uxplima.craftwire.botrunner.world.TrackedEntity;
import com.uxplima.craftwire.botrunner.world.WorldCache;
import com.uxplima.craftwire.botrunner.world.WorldKey;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

/**
 * The world as one Java bot sees it: in world mode `shared` the chunks and entities of its server and dimension in
 * the runner's {@link WorldCache} (a chunk another bot already holds is not decoded again), in mode `none` nothing at
 * all (no chunk is decoded, every block is unknown). Also the tab list's names, so player entities get names. Runner
 * loop thread.
 */
final class JavaWorld {
    private static final BlockView NOTHING = (x, y, z) -> Cell.UNKNOWN;

    private final WorldCache cache;
    private final JavaBlocks blocks;
    private final JavaCompat compat;
    private final String server;
    private final boolean shared;
    private final Object holder;
    private final Map<UUID, String> names = new HashMap<>();
    private final Set<Long> seen = new HashSet<>();
    private SharedWorld world;
    private String dimension = "unknown";
    private int minY;
    private int sections;
    private PalettedContainerFactory palettes;

    JavaWorld(WorldCache cache, JavaBlocks blocks, JavaCompat compat, String server, boolean shared, Object holder) {
        this.cache = cache;
        this.blocks = blocks;
        this.compat = compat;
        this.server = server;
        this.shared = shared;
        this.holder = holder;
    }

    /** Login or respawn into a dimension: the old one is let go first (a respawn in the same dimension resends its chunks). */
    void enter(String dim, DimensionType type, RegistryAccess access) {
        leave();
        dimension = dim;
        minY = type.minY();
        sections = type.height() >> 4;
        if (!shared) return;
        palettes = PalettedContainerFactory.create(access);
        world = cache.enter(new WorldKey(server, dim), blocks);
    }

    void leave() {
        if (world != null) cache.leave(world, holder);
        world = null;
        seen.clear();
    }

    String dimension() {
        return dimension;
    }

    boolean shared() {
        return shared;
    }

    BlockView view() {
        return world == null ? NOTHING : world;
    }

    /** Decodes the chunk unless another bot of this world already holds it; the sections are read the client's way. */
    void chunk(ClientboundLevelChunkWithLightPacket p) {
        if (world == null) return;
        int cx = compat.chunkX(p);
        int cz = compat.chunkZ(p);
        ChunkColumn known = world.chunk(cx, cz);
        world.loadChunk(cx, cz, known != null ? known : decode(p), holder);
    }

    private ChunkColumn decode(ClientboundLevelChunkWithLightPacket p) {
        ChunkColumn column = new ChunkColumn(minY, sections);
        FriendlyByteBuf buf = compat.chunkData(p).getReadBuffer();
        for (int s = 0; s < sections; s++) {
            LevelChunkSection section = new LevelChunkSection(palettes);
            section.read(buf);
            if (section.hasOnlyAir()) continue;
            char[] states = new char[16 * 16 * 16];
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) states[(y << 8) | (z << 4) | x] = (char) JavaBlocks.id(section.getBlockState(x, y, z));
                }
            }
            column.section(s, states);
        }
        return column;
    }

    void forget(int cx, int cz) {
        if (world != null) world.unloadChunk(cx, cz, holder);
    }

    void block(int x, int y, int z, int state) {
        if (world != null) world.setBlock(x, y, z, state);
    }

    /** The state id at a block, or -1 when the bot does not have its chunk. */
    int state(int x, int y, int z) {
        return world == null ? -1 : world.blockState(x, y, z);
    }

    void named(UUID id, String name) {
        names.put(id, name);
    }

    void unnamed(UUID id) {
        names.remove(id);
    }

    void addEntity(ClientboundAddEntityPacket p) {
        if (world == null) return;
        TrackedEntity e = new TrackedEntity(p.getId(), p.getUUID(), EntityType.getKey(p.getType()).toString(), p.getX(), p.getY(), p.getZ());
        e.rotate(p.getYRot(), p.getXRot());
        if (p.getType() == EntityType.PLAYER) e.name(names.get(p.getUUID()));
        world.seeEntity(e, holder);
        seen.add((long) p.getId());
    }

    void removeEntities(List<Integer> ids) {
        if (world == null) return;
        for (int id : ids) {
            world.forgetEntity(id, holder);
            seen.remove((long) id);
        }
    }

    /** Relative moves are applied by the bot that first saw the entity only, so a shared entity moves once per packet. */
    void moveEntity(ClientboundMoveEntityPacket p) {
        if (world == null) return;
        TrackedEntity e = owned(moveId(p));
        if (e == null) return;
        double[] to = compat.movedTo(p, e.x(), e.y(), e.z());
        if (to != null) e.moveTo(to[0], to[1], to[2]);
        if (p.hasRotation()) e.rotate(p.getYRot(), p.getXRot());
    }

    void teleportEntity(ClientboundTeleportEntityPacket p) {
        TrackedEntity e = world == null ? null : world.entity(p.id());
        if (e == null) return;
        PositionMoveRotation now = new PositionMoveRotation(new Vec3(e.x(), e.y(), e.z()), Vec3.ZERO, e.yaw(), e.pitch());
        PositionMoveRotation to = PositionMoveRotation.calculateAbsolute(now, p.change(), p.relatives());
        e.moveTo(to.position().x, to.position().y, to.position().z);
        e.rotate(to.yRot(), to.xRot());
    }

    void syncEntity(ClientboundEntityPositionSyncPacket p) {
        TrackedEntity e = world == null ? null : world.entity(p.id());
        if (e == null) return;
        double[] at = compat.syncPosition(p);
        e.moveTo(at[0], at[1], at[2]);
        e.rotate(p.values().yRot(), p.values().xRot());
    }

    /**
     * The entity id of a relative move. The packet keeps it in a protected field with no getter (the client looks the
     * entity up through getEntity(Level)), so the packet is written back with its own codec, whose first field is
     * the id.
     */
    private static int moveId(ClientboundMoveEntityPacket p) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer(16));
        try {
            switch (p) {
                case ClientboundMoveEntityPacket.PosRot m -> ClientboundMoveEntityPacket.PosRot.STREAM_CODEC.encode(buf, m);
                case ClientboundMoveEntityPacket.Pos m -> ClientboundMoveEntityPacket.Pos.STREAM_CODEC.encode(buf, m);
                case ClientboundMoveEntityPacket.Rot m -> ClientboundMoveEntityPacket.Rot.STREAM_CODEC.encode(buf, m);
                default -> {
                    return -1;
                }
            }
            return VarInt.read(buf);
        } finally {
            buf.release();
        }
    }

    /** The entity when this bot is the one that applies relative moves to it ({@link SharedWorld#mover}). */
    private TrackedEntity owned(int id) {
        if (world == null) return null;
        TrackedEntity e = world.entity(id);
        return e != null && world.mover(id) == holder ? e : null;
    }

    /** A player this bot can see, by name. */
    Optional<Position> player(String name) {
        if (world == null) return Optional.empty();
        TrackedEntity e = world.player(name);
        if (e == null || !seen.contains(e.id())) return Optional.empty();
        return Optional.of(new Position(e.x(), e.y(), e.z()));
    }

    TrackedEntity entity(UUID id) {
        if (world == null) return null;
        for (long k : seen) {
            TrackedEntity e = world.entity(k);
            if (e != null && e.uuid().equals(id)) return e;
        }
        return null;
    }

    TrackedEntity entity(long id) {
        return world == null || !seen.contains(id) ? null : world.entity(id);
    }

    /** The entities this bot sees, nearest first, optionally of one type. */
    List<TrackedEntity> near(Position at, String type, double range) {
        List<TrackedEntity> out = new ArrayList<>();
        if (world == null) return out;
        for (long k : seen) {
            TrackedEntity e = world.entity(k);
            if (e == null || (type != null && !e.type().equals(type))) continue;
            if (distance(e, at) <= range) out.add(e);
        }
        out.sort(Comparator.comparingDouble(e -> distance(e, at)));
        return out;
    }

    static double distance(TrackedEntity e, Position at) {
        return Math.sqrt((e.x() - at.x()) * (e.x() - at.x()) + (e.y() - at.y()) * (e.y() - at.y()) + (e.z() - at.z()) * (e.z() - at.z()));
    }

    static JsonObject json(TrackedEntity e, Position at) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.id());
        o.addProperty("uuid", e.uuid().toString());
        o.addProperty("type", e.type());
        if (e.name() != null) o.addProperty("name", e.name());
        o.addProperty("x", Jsons.round(e.x()));
        o.addProperty("y", Jsons.round(e.y()));
        o.addProperty("z", Jsons.round(e.z()));
        o.addProperty("distance", Jsons.round(distance(e, at)));
        return o;
    }

    JsonArray nearJson(Position at, String type, double range, int limit) {
        JsonArray a = new JsonArray();
        for (TrackedEntity e : near(at, type, range)) {
            if (a.size() >= limit) break;
            a.add(json(e, at));
        }
        return a;
    }
}
