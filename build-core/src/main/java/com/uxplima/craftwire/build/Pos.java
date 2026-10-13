package com.uxplima.craftwire.build;

/** A block position packed in a long, in the same layout as Minecraft's BlockPos.asLong (x 26 bits, z 26, y 12). */
public final class Pos {
    /** x and z stop one short of -2^25, so no position packs to Long.MIN_VALUE (the empty key of LongIntMap). */
    public static final int MIN_XZ = -(1 << 25) + 1;
    public static final int MAX_XZ = (1 << 25) - 1;
    public static final int MIN_Y = -2048;
    public static final int MAX_Y = 2047;

    private Pos() {}

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    public static int x(long p) {
        return (int) (p >> 38);
    }

    public static int y(long p) {
        return (int) (p << 52 >> 52);
    }

    public static int z(long p) {
        return (int) (p << 26 >> 38);
    }

    public static boolean inRange(int x, int y, int z) {
        return x >= MIN_XZ && x <= MAX_XZ && z >= MIN_XZ && z <= MAX_XZ && y >= MIN_Y && y <= MAX_Y;
    }
}
