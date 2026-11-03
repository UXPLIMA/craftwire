package com.uxplima.craftwire.bedrock.packet;

import com.uxplima.craftwire.bedrock.codec.Bin;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * PlayerList: players added to or removed from the tab list. Skins are skipped field by field (Skin in the
 * protocol), since the bot only reports names.
 */
public record PlayerListPacket(boolean add, List<Entry> entries) implements Clientbound {
    /** One PlayerListEntry; on removal only `uuid` is set. */
    public record Entry(UUID uuid, long uniqueId, String name, String xuid) {}

    static PlayerListPacket read(ByteBuf b) {
        int n = Bin.readVarUInt(b);
        boolean add = false;
        List<Entry> out = new ArrayList<>(Math.min(n, 256));
        for (int i = 0; i < n; i++) {
            add = Bin.readVarUInt(b) == 1;
            b.skipBytes(1);
            UUID uuid = Bin.readUuid(b);
            if (!add) {
                out.add(new Entry(uuid, 0, null, null));
                continue;
            }
            long unique = Bin.readVarLong(b);
            String name = Bin.readString(b);
            String xuid = Bin.readString(b);
            Bin.readString(b);
            Bin.readString(b);
            b.skipBytes(4);
            skipSkin(b);
            b.skipBytes(3 + 4);
            out.add(new Entry(uuid, unique, name, xuid));
        }
        return new PlayerListPacket(add, out);
    }

    private static void skipSkin(ByteBuf b) {
        Bin.readString(b);
        Bin.skipBytes(b);
        b.skipBytes(8);
        Bin.skipBytes(b);
        int animations = Bin.readVarUInt(b);
        for (int i = 0; i < animations; i++) {
            b.skipBytes(8);
            Bin.skipBytes(b);
            Bin.readVarUInt(b);
            b.skipBytes(4);
            Bin.readVarUInt(b);
        }
        b.skipBytes(8);
        Bin.skipBytes(b);
        Bin.skipBytes(b);
        Bin.skipBytes(b);
        Bin.skipBytes(b);
        Bin.readString(b);
        Bin.readString(b);
        b.skipBytes(1 + 4);
        int pieces = Bin.readVarUInt(b);
        for (int i = 0; i < pieces; i++) {
            Bin.readString(b);
            b.skipBytes(4 + 16 + 1);
            Bin.readString(b);
        }
        int tints = Bin.readVarUInt(b);
        for (int i = 0; i < tints; i++) {
            Bin.readString(b);
            b.skipBytes(16);
        }
        b.skipBytes(5);
        Bin.readString(b);
        Bin.readString(b);
    }
}
