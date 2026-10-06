package com.uxplima.craftwire.paper.world.render;

/** Colours of the renders: vanilla map brightness levels, depth fade, and the colours of everything that is not a block. */
public final class Shading {
    private Shading() {}

    /** Vanilla map brightness: a block lower than its northern neighbour, level, higher. */
    public static final int LOW = 180;
    public static final int NORMAL = 220;
    public static final int HIGH = 255;

    public static final int AIR = 0xE8ECF0;
    public static final int SKY = 0xBFD4EA;
    public static final int UNLOADED_A = 0x3A3F45;
    public static final int UNLOADED_B = 0x30343A;
    public static final int GRID = 0x101010;
    public static final int MARGIN = 0xFFFFFF;
    public static final int LABEL = 0x202020;
    public static final int MARKER = 0xE0201C;
    public static final int MARKER_EDGE = 0xFFFFFF;

    public static int shade(int rgb, int level) {
        int r = (rgb >> 16 & 0xFF) * level / 255;
        int g = (rgb >> 8 & 0xFF) * level / 255;
        int b = (rgb & 0xFF) * level / 255;
        return r << 16 | g << 8 | b;
    }

    /** Front views: the nearest blocks at full brightness, the farthest at half. */
    public static int depth(int rgb, int distance, int range) {
        int level = 255 - (range <= 1 ? 0 : 127 * distance / (range - 1));
        return shade(rgb, level);
    }

    /** Vanilla water: lighter where shallow, with a checker that hides the steps. */
    public static int waterLevel(int depth, int x, int z) {
        double f = depth * 0.1 + ((x + z) & 1) * 0.2;
        return f < 0.5 ? HIGH : f > 0.9 ? LOW : NORMAL;
    }

    public static int unloaded(int x, int z) {
        return ((x + z) & 1) == 0 ? UNLOADED_A : UNLOADED_B;
    }
}
