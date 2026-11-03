package com.uxplima.craftwire.botrunner.bedrock;

import com.uxplima.craftwire.bedrock.codec.BedrockCodecException;
import com.uxplima.craftwire.bedrock.codec.SubChunkDecoder;
import com.uxplima.craftwire.bedrock.packet.PlayOut;
import com.uxplima.craftwire.bedrock.packet.WorldPackets;
import com.uxplima.craftwire.bot.path.BlockView;
import com.uxplima.craftwire.bot.path.Cell;
import com.uxplima.craftwire.botrunner.world.ChunkColumn;
import com.uxplima.craftwire.botrunner.world.SharedWorld;
import com.uxplima.craftwire.botrunner.world.WorldCache;
import com.uxplima.craftwire.botrunner.world.WorldKey;
import io.netty.buffer.ByteBuf;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The blocks one Bedrock bot sees: in world mode `shared` the chunks of its server and dimension in the runner's
 * {@link WorldCache}, in mode `none` nothing. Chunks come whole (LevelChunk) or as a column the bot then asks for
 * sub-chunk by sub-chunk (SubChunkRequest), as the server chooses. Bedrock never unloads chunks by packet: the client
 * drops what lies outside the NetworkChunkPublisherUpdate radius. Entities are in {@link BedrockEntities}. Runner
 * loop thread.
 */
final class BedrockWorld {
    private static final BlockView NOTHING = (x, y, z) -> Cell.UNKNOWN;
    private static final String[] DIMENSIONS = {"minecraft:overworld", "minecraft:the_nether", "minecraft:the_end"};
    private static final int[] MIN_SUB = {-4, 0, 0};
    private static final int[] SUB_COUNT = {24, 8, 16};

    private final WorldCache cache;
    private final String server;
    private final boolean shared;
    private final Object holder;
    private final Consumer<ByteBuf> out;
    private final Set<Long> held = new HashSet<>();
    private final BedrockNames names = new BedrockNames();
    private BedrockBlocks.Palette palette;
    private SharedWorld world;
    private int dimension;

    BedrockWorld(WorldCache cache, String server, boolean shared, Object holder, Consumer<ByteBuf> out) {
        this.cache = cache;
        this.server = server;
        this.shared = shared;
        this.holder = holder;
        this.out = out;
    }

    /** StartGame or ChangeDimension: the old dimension is let go first. */
    void enter(int dim, BedrockBlocks.Palette p) {
        leave();
        names.clear();
        dimension = dim >= 0 && dim < DIMENSIONS.length ? dim : 0;
        palette = p;
        if (shared && p != null) world = cache.enter(new WorldKey(server, DIMENSIONS[dimension]), p);
    }

    void leave() {
        if (world != null) cache.leave(world, holder);
        world = null;
        held.clear();
    }

    int dimensionId() {
        return dimension;
    }

    String dimension() {
        return DIMENSIONS[dimension];
    }

    boolean shared() {
        return shared;
    }

    BedrockBlocks.Palette palette() {
        return palette;
    }

    BlockView view() {
        return world == null ? NOTHING : world;
    }

    void chunk(WorldPackets.LevelChunk c) {
        if (world == null || c.dimension() != dimension) return;
        ChunkColumn known = world.chunk(c.x(), c.z());
        if (known != null) {
            hold(c.x(), c.z(), known);
            return;
        }
        ChunkColumn column = new ChunkColumn(MIN_SUB[dimension] * 16, SUB_COUNT[dimension]);
        if (c.requestMode()) {
            hold(c.x(), c.z(), column);
            requestSubChunks(c.x(), c.z(), c.subChunkLimit());
            return;
        }
        try {
            List<SubChunkDecoder.Section> sections = SubChunkDecoder.levelChunk(c.payload(), c.subChunkCount(), MIN_SUB[dimension]);
            for (SubChunkDecoder.Section s : sections) if (s != null) column.section(s.subY() - MIN_SUB[dimension], dense(s));
        } catch (BedrockCodecException | IndexOutOfBoundsException e) {
            // A chunk this decoder cannot read stays unknown (a wall to the pathfinder) rather than wrong.
            return;
        }
        hold(c.x(), c.z(), column);
        try {
            names.blockEntities(SubChunkDecoder.levelChunkBlockEntities(c.payload(), c.subChunkCount(), SUB_COUNT[dimension]));
        } catch (BedrockCodecException | IndexOutOfBoundsException e) {
            // The blocks are in; only the block entities' names are lost, and menus fall back to the block's name.
        }
    }

