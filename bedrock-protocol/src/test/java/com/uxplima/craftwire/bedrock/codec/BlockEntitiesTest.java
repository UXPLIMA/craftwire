package com.uxplima.craftwire.bedrock.codec;

import static org.junit.jupiter.api.Assertions.*;

import com.uxplima.craftwire.bedrock.packet.BedrockVersion;
import com.uxplima.craftwire.bedrock.packet.PacketDecoder;
import com.uxplima.craftwire.bedrock.packet.WorldPackets;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Block entities where Bedrock sends them: after a SubChunk entry's storages, at the end of a LevelChunk payload
 * (after its sub-chunks, one biome storage per sub-chunk of the dimension and the border block count) and in
 * BlockActorData. The NBT is written by hand in the network encoding (varint lengths, zig-zag varint ints).
 */
class BlockEntitiesTest {
    @Test
    void aSubChunkEntryKeepsTheBlockEntitiesAfterItsStorages() {
        ByteBuf b = Unpooled.buffer();
        b.writeByte(9);
        b.writeByte(1);
        b.writeByte(4);
        singleBlockStorage(b, 7);
        chest(b, 3, 70, -12, "§6Daily Rewards");
        chest(b, 4, 70, -12, null);

        SubChunkDecoder.Entry e = SubChunkDecoder.subChunkEntry(ByteBufUtil.getBytes(b), 0);
        assertEquals(4, e.section().subY());
        assertEquals(7, e.section().networkId(0, 0, 0));
        assertEquals(2, e.blockEntities().size());
        assertEquals("§6Daily Rewards", e.blockEntities().get(0).get("CustomName"));
        assertEquals(-12, e.blockEntities().get(0).get("z"));
        assertFalse(e.blockEntities().get(1).containsKey("CustomName"));
    }

    @Test
    void aLevelChunkPayloadHasItsBlockEntitiesAfterTheBiomesAndBorderBlocks() {
        ByteBuf b = Unpooled.buffer();
        b.writeByte(8);
        b.writeByte(1);
        singleBlockStorage(b, 1);
        int storages = 24;
        // A one-bit biome storage (header 1 << 1 | 1, 128 words, a palette of two), a single-biome storage (header
        // 1, one entry), then "same as below" (0xff) for the rest of the overworld's 24 sub-chunks.
        b.writeByte(3);
        b.writeZero(128 * 4);
        Bin.writeVarInt(b, 2);
        Bin.writeVarInt(b, 1);
        Bin.writeVarInt(b, 7);
        b.writeByte(1);
        Bin.writeVarInt(b, 1);
        for (int i = 2; i < storages; i++) b.writeByte(0xff);
        b.writeByte(0);
        chest(b, 0, -60, 0, "Vault");

        List<Map<String, Object>> found = SubChunkDecoder.levelChunkBlockEntities(ByteBufUtil.getBytes(b), 1, storages);
        assertEquals(1, found.size());
        assertEquals("Vault", found.get(0).get("CustomName"));
        assertEquals(-60, found.get(0).get("y"));
    }

    @Test
    void blockActorDataCarriesTheBlockEntityAtItsPosition() {
        ByteBuf b = Unpooled.buffer();
        Bin.writeVarUInt(b, 56);
        Bin.writeBlockPos(b, -100, 64, 250);
        chest(b, -100, 64, 250, "Shop");
        WorldPackets.BlockActorData d = (WorldPackets.BlockActorData) PacketDecoder.decode(b, BedrockVersion.LATEST);
        assertArrayEquals(new int[] {-100, 64, 250}, new int[] {d.x(), d.y(), d.z()});
        assertEquals("Shop", d.nbt().get("CustomName"));
    }

    /** One storage of a single block: header 0 << 1 | 1 (runtime ids), the one palette entry. */
    private static void singleBlockStorage(ByteBuf b, int networkId) {
        b.writeByte(1);
        Bin.writeVarInt(b, networkId);
    }

    /** A chest block entity in network NBT: an unnamed compound with id, x, y, z and an optional CustomName. */
    private static void chest(ByteBuf b, int x, int y, int z, String name) {
        b.writeByte(10);
        Bin.writeVarUInt(b, 0);
        string(b, "id", "Chest");
        integer(b, "x", x);
        integer(b, "y", y);
        integer(b, "z", z);
        if (name != null) string(b, "CustomName", name);
        b.writeByte(0);
    }

    private static void string(ByteBuf b, String key, String value) {
        b.writeByte(8);
        name(b, key);
        name(b, value);
    }

    private static void integer(ByteBuf b, String key, int value) {
        b.writeByte(3);
        name(b, key);
        Bin.writeVarInt(b, value);
    }

    private static void name(ByteBuf b, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        Bin.writeVarUInt(b, bytes.length);
        b.writeBytes(bytes);
    }
}
