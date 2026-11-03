package com.uxplima.craftwire.bedrock.packet;

import com.uxplima.craftwire.bedrock.codec.Bin;
import com.uxplima.craftwire.bedrock.codec.Nbt;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;
import java.util.Map;

/**
 * An item stack as an ItemInstance carries it: the item's network id (from ItemRegistry), count, metadata, the
 * stack network id the server tracks it by, the block it places, and the little-endian NBT of its user data
 * (display name and lore live there). Network id 0 is air. `userData` keeps the bytes as received, so an item the
 * bot sends back (held item in a transaction) matches what the server holds, NBT and can-place-on lists included.
 */
public record BedrockItem(int networkId, int count, int meta, int stackId, int blockRuntimeId, Map<String, Object> nbt, byte[] userData) {
    public static final BedrockItem EMPTY = new BedrockItem(0, 0, 0, 0, 0, Map.of(), new byte[0]);

    public boolean empty() {
        return networkId == 0 || count == 0;
    }

    /** ItemInstance: int16 id, uint16 count, varuint meta, optional varint stack id, varuint block runtime id, user data. */
    static BedrockItem read(ByteBuf b) {
        int id = b.readShortLE();
        int count = b.readUnsignedShortLE();
        int meta = Bin.readVarUInt(b);
        int stackId = Bin.readBool(b) ? Bin.readVarInt(b) : 0;
        int block = Bin.readVarUInt(b);
        byte[] user = Bin.readBytes(b);
        return new BedrockItem(id, count, meta, stackId, block, userNbt(user), user);
    }

    /** The user data's NBT: int16 length 0 (none), or -1 and a version byte, then little-endian NBT. */
    private static Map<String, Object> userNbt(byte[] user) {
        if (user.length < 2) return Map.of();
        ByteBuf u = Unpooled.wrappedBuffer(user);
        short marker = u.readShortLE();
        if (marker == 0) return Map.of();
        if (marker == -1) {
            if (u.readUnsignedByte() != 1) return Map.of();
        }
        try {
            return Nbt.readCompound(u, Nbt.Encoding.LITTLE_ENDIAN);
        } catch (RuntimeException e) {
            // Item NBT a plugin made odd should not cost the bot its inventory view.
            return Map.of();
        }
    }

    /** Writes this stack as an ItemInstance (what a bot echoes back in transactions). */
    void write(ByteBuf b, int shieldId) {
        b.writeShortLE(networkId);
        b.writeShortLE(count);
        Bin.writeVarUInt(b, meta);
        Bin.writeBool(b, stackId != 0);
        if (stackId != 0) Bin.writeVarInt(b, stackId);
        Bin.writeVarUInt(b, blockRuntimeId);
        writeUserData(b, shieldId);
    }

    /**
     * User data: the bytes received, or for an item the bot made up an empty one (int16 0, no can-place-on or
     * can-break lists, and the blocking tick for a shield).
     */
    void writeUserData(ByteBuf b, int shieldId) {
        if (networkId == 0) {
            Bin.writeVarUInt(b, 0);
            return;
        }
        if (userData.length > 0) {
            Bin.writeBytes(b, userData);
            return;
        }
        ByteBuf user = Unpooled.buffer(18);
        user.writeShortLE(0);
        user.writeIntLE(0);
        user.writeIntLE(0);
        if (networkId == shieldId) user.writeLongLE(0);
        byte[] bytes = new byte[user.readableBytes()];
        user.readBytes(bytes);
        Bin.writeBytes(b, bytes);
    }

    /** The display name set by an anvil or a plugin (NBT display.Name), or null. */
    public String customName() {
        Object display = nbt.get("display");
        if (display instanceof Map<?, ?> d && d.get("Name") instanceof String s) return s;
        return null;
    }

    /** Lore lines (NBT display.Lore). */
    public List<String> lore() {
        Object display = nbt.get("display");
        if (display instanceof Map<?, ?> d && d.get("Lore") instanceof List<?> l) {
            return l.stream().filter(String.class::isInstance).map(String.class::cast).toList();
        }
        return List.of();
    }
}
