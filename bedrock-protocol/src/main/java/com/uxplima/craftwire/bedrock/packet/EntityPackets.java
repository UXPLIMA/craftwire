package com.uxplima.craftwire.bedrock.packet;

import com.uxplima.craftwire.bedrock.codec.BedrockCodecException;
import com.uxplima.craftwire.bedrock.codec.Bin;
import com.uxplima.craftwire.bedrock.codec.Nbt;
import io.netty.buffer.ByteBuf;
import java.util.UUID;

/** Entities and the bot itself: spawns, removals, moves, server corrections, health, game mode, respawn. */
public final class EntityPackets {
    /** AddPlayer and AddActor, reduced to what tracking needs; `name` is the player name or the entity's name tag. */
    public record AddEntity(long runtimeId, long uniqueId, UUID uuid, String type, String name, float x, float y, float z) implements Clientbound {}

    public record RemoveEntity(long uniqueId) implements Clientbound {}

    /** MoveActorAbsolute, MoveActorDelta and MovePlayer for others; NaN marks an axis a delta did not change. */
    public record MoveEntity(long runtimeId, float x, float y, float z, float yaw, float pitch) implements Clientbound {}

    /**
     * MovePlayer (mode 0 normal, 1 reset, 2 teleport, 3 rotation), or CorrectPlayerMovePrediction for the bot's own
     * body (`correction`, runtime id -1): the server's answer when it disagrees with the bot's predicted position.
     */
    public record MoveSelf(long runtimeId, float x, float y, float z, float yaw, float pitch, int mode, boolean correction, long tick)
            implements Clientbound {}

    public record Motion(long runtimeId, float x, float y, float z) implements Clientbound {}

    /** UpdateAttributes, reduced to the values the bot reports (minecraft:health, minecraft:player.hunger). */
    public record Attributes(long runtimeId, float health, float hunger) implements Clientbound {}

    public record GameMode(int mode) implements Clientbound {}

    /** Respawn: state 0 searching, 1 ready (the server's spawn point). */
    public record Respawn(float x, float y, float z, int state, long runtimeId) implements Clientbound {}

    public record ChangeDimension(int dimension, float x, float y, float z) implements Clientbound {}

    private EntityPackets() {}

    static Clientbound decode(int id, ByteBuf b) {
        return switch (id) {
            case PacketIds.ADD_PLAYER -> addPlayer(b);
            case PacketIds.ADD_ACTOR -> addActor(b);
            case PacketIds.REMOVE_ACTOR -> new RemoveEntity(Bin.readVarLong(b));
            case PacketIds.MOVE_ACTOR_ABSOLUTE -> {
                long rid = Bin.readVarULong(b);
                b.skipBytes(1);
                float[] p = Bin.readVec3(b);
                float pitch = Bin.readByteAngle(b);
                float yaw = Bin.readByteAngle(b);
                yield new MoveEntity(rid, p[0], p[1], p[2], yaw, pitch);
            }
            case PacketIds.MOVE_ACTOR_DELTA -> delta(b);
            case PacketIds.MOVE_PLAYER -> movePlayer(b);
            case PacketIds.CORRECT_PLAYER_MOVE_PREDICTION -> correction(b);
            case PacketIds.SET_ACTOR_MOTION -> {
                long rid = Bin.readVarULong(b);
                float[] v = Bin.readVec3(b);
                yield new Motion(rid, v[0], v[1], v[2]);
            }
            case PacketIds.UPDATE_ATTRIBUTES -> attributes(b);
            case PacketIds.SET_HEALTH -> new Attributes(-1, Bin.readVarInt(b), Float.NaN);
            case PacketIds.SET_PLAYER_GAME_TYPE -> new GameMode(Bin.readVarInt(b));
            case PacketIds.RESPAWN -> {
                float[] p = Bin.readVec3(b);
                yield new Respawn(p[0], p[1], p[2], b.readUnsignedByte(), Bin.readVarULong(b));
            }
            case PacketIds.CHANGE_DIMENSION -> {
                int dim = Bin.readVarInt(b);
                float[] p = Bin.readVec3(b);
                yield new ChangeDimension(dim, p[0], p[1], p[2]);
            }
            default -> null;
        };
    }

    /** AddPlayer: the unique id comes last, in the ability data after the metadata and properties. */
    private static AddEntity addPlayer(ByteBuf b) {
        UUID uuid = Bin.readUuid(b);
        String name = Bin.readString(b);
        long rid = Bin.readVarULong(b);
        Bin.readString(b);
        float[] p = Bin.readVec3(b);
        b.skipBytes(12 + 12);
        BedrockItem.read(b);
        Bin.readVarInt(b);
        metadataName(b);
        properties(b);
        long unique = b.readLongLE();
        return new AddEntity(rid, unique, uuid, "minecraft:player", name, p[0], p[1], p[2]);
    }

