package com.uxplima.craftwire.paper.world.render;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;

/** Blocks read from chunk snapshots taken on the server thread, so a render can run on any thread. */
public final class SnapshotSource implements BlockSource {
    private static final Set<Material> ALWAYS_IN_WATER = Set.of(Material.WATER, Material.BUBBLE_COLUMN, Material.KELP, Material.KELP_PLANT,
            Material.SEAGRASS, Material.TALL_SEAGRASS);

    private final Map<Long, ChunkSnapshot> chunks;
    private final int minY;
    private final int maxY;
    private final Map<BlockData, Integer> colors = new ConcurrentHashMap<>();

    public SnapshotSource(Map<Long, ChunkSnapshot> chunks, int minY, int maxY) {
        this.chunks = chunks;
        this.minY = minY;
        this.maxY = maxY;
    }

    public static long key(int chunkX, int chunkZ) {
        return (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);
    }

    private ChunkSnapshot at(int x, int z) {
        return chunks.get(key(x >> 4, z >> 4));
    }

    @Override public boolean loaded(int x, int z) {
        return at(x, z) != null;
    }

    @Override public int top(int x, int z) {
        // The heightmap's answer, with a margin: whether it names the block or the air above it does not matter.
        return Math.min(maxY - 1, at(x, z).getHighestBlockYAt(x & 15, z & 15) + 1);
    }

    @Override public int color(int x, int y, int z) {
        BlockData b = at(x, z).getBlockData(x & 15, y, z & 15);
        return colors.computeIfAbsent(b, d -> {
            if (d.getMaterial().isAir()) return 0;
            Color c = d.getMapColor();
            return c.asRGB();
        });
    }

    @Override public boolean water(int x, int y, int z) {
        BlockData b = at(x, z).getBlockData(x & 15, y, z & 15);
        return ALWAYS_IN_WATER.contains(b.getMaterial()) || b instanceof Waterlogged w && w.isWaterlogged();
    }

    @Override public int minY() {
        return minY;
    }

    @Override public int maxY() {
        return maxY;
    }
}
