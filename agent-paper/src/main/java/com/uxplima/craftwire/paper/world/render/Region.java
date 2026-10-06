package com.uxplima.craftwire.paper.world.render;

/** A rectangle of columns, corners inclusive, in any order. */
public record Region(int x1, int z1, int x2, int z2) {
    public Region {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        x1 = ax;
        x2 = bx;
        z1 = az;
        z2 = bz;
    }

    public int width() {
        return x2 - x1 + 1;
    }

    public int depth() {
        return z2 - z1 + 1;
    }
}