    private static AddEntity addActor(ByteBuf b) {
        long unique = Bin.readVarLong(b);
        long rid = Bin.readVarULong(b);
        String type = Bin.readString(b);
        float[] p = Bin.readVec3(b);
        b.skipBytes(12 + 16);
        int attributes = Bin.readVarUInt(b);
        for (int i = 0; i < attributes; i++) {
            Bin.readString(b);
            b.skipBytes(12);
        }
        String name = metadataName(b);
        return new AddEntity(rid, unique, null, type, name == null || name.isEmpty() ? null : name, p[0], p[1], p[2]);
    }

    /** Reads entity metadata and returns its name tag (key 4), or null. */
    static String metadataName(ByteBuf b) {
        String name = null;
        int n = Bin.readVarUInt(b);
        for (int i = 0; i < n; i++) {
            int key = Bin.readVarUInt(b);
            int type = Bin.readVarUInt(b);
            b.skipBytes(1);
            switch (type) {
                case 0 -> b.skipBytes(1);
                case 1 -> b.skipBytes(2);
                case 2 -> Bin.readVarInt(b);
                case 3 -> b.skipBytes(4);
                case 4 -> {
                    String s = Bin.readString(b);
                    if (key == 4) name = s;
                }
                case 5 -> Nbt.read(b, Nbt.Encoding.NETWORK);
                case 6 -> Bin.readBlockPos(b);
                case 7 -> Bin.readVarLong(b);
                case 8 -> b.skipBytes(12);
                default -> throw new BedrockCodecException("unknown entity metadata type " + type);
            }
        }
        return name;
    }

    private static void properties(ByteBuf b) {
        int ints = Bin.readVarUInt(b);
        for (int i = 0; i < ints; i++) {
            Bin.readVarUInt(b);
            Bin.readVarInt(b);
        }
        int floats = Bin.readVarUInt(b);
        for (int i = 0; i < floats; i++) {
            Bin.readVarUInt(b);
            b.skipBytes(4);
        }
    }

    private static MoveEntity delta(ByteBuf b) {
        long rid = Bin.readVarULong(b);
        float x = Bin.readBool(b) ? b.readFloatLE() : Float.NaN;
        float y = Bin.readBool(b) ? b.readFloatLE() : Float.NaN;
        float z = Bin.readBool(b) ? b.readFloatLE() : Float.NaN;
        float pitch = Bin.readBool(b) ? Bin.readByteAngle(b) : Float.NaN;
        float yaw = Bin.readBool(b) ? Bin.readByteAngle(b) : Float.NaN;
        return new MoveEntity(rid, x, y, z, yaw, pitch);
    }

    /** MovePlayer: the bot tells its own moves apart from others' by runtime id; TeleportData is two int32. */
    private static MoveSelf movePlayer(ByteBuf b) {
        long rid = Bin.readVarULong(b);
        float[] p = Bin.readVec3(b);
        float pitch = b.readFloatLE();
        float yaw = b.readFloatLE();
        b.skipBytes(4);
        int mode = b.readUnsignedByte();
        b.skipBytes(1);
        Bin.readVarULong(b);
        if (Bin.readBool(b)) b.skipBytes(8);
        long tick = Bin.readVarULong(b);
        return new MoveSelf(rid, p[0], p[1], p[2], yaw, pitch, mode, false, tick);
    }

    /** CorrectPlayerMovePrediction: prediction type 0 is the player, 1 a vehicle (which a bot never rides). */
    private static MoveSelf correction(ByteBuf b) {
        int type = b.readUnsignedByte();
        float[] p = Bin.readVec3(b);
        b.skipBytes(12);
        float pitch = b.readFloatLE();
        float yaw = b.readFloatLE();
        if (Bin.readBool(b)) b.skipBytes(4);
        b.skipBytes(1);
        long tick = Bin.readVarULong(b);
        return type == 0 ? new MoveSelf(-1, p[0], p[1], p[2], yaw, pitch, 0, true, tick) : null;
    }

    private static Attributes attributes(ByteBuf b) {
        long rid = Bin.readVarULong(b);
        float health = Float.NaN;
        float hunger = Float.NaN;
        int n = Bin.readVarUInt(b);
        for (int i = 0; i < n; i++) {
            b.skipBytes(8);
            float value = b.readFloatLE();
            b.skipBytes(12);
            String name = Bin.readString(b);
            int modifiers = Bin.readVarUInt(b);
            for (int m = 0; m < modifiers; m++) {
                Bin.readString(b);
                Bin.readString(b);
                b.skipBytes(4 + 4 + 4 + 1);
            }
            if (name.equals("minecraft:health")) health = value;
            else if (name.equals("minecraft:player.hunger")) hunger = value;
        }
        return new Attributes(rid, health, hunger);
    }
}
