package com.uxplima.craftwire.build;

/** Shapes in the caller's frame. Boxes are inclusive and take their corners in any order. */
public final class Shapes {
    private Shapes() {}

    public static void fill(Placer p, int x1, int y1, int z1, int x2, int y2, int z2, BlockPick b) {
        for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
                for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) p.place(x, y, z, b);
    }

    /** Walls, floor and ceiling of b; everything inside becomes air (a room). */
    public static void hollow(Placer p, int x1, int y1, int z1, int x2, int y2, int z2, BlockPick b, int air, boolean floor, boolean ceiling) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), ay = Math.min(y1, y2), by = Math.max(y1, y2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        BlockPick empty = Picks.of(air);
        for (int y = ay; y <= by; y++)
            for (int z = az; z <= bz; z++)
                for (int x = ax; x <= bx; x++) {
                    boolean side = x == ax || x == bx || z == az || z == bz;
                    boolean shell = side || (y == ay && floor) || (y == by && ceiling);
                    p.place(x, y, z, shell ? b : empty);
                }
    }

    public static void walls(Placer p, int x1, int y1, int z1, int x2, int y2, int z2, BlockPick b) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
            for (int z = az; z <= bz; z++)
                for (int x = ax; x <= bx; x++) if (x == ax || x == bx || z == az || z == bz) p.place(x, y, z, b);
    }

    public static void outline(Placer p, int x1, int y1, int z1, int x2, int y2, int z2, BlockPick b) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), ay = Math.min(y1, y2), by = Math.max(y1, y2), az = Math.min(z1, z2), bz = Math.max(z1, z2);
        for (int y = ay; y <= by; y++)
            for (int z = az; z <= bz; z++)
                for (int x = ax; x <= bx; x++) {
                    int planes = (x == ax || x == bx ? 1 : 0) + (y == ay || y == by ? 1 : 0) + (z == az || z == bz ? 1 : 0);
                    if (planes >= 2) p.place(x, y, z, b);
                }
    }

    /** half: 0 whole, 1 top half (y >= cy), -1 bottom half. */
    public static void sphere(Placer p, int cx, int cy, int cz, int r, BlockPick b, boolean hollow, int half) {
        ellipsoidImpl(p, cx, cy, cz, r, r, r, b, hollow, half);
    }

    public static void ellipsoid(Placer p, int cx, int cy, int cz, int rx, int ry, int rz, BlockPick b, boolean hollow) {
        ellipsoidImpl(p, cx, cy, cz, rx, ry, rz, b, hollow, 0);
    }

    private static boolean inEllipsoid(int dx, int dy, int dz, double rx, double ry, double rz) {
        double a = dx / rx, c = dy / ry, e = dz / rz;
        return a * a + c * c + e * e < 1.0;
    }

    private static void ellipsoidImpl(Placer p, int cx, int cy, int cz, int rx, int ry, int rz, BlockPick b, boolean hollow, int half) {
        double ex = rx + 0.5, ey = ry + 0.5, ez = rz + 0.5;
        for (int dy = -ry; dy <= ry; dy++) {
            if (half > 0 && dy < 0 || half < 0 && dy > 0) continue;
            for (int dz = -rz; dz <= rz; dz++)
                for (int dx = -rx; dx <= rx; dx++) {
                    if (!inEllipsoid(dx, dy, dz, ex, ey, ez)) continue;
                    if (hollow && inEllipsoid(dx + 1, dy, dz, ex, ey, ez) && inEllipsoid(dx - 1, dy, dz, ex, ey, ez)
                            && inEllipsoid(dx, dy + 1, dz, ex, ey, ez) && inEllipsoid(dx, dy - 1, dz, ex, ey, ez)
                            && inEllipsoid(dx, dy, dz + 1, ex, ey, ez) && inEllipsoid(dx, dy, dz - 1, ex, ey, ez)) continue;
                    p.place(cx + dx, cy + dy, cz + dz, b);
                }
        }
    }

    private static boolean inDisk(int a, int c, double r) {
        return a * a + c * c < (r + 0.5) * (r + 0.5);
    }

    /** A cylinder of h blocks from (cx, y, cz) along axis 'y' (up), 'x' (east) or 'z' (south). */
    public static void cylinder(Placer p, int cx, int y, int cz, int r, int h, BlockPick b, boolean hollow, char axis) {
        for (int i = 0; i < h; i++)
            for (int c = -r; c <= r; c++)
                for (int a = -r; a <= r; a++) {
                    if (!inDisk(a, c, r)) continue;
                    if (hollow && inDisk(a + 1, c, r) && inDisk(a - 1, c, r) && inDisk(a, c + 1, r) && inDisk(a, c - 1, r)) continue;
                    switch (axis) {
                        case 'x' -> p.place(cx + i, y + a, cz + c, b);
                        case 'z' -> p.place(cx + a, y + c, cz + i, b);
                        default -> p.place(cx + a, y + i, cz + c, b);
                    }
                }
    }

    public static void cone(Placer p, int cx, int y, int cz, int r, int h, BlockPick b, boolean hollow) {
        for (int i = 0; i < h; i++) {
            double ri = r * (1.0 - (double) i / h);
            int ir = (int) Math.ceil(ri);
            for (int c = -ir; c <= ir; c++)
                for (int a = -ir; a <= ir; a++) {
                    if (!inDisk(a, c, ri)) continue;
                    if (hollow && i < h - 1 && inDisk(a + 1, c, ri) && inDisk(a - 1, c, ri) && inDisk(a, c + 1, ri) && inDisk(a, c - 1, ri)) continue;
                    p.place(cx + a, y + i, cz + c, b);
                }
        }
    }

    /** A straight line; width w makes it a w x w x w brush. */
    public static void line(Placer p, int x1, int y1, int z1, int x2, int y2, int z2, BlockPick b, int width) {
        int n = Math.max(Math.abs(x2 - x1), Math.max(Math.abs(y2 - y1), Math.abs(z2 - z1)));
        int lo = -(width - 1) / 2, hi = width / 2;
        for (int i = 0; i <= n; i++) {
            double t = n == 0 ? 0 : (double) i / n;
            int x = (int) Math.round(x1 + (x2 - x1) * t), y = (int) Math.round(y1 + (y2 - y1) * t), z = (int) Math.round(z1 + (z2 - z1) * t);
            for (int dy = lo; dy <= hi; dy++)
                for (int dz = lo; dz <= hi; dz++)
                    for (int dx = lo; dx <= hi; dx++) p.place(x + dx, y + dy, z + dz, b);
        }
    }

    /** A half-ellipse arch from (x1, z1) to (x2, z2) on one axis, rising `height` above y, `thickness` blocks deep downward. */
    public static void arch(Placer p, int x1, int y, int z1, int x2, int z2, int height, BlockPick b, int thickness) {
        if (x1 != x2 && z1 != z2) throw new IllegalArgumentException("An arch runs along x or along z: give equal x or equal z");
        boolean alongX = z1 == z2;
        int a0 = alongX ? Math.min(x1, x2) : Math.min(z1, z2), a1 = alongX ? Math.max(x1, x2) : Math.max(z1, z2);
        double mid = (a0 + a1) / 2.0, half = (a1 - a0) / 2.0 + 0.5;
        for (int a = a0; a <= a1; a++) {
            double u = (a - mid) / half;
            int top = y + (int) Math.round(height * Math.sqrt(Math.max(0, 1 - u * u)));
            for (int yy = Math.max(y, top - thickness + 1); yy <= top; yy++) {
                if (alongX) p.place(a, yy, z1, b);
                else p.place(x1, yy, a, b);
            }
        }
    }

    /** A prism over the polygon (xs[i], zs[i]) from y, h blocks high; hollow keeps only the edges. */
    public static void polygon(Placer p, int[] xs, int[] zs, int y, int h, BlockPick b, boolean hollow) {
        if (xs.length < 3 || xs.length != zs.length) throw new IllegalArgumentException("A polygon needs at least 3 points [x, z]");
        for (int level = y; level < y + h; level++) {
            if (hollow) {
                for (int i = 0; i < xs.length; i++) {
                    int j = (i + 1) % xs.length;
                    line(p, xs[i], level, zs[i], xs[j], level, zs[j], b, 1);
                }
                continue;
            }
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (int i = 0; i < xs.length; i++) {
                minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]); minZ = Math.min(minZ, zs[i]); maxZ = Math.max(maxZ, zs[i]);
            }
            for (int z = minZ; z <= maxZ; z++)
                for (int x = minX; x <= maxX; x++) if (inside(xs, zs, x + 0.5, z + 0.5)) p.place(x, level, z, b);
        }
    }

    private static boolean inside(int[] xs, int[] zs, double px, double pz) {
        boolean in = false;
        for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
            if ((zs[i] > pz) != (zs[j] > pz) && px < (double) (xs[j] - xs[i]) * (pz - zs[i]) / (zs[j] - zs[i]) + xs[i]) in = !in;
        }
        return in;
    }
}
