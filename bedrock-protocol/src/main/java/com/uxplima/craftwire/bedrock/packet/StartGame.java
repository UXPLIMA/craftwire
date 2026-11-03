package com.uxplima.craftwire.bedrock.packet;

import com.uxplima.craftwire.bedrock.codec.Bin;
import com.uxplima.craftwire.bedrock.codec.Nbt;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * StartGame, the packet that puts the player in the world, read field by field up to what the bot needs (the
 * fields after the block id mode are skipped with the rest of the packet). Game mode 5 means "the world's default".
 *
 * @param customBlocks  blocks the server adds (custom blocks, Geyser's extras), which take part in runtime ids
 * @param hashedBlockIds whether chunk palettes name blocks by their network hash instead of their runtime id
 */
public record StartGame(long uniqueId, long runtimeId, int gameMode, float x, float y, float z, float pitch, float yaw, int dimension,
                        int worldGameMode, boolean serverAuthBlockBreaking, List<CustomBlock> customBlocks, boolean serverAuthInventory,
                        String gameVersion, boolean hashedBlockIds) implements Clientbound {
    public record CustomBlock(String name, Map<String, Object> properties) {}

    static StartGame read(ByteBuf b) {
        long unique = Bin.readVarLong(b);
        long runtime = Bin.readVarULong(b);
        int gameMode = Bin.readVarInt(b);
        float[] pos = Bin.readVec3(b);
        float pitch = b.readFloatLE();
        float yaw = b.readFloatLE();
        b.skipBytes(8 + 2);
        Bin.readString(b);
        int dimension = Bin.readVarInt(b);
        Bin.readVarInt(b);
        int worldGameMode = Bin.readVarInt(b);
        levelSettings(b);
        b.skipBytes(1);
        Bin.readVarInt(b);
        boolean serverAuthBreaking = Bin.readBool(b);
        b.skipBytes(8);
        Bin.readVarInt(b);
        int n = Bin.readVarUInt(b);
        List<CustomBlock> blocks = new ArrayList<>();
        for (int i = 0; i < n; i++) blocks.add(new CustomBlock(Bin.readString(b), Nbt.readCompound(b, Nbt.Encoding.NETWORK)));
        Bin.readString(b);
        boolean serverAuthInventory = Bin.readBool(b);
        String version = Bin.readString(b);
        Nbt.read(b, Nbt.Encoding.NETWORK);
        b.skipBytes(8 + 16);
        Bin.readBool(b);
        boolean hashed = Bin.readBool(b);
        return new StartGame(unique, runtime, gameMode, pos[0], pos[1], pos[2], pitch, yaw, dimension, worldGameMode, serverAuthBreaking,
                blocks, serverAuthInventory, version, hashed);
    }

    /** From Hardcore through Trial: the world's settings, none of which the bot uses. */
    private static void levelSettings(ByteBuf b) {
        b.skipBytes(1);
        Bin.readVarInt(b);
        Bin.readBlockPos(b);
        b.skipBytes(1);
        Bin.readVarInt(b);
        b.skipBytes(3);
        Bin.readVarInt(b);
        Bin.readVarUInt(b);
        b.skipBytes(1);
        Bin.readString(b);
        b.skipBytes(4 + 4 + 3);
        Bin.readVarInt(b);
        Bin.readVarInt(b);
        b.skipBytes(2);
        gameRules(b);
        int experiments = b.readIntLE();
        for (int i = 0; i < experiments; i++) {
            Bin.readString(b);
            b.skipBytes(1);
        }
        b.skipBytes(3 + 1);
        b.skipBytes(4);
        b.skipBytes(10);
        Bin.readString(b);
        b.skipBytes(4 + 4 + 1);
        Bin.readString(b);
        Bin.readString(b);
        if (Bin.readBool(b)) b.skipBytes(1);
        b.skipBytes(1 + 1);
        Bin.readVarInt(b);
        b.skipBytes(1);
        Bin.readString(b);
        Bin.readString(b);
        Bin.readString(b);
    }

    /** GameRule: name, editable, then a varuint type (0 none, 1 bool, 2 uint32, 3 float) and its value. */
    private static void gameRules(ByteBuf b) {
        int n = Bin.readVarUInt(b);
        for (int i = 0; i < n; i++) {
            Bin.readString(b);
            b.skipBytes(1);
            switch (Bin.readVarUInt(b)) {
                case 1 -> b.skipBytes(1);
                case 2, 3 -> b.skipBytes(4);
                default -> { }
            }
        }
    }
}
