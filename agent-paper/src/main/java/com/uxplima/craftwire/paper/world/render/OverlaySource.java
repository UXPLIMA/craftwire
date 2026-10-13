package com.uxplima.craftwire.paper.world.render;

import com.uxplima.craftwire.build.LongIntMap;
import com.uxplima.craftwire.build.Pos;
import com.uxplima.craftwire.mc.BuildStates;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.state.BlockState;

/** The world with a dry run's blocks on top, so a build can be previewed before anything is written. */
final class OverlaySource implements BlockSource {
    private final BlockSource world;
    private final LongIntMap overlay;
    private final Map<Long, Integer> tops = new HashMap<>();
    private final Map<Integer, int[]> colors = new ConcurrentHashMap<>();

    OverlaySource(BlockSource world, LongIntMap overlay) {
        this.world = world;
        this.overlay = overlay;
        overlay.forEach((pos, s) -> tops.merge(column(Pos.x(pos), Pos.z(pos)), Pos.y(pos) + 1, Math::max));
    }

    private static long column(int x, int z) {
        return (long) x << 32 | (z & 0xFFFFFFFFL);
    }

    /** {map colour, top, side} of a build state; 0 for air. */
    private int[] colorsOf(int id) {
        return colors.computeIfAbsent(id, k -> {
            BlockState s = BuildStates.INSTANCE.state(k);
            if (s.isAir()) return new int[] {0, 0, 0};
            String name = BuildStates.INSTANCE.name(k);
            String block = name.contains("[") ? name.substring(0, name.indexOf('[')) : name;
            int map = s.getMapColor(EmptyBlockGetter.INSTANCE, BlockPos.ZERO).col;
            int top = BlockColors.top(block), side = BlockColors.side(block);
            return new int[] {map, top >= 0 ? top : map, side >= 0 ? side : map};
        });
    }

    private int at(int x, int y, int z) {
        return Pos.inRange(x, y, z) ? overlay.get(Pos.pack(x, y, z)) : LongIntMap.NONE;
    }

    @Override public boolean loaded(int x, int z) {
        return world.loaded(x, z) || tops.containsKey(column(x, z));
    }

    @Override public int top(int x, int z) {
        int own = tops.getOrDefault(column(x, z), Integer.MIN_VALUE);
        return world.loaded(x, z) ? Math.max(world.top(x, z), own) : own;
    }

    @Override public int color(int x, int y, int z) {
        int s = at(x, y, z);
        return s == LongIntMap.NONE ? world.color(x, y, z) : colorsOf(s)[0];
    }

    @Override public int topColor(int x, int y, int z) {
        int s = at(x, y, z);
        return s == LongIntMap.NONE ? world.topColor(x, y, z) : colorsOf(s)[1];
    }

    @Override public int sideColor(int x, int y, int z) {
        int s = at(x, y, z);
        return s == LongIntMap.NONE ? world.sideColor(x, y, z) : colorsOf(s)[2];
    }

    @Override public boolean water(int x, int y, int z) {
        return at(x, y, z) == LongIntMap.NONE && world.water(x, y, z);
    }

    @Override public int minY() {
        return world.minY();
    }

    @Override public int maxY() {
        return world.maxY();
    }
}
