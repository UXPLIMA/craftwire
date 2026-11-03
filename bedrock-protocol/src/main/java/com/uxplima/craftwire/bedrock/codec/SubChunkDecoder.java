package com.uxplima.craftwire.bedrock.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;

/**
 * Decodes the network form of Bedrock sub-chunks (16x16x16 blocks), as LevelChunk and SubChunk carry them. Only the
 * first storage layer (the blocks) is kept; the second holds the water of waterlogged blocks.
 *
 * <p>Sub-chunk: version byte (1, 8 or 9), for 8 and 9 a storage count, for 9 also the sub-chunk's y index. Each
 * storage: a header byte (bits per block &lt;&lt; 1 | runtime flag), 4096 indices packed into little-endian uint32
 * words, then the palette of zigzag varint32 network ids (a count first unless bits is 0, which has one entry).
 */
public final class SubChunkDecoder {
    public static final int BLOCKS = 16 * 16 * 16;

    /**
     * One decoded sub-chunk: `palette` holds network ids and `indices` the palette index of every block in YZX order
     * (y &lt;&lt; 8 | z &lt;&lt; 4 | x), converted from Bedrock's XZY so callers index it like Java sections.
     */
    public record Section(int subY, int[] palette, short[] indices) {
        public int networkId(int x, int y, int z) {
            return palette[indices[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] & 0xffff];
        }
    }

    private SubChunkDecoder() {}

    /**
     * The sub-chunks at the start of a LevelChunk payload, from the dimension's lowest sub-chunk `minSubY` up. A
     * sub-chunk with no storages is all air and comes back as null.
     */
    public static List<Section> levelChunk(byte[] payload, int count, int minSubY) {
        ByteBuf b = Unpooled.wrappedBuffer(payload);
        List<Section> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) out.add(read(b, minSubY + i));
        return out;
    }

    /** One SubChunk entry's payload (null when all air); `fallbackSubY` is used when the version carries no y index. */
    public static Section subChunk(byte[] payload, int fallbackSubY) {
        return read(Unpooled.wrappedBuffer(payload), fallbackSubY);
    }

    /** Section.subY is the absolute sub-chunk y (block y >> 4), as version 9 stores it. */
    private static Section read(ByteBuf b, int fallbackSubY) {
        int version = b.readUnsignedByte();
        int storages = 1;
        int subY = fallbackSubY;
        switch (version) {
            case 1 -> { }
            case 8 -> storages = b.readUnsignedByte();
            case 9 -> {
                storages = b.readUnsignedByte();
                subY = b.readByte();
            }
            default -> throw new BedrockCodecException("unsupported sub-chunk version " + version);
        }
        if (storages == 0) return null;
        Section first = storage(b, subY);
        for (int i = 1; i < storages; i++) storage(b, subY);
        return first;
    }

    private static Section storage(ByteBuf b, int subY) {
        int header = b.readUnsignedByte();
        if ((header & 1) == 0) throw new BedrockCodecException("sub-chunk storage uses a persistent (NBT) palette");
        int bits = header >> 1;
        short[] indices = new short[BLOCKS];
        if (bits == 0) {
            return new Section(subY, new int[] {Bin.readVarInt(b)}, indices);
        }
        int perWord = 32 / bits;
        int words = (BLOCKS + perWord - 1) / perWord;
        int mask = (1 << bits) - 1;
        int at = 0;
        for (int w = 0; w < words; w++) {
            int word = b.readIntLE();
            for (int k = 0; k < perWord && at < BLOCKS; k++, at++) {
                // Bedrock's index is XZY: x << 8 | z << 4 | y; stored here as YZX.
                int x = at >> 8;
                int z = (at >> 4) & 15;
                int y = at & 15;
                indices[(y << 8) | (z << 4) | x] = (short) ((word >>> (k * bits)) & mask);
            }
        }
        int size = Bin.readVarInt(b);
        if (size <= 0 || size > BLOCKS) throw new BedrockCodecException("sub-chunk palette size " + size);
        int[] palette = new int[size];
        for (int i = 0; i < size; i++) palette[i] = Bin.readVarInt(b);
        for (short s : indices) {
            if ((s & 0xffff) >= size) throw new BedrockCodecException("sub-chunk index " + s + " outside its palette of " + size);
        }
        return new Section(subY, palette, indices);
    }
}
