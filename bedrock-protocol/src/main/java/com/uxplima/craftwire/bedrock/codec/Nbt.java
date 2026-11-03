package com.uxplima.craftwire.bedrock.codec;

import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bedrock NBT in its two encodings: network (varint string lengths, zig-zag varint ints, longs and array lengths;
 * packets and the block state list) and little-endian (fixed-width; item user data). Values become plain Java
 * objects: Byte, Short, Integer, Long, Float, Double, byte[], String, List, Map (in tag order), int[], long[].
 */
public final class Nbt {
    public enum Encoding { NETWORK, LITTLE_ENDIAN }

    private static final int MAX_DEPTH = 512;
    private static final int END = 0;
    private static final int BYTE = 1;
    private static final int SHORT = 2;
    private static final int INT = 3;
    private static final int LONG = 4;
    private static final int FLOAT = 5;
    private static final int DOUBLE = 6;
    private static final int BYTE_ARRAY = 7;
    private static final int STRING = 8;
    private static final int LIST = 9;
    private static final int COMPOUND = 10;
    private static final int INT_ARRAY = 11;
    private static final int LONG_ARRAY = 12;

    private final ByteBuf b;
    private final Encoding e;

    private Nbt(ByteBuf b, Encoding e) {
        this.b = b;
        this.e = e;
    }

    /** One named root tag; its value (a compound for every root the protocol sends), or null for TAG_End. */
    public static Object read(ByteBuf b, Encoding e) {
        Nbt n = new Nbt(b, e);
        int type = b.readUnsignedByte();
        if (type == END) return null;
        n.string();
        return n.value(type, 0);
    }

    /** A root tag that must be a compound; an empty map for TAG_End. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> readCompound(ByteBuf b, Encoding e) {
        Object o = read(b, e);
        if (o == null) return new LinkedHashMap<>();
        if (!(o instanceof Map)) throw new BedrockCodecException("expected a compound root tag");
        return (Map<String, Object>) o;
    }

    private Object value(int type, int depth) {
        if (depth > MAX_DEPTH) throw new BedrockCodecException("NBT nested too deeply");
        return switch (type) {
            case BYTE -> b.readByte();
            case SHORT -> b.readShortLE();
            case INT -> integer();
            case LONG -> e == Encoding.NETWORK ? Bin.readVarLong(b) : b.readLongLE();
            case FLOAT -> b.readFloatLE();
            case DOUBLE -> b.readDoubleLE();
            case BYTE_ARRAY -> {
                byte[] a = new byte[length()];
                b.readBytes(a);
                yield a;
            }
            case STRING -> string();
            case LIST -> list(depth);
            case COMPOUND -> compound(depth);
            case INT_ARRAY -> {
                int[] a = new int[length()];
                for (int i = 0; i < a.length; i++) a[i] = integer();
                yield a;
            }
            case LONG_ARRAY -> {
                long[] a = new long[length()];
                for (int i = 0; i < a.length; i++) a[i] = e == Encoding.NETWORK ? Bin.readVarLong(b) : b.readLongLE();
                yield a;
            }
            default -> throw new BedrockCodecException("unknown NBT tag type " + type);
        };
    }

    private List<Object> list(int depth) {
        int type = b.readUnsignedByte();
        int n = length();
        List<Object> out = new ArrayList<>(Math.min(n, 1024));
        for (int i = 0; i < n; i++) out.add(type == END ? null : value(type, depth + 1));
        return out;
    }

    private Map<String, Object> compound(int depth) {
        Map<String, Object> out = new LinkedHashMap<>();
        while (true) {
            int type = b.readUnsignedByte();
            if (type == END) return out;
            String name = string();
            out.put(name, value(type, depth + 1));
        }
    }

    private int integer() {
        return e == Encoding.NETWORK ? Bin.readVarInt(b) : b.readIntLE();
    }

    private int length() {
        int n = integer();
        if (n < 0 || n > b.readableBytes()) throw new BedrockCodecException("NBT length " + n + " exceeds the data left");
        return n;
    }

    private String string() {
        int n = e == Encoding.NETWORK ? Bin.readVarUInt(b) : b.readUnsignedShortLE();
        if (n < 0 || n > b.readableBytes()) throw new BedrockCodecException("NBT string length " + n + " exceeds the data left");
        String s = b.toString(b.readerIndex(), n, StandardCharsets.UTF_8);
        b.skipBytes(n);
        return s;
    }

    /** An empty compound in network encoding: what a client sends where it has no NBT to give. */
    public static void writeEmptyCompound(ByteBuf b) {
        b.writeByte(COMPOUND);
        b.writeByte(0);
        b.writeByte(END);
    }
}
