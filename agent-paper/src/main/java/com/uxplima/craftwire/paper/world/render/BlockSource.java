package com.uxplima.craftwire.paper.world.render;

/** The blocks a render reads, in world coordinates. Safe to read off the server thread. */
public interface BlockSource {
    /** Whether the column's chunk was available (loaded or generated). */
    boolean loaded(int x, int z);

    /** A y at or above the highest non-air block of the column. */
    int top(int x, int z);

    /** The block's map colour (0xRRGGBB), or 0 for blocks maps do not draw (air, glass…). */
    int color(int x, int y, int z);

    /** Whether the block holds water (water itself, or waterlogged). */
    boolean water(int x, int y, int z);

    int minY();

    int maxY();

    /** Colour of the block's top face; the map colour unless real texture colours are known. */
    default int topColor(int x, int y, int z) {
        return color(x, y, z);
    }

    /** Colour of the block's sides. */
    default int sideColor(int x, int y, int z) {
        return color(x, y, z);
    }
}