    private void hold(int cx, int cz, ChunkColumn column) {
        world.loadChunk(cx, cz, column, holder);
        held.add(SharedWorld.chunkKey(cx, cz));
    }

    /** SubChunkRequest for the column, base y 0, one offset per sub-chunk from the lowest up to the server's limit. */
    private void requestSubChunks(int cx, int cz, int limit) {
        int count = limit < 0 ? SUB_COUNT[dimension] : Math.min(limit, SUB_COUNT[dimension]);
        byte[] offsets = new byte[count * 3];
        for (int i = 0; i < count; i++) offsets[i * 3 + 1] = (byte) (MIN_SUB[dimension] + i);
        out.accept(PlayOut.subChunkRequest(dimension, cx, 0, cz, offsets));
    }

    void subChunks(WorldPackets.SubChunk s) {
        if (world == null || s.dimension() != dimension) return;
        for (WorldPackets.SubChunkEntry e : s.entries()) {
            int cx = s.x() + e.dx();
            int cz = s.z() + e.dz();
            int subY = s.y() + e.dy();
            ChunkColumn column = world.chunk(cx, cz);
            if (column == null) continue;
            int index = subY - MIN_SUB[dimension];
            if (e.result() == WorldPackets.SubChunkEntry.SUCCESS_ALL_AIR) {
                column.section(index, null);
            } else if (e.result() == WorldPackets.SubChunkEntry.SUCCESS && e.payload().length > 0) {
                try {
                    SubChunkDecoder.Entry entry = SubChunkDecoder.subChunkEntry(e.payload(), subY);
                    column.section(index, entry.section() == null ? null : dense(entry.section()));
                    names.blockEntities(entry.blockEntities());
                } catch (BedrockCodecException | IndexOutOfBoundsException bad) {
                    column.section(index, null);
                }
            }
        }
    }

    /** A decoded sub-chunk in the shared world's dense ids. */
    private char[] dense(SubChunkDecoder.Section s) {
        char[] map = new char[s.palette().length];
        for (int i = 0; i < map.length; i++) map[i] = (char) palette.dense(s.palette()[i]);
        char[] states = new char[SubChunkDecoder.BLOCKS];
        short[] idx = s.indices();
        for (int i = 0; i < states.length; i++) states[i] = map[idx[i] & 0xffff];
        return states;
    }

    void block(WorldPackets.UpdateBlock u) {
        if (world == null || u.layer() != 0) return;
        world.setBlock(u.x(), u.y(), u.z(), palette.dense(u.runtimeId()));
    }

    /** The publisher's area moved or shrank: chunks outside it are dropped, as the client drops them. */
    void publisher(WorldPackets.ChunkPublisher p) {
        if (world == null) return;
        int cx = p.x() >> 4;
        int cz = p.z() >> 4;
        int r = (p.radius() >> 4) + 1;
        held.removeIf(k -> {
            int x = (int) (k >> 32);
            int z = (int) (long) k;
            if (Math.abs(x - cx) <= r && Math.abs(z - cz) <= r) return false;
            world.unloadChunk(x, z, holder);
            return true;
        });
    }

    /** Block entity names; kept in every world mode, since menus are titled by them. */
    BedrockNames names() {
        return names;
    }

    /** The dense state at a block, or -1 when the bot does not have its chunk. */
    int state(int x, int y, int z) {
        return world == null ? -1 : world.blockState(x, y, z);
    }

    /** The shared world of the current dimension, or null in world mode none. */
    SharedWorld sharedWorld() {
        return world;
    }
}
