package com.uxplima.craftwire.bedrock.packet;

import com.uxplima.craftwire.bedrock.codec.Bin;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;

/**
 * The world around the bot: chunk columns (LevelChunk), sub-chunks sent on request (SubChunk), single block changes
 * and the chunk radius. Payloads stay raw bytes here; codec/SubChunkDecoder turns them into block states. The bot
 * turns the blob cache off (ClientCacheStatus false), so payloads are always complete.
 */
public final class WorldPackets {
    public record ChunkRadius(int radius) implements Clientbound {}

    /**
     * LevelChunk. With `requestMode` (SubChunkLimit present, protocol 2225) the payload holds no sub-chunks and the
     * client asks for them with SubChunkRequest, up to `subChunkLimit` of them (-1: no limit); otherwise the payload
     * starts with `subChunkCount` sub-chunks (at most 64).
     */
    public record LevelChunk(int x, int z, int dimension, int subChunkCount, boolean requestMode, int subChunkLimit, byte[] payload)
            implements Clientbound {}

    /** One SubChunkEntry: offset from the request's base, result (1 success, 6 all air), the sub-chunk bytes. */
    public record SubChunkEntry(int dx, int dy, int dz, int result, byte[] payload) {
        public static final int SUCCESS = 1;
        public static final int SUCCESS_ALL_AIR = 6;
    }

    public record SubChunk(int dimension, int x, int y, int z, List<SubChunkEntry> entries) implements Clientbound {}

    /** UpdateBlock: layer 0 is the block, layer 1 the liquid sharing its cell. */
    public record UpdateBlock(int x, int y, int z, int runtimeId, int layer) implements Clientbound {}

    /** NetworkChunkPublisherUpdate: the centre and radius of the area the server keeps sending chunks for. */
    public record ChunkPublisher(int x, int y, int z, int radius) implements Clientbound {}

    private WorldPackets() {}

    static Clientbound decode(int id, ByteBuf b) {
        return switch (id) {
            case PacketIds.CHUNK_RADIUS_UPDATED -> new ChunkRadius(Bin.readVarInt(b));
            case PacketIds.LEVEL_CHUNK -> levelChunk(b);
            case PacketIds.SUB_CHUNK -> subChunk(b);
            case PacketIds.UPDATE_BLOCK -> {
                int[] p = Bin.readBlockPos(b);
                int runtime = Bin.readVarUInt(b);
                Bin.readVarUInt(b);
                yield new UpdateBlock(p[0], p[1], p[2], runtime, Bin.readVarUInt(b));
            }
            case PacketIds.NETWORK_CHUNK_PUBLISHER_UPDATE -> {
                int[] p = Bin.readBlockPos(b);
                yield new ChunkPublisher(p[0], p[1], p[2], Bin.readVarUInt(b));
            }
            default -> null;
        };
    }

    private static LevelChunk levelChunk(ByteBuf b) {
        int x = Bin.readVarInt(b);
        int z = Bin.readVarInt(b);
        int dimension = Bin.readVarInt(b);
        int count = Bin.readVarUInt(b);
        boolean request = Bin.readBool(b);
        int limit = request ? Bin.readVarInt(b) : -1;
        Bin.readBool(b);
        int hashes = Bin.readVarUInt(b);
        b.skipBytes(hashes * 8);
        byte[] payload = Bin.readBytes(b);
        return new LevelChunk(x, z, dimension, count, request, limit, payload);
    }

    private static SubChunk subChunk(ByteBuf b) {
        Bin.readBool(b);
        int dimension = Bin.readVarInt(b);
        int x = b.readIntLE();
        int y = b.readIntLE();
        int z = b.readIntLE();
        int n = Bin.readVarUInt(b);
        List<SubChunkEntry> entries = new ArrayList<>(Math.min(n, 1024));
        for (int i = 0; i < n; i++) {
            int dx = b.readByte();
            int dy = b.readByte();
            int dz = b.readByte();
            int result = b.readUnsignedByte();
            byte[] payload = Bin.readBool(b) ? Bin.readBytes(b) : new byte[0];
            b.skipBytes(1);
            if (Bin.readBool(b)) skipHeightMap(b);
            b.skipBytes(1);
            if (Bin.readBool(b)) skipHeightMap(b);
            if (Bin.readBool(b)) b.skipBytes(8);
            entries.add(new SubChunkEntry(dx, dy, dz, result, payload));
        }
        return new SubChunk(dimension, x, y, z, entries);
    }

    /** HeightMap: 16 rows, each a varuint 16 and sixteen int8 heights. */
    private static void skipHeightMap(ByteBuf b) {
        for (int row = 0; row < 16; row++) {
            Bin.readVarUInt(b);
            b.skipBytes(16);
        }
    }
}
