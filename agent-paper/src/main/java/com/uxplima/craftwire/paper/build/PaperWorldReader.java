package com.uxplima.craftwire.paper.build;

import com.uxplima.craftwire.build.Changes;
import com.uxplima.craftwire.build.WorldReader;
import com.uxplima.craftwire.mc.BuildStates;
import com.uxplima.craftwire.paper.Sync;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.levelgen.Heightmap;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

/** Script reads from copies of chunk sections taken on the chunk's own thread. Only for the build worker threads. */
final class PaperWorldReader implements WorldReader {
    private record Copy(PalettedContainer<BlockState>[] sections, int[] ground) {}

    private final Sync sync;
    private final World world;
    private final ServerLevel level;
    private final Map<Long, Copy> chunks = new ConcurrentHashMap<>();

    PaperWorldReader(Sync sync, World world) {
        this.sync = sync;
        this.world = world;
        this.level = ((CraftWorld) world).getHandle();
    }

    @Override public int read(int x, int y, int z) {
        if (y < level.getMinY() || y > level.getMaxY()) return BuildStates.INSTANCE.air();
        Copy c = chunk(x >> 4, z >> 4);
        return BuildStates.INSTANCE.id(c.sections()[level.getSectionIndex(y)].get(x & 15, y & 15, z & 15));
    }

    @Override public int ground(int x, int z) {
        return chunk(x >> 4, z >> 4).ground()[(z & 15) << 4 | (x & 15)];
    }

    @SuppressWarnings("unchecked")
    private Copy chunk(int cx, int cz) {
        return chunks.computeIfAbsent(Changes.chunkKey(cx, cz), k -> sync.loaded(world, cx, cz, () -> {
            LevelChunk ch = level.getChunk(cx, cz);
            LevelChunkSection[] s = ch.getSections();
            PalettedContainer<BlockState>[] copy = new PalettedContainer[s.length];
            for (int i = 0; i < s.length; i++) copy[i] = s[i].getStates().copy();
            int[] ground = new int[256];
            for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) ground[z << 4 | x] = ch.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            return new Copy(copy, ground);
        }).join());
    }
}
