package com.uxplima.craftwire.bedrock.codec;

import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The Bedrock protocol's primitive types on a Netty buffer: unsigned and zig-zag varints of 32 and 64 bits,
 * little-endian fixed-width numbers (the protocol's default byte order), varint-prefixed strings and byte slices,
 * UUIDs as two little-endian longs, vectors and block positions. Stateless; the caller owns the buffer.
 */
public final class Bin {
    /** Strings and slices longer than this are rejected; nothing a bot reads legitimately comes close. */
    private static final int MAX_LENGTH = 64 * 1024 * 1024;

    private Bin() {}

    public static int readVarUInt(ByteBuf b) {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            byte x = b.readByte();
            value |= (x & 0x7f) << shift;
            if ((x & 0x80) == 0) return value;
        }
        throw new BedrockCodecException("varint32 is too long");
    }

    public static void writeVarUInt(ByteBuf b, int value) {
        while ((value & ~0x7f) != 0) {
            b.writeByte((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        b.writeByte(value);
    }

    public static long readVarULong(ByteBuf b) {
        long value = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            byte x = b.readByte();
            value |= (long) (x & 0x7f) << shift;
            if ((x & 0x80) == 0) return value;
        }
        throw new BedrockCodecException("varint64 is too long");
    }

    public static void writeVarULong(ByteBuf b, long value) {
        while ((value & ~0x7fL) != 0) {
            b.writeByte((int) ((value & 0x7f) | 0x80));
            value >>>= 7;
        }
        b.writeByte((int) value);
    }

    /** Zig-zag varint32 (the protocol's Varint32). */
    public static int readVarInt(ByteBuf b) {
        int raw = readVarUInt(b);
        return (raw >>> 1) ^ -(raw & 1);
    }

    public static void writeVarInt(ByteBuf b, int value) {
        writeVarUInt(b, (value << 1) ^ (value >> 31));
    }

    /** Zig-zag varint64 (the protocol's Varint64, used for entity unique ids). */
    public static long readVarLong(ByteBuf b) {
        long raw = readVarULong(b);
        return (raw >>> 1) ^ -(raw & 1);
    }

    public static void writeVarLong(ByteBuf b, long value) {
        writeVarULong(b, (value << 1) ^ (value >> 63));
    }

    public static boolean readBool(ByteBuf b) {
        return b.readByte() != 0;
    }

    public static void writeBool(ByteBuf b, boolean v) {
        b.writeByte(v ? 1 : 0);
    }

    public static String readString(ByteBuf b) {
        int n = length(readVarUInt(b), b);
        String s = b.toString(b.readerIndex(), n, StandardCharsets.UTF_8);
        b.skipBytes(n);
        return s;
    }

    public static void writeString(ByteBuf b, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarUInt(b, bytes.length);
        b.writeBytes(bytes);
    }

    /** A string with a little-endian int16 length (item user data's StringUTF). */
    public static String readShortString(ByteBuf b) {
        int n = length(b.readUnsignedShortLE(), b);
        String s = b.toString(b.readerIndex(), n, StandardCharsets.UTF_8);
        b.skipBytes(n);
        return s;
    }

    public static byte[] readBytes(ByteBuf b) {
        int n = length(readVarUInt(b), b);
        byte[] out = new byte[n];
        b.readBytes(out);
        return out;
    }

    public static void writeBytes(ByteBuf b, byte[] bytes) {
        writeVarUInt(b, bytes.length);
        b.writeBytes(bytes);
    }

    /** Skips a varint-prefixed string or byte slice. */
    public static void skipBytes(ByteBuf b) {
        b.skipBytes(length(readVarUInt(b), b));
    }

    /** UUIDs go out as their two halves, each a little-endian long (most significant half first). */
    public static UUID readUuid(ByteBuf b) {
        long most = b.readLongLE();
        long least = b.readLongLE();
        return new UUID(most, least);
    }

    public static void writeUuid(ByteBuf b, UUID id) {
        b.writeLongLE(id.getMostSignificantBits());
        b.writeLongLE(id.getLeastSignificantBits());
    }

    public static float[] readVec3(ByteBuf b) {
        return new float[] {b.readFloatLE(), b.readFloatLE(), b.readFloatLE()};
    }

    public static void writeVec3(ByteBuf b, float x, float y, float z) {
        b.writeFloatLE(x);
        b.writeFloatLE(y);
        b.writeFloatLE(z);
    }

    public static void writeVec2(ByteBuf b, float x, float y) {
        b.writeFloatLE(x);
        b.writeFloatLE(y);
    }

    /** BlockPos: three zig-zag varint32s. */
    public static int[] readBlockPos(ByteBuf b) {
        return new int[] {readVarInt(b), readVarInt(b), readVarInt(b)};
    }

    public static void writeBlockPos(ByteBuf b, int x, int y, int z) {
        writeVarInt(b, x);
        writeVarInt(b, y);
        writeVarInt(b, z);
    }

    /** ByteFloat: a rotation in 1/256ths of a turn. */
    public static float readByteAngle(ByteBuf b) {
        return b.readUnsignedByte() * (360f / 256f);
    }

    private static int length(int n, ByteBuf b) {
        if (n < 0 || n > MAX_LENGTH || n > b.readableBytes()) {
            throw new BedrockCodecException("length " + n + " exceeds the " + b.readableBytes() + " bytes left");
        }
        return n;
    }
}
