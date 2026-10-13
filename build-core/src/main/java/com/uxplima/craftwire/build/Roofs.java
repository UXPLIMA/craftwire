package com.uxplima.craftwire.build;

/**
 * Roofs from stairs and slabs. A stair's facing is its high side, so a slope rising toward the south uses facing=south.
 * Corners of hip roofs are straight stairs here; the server's connection pass turns them into outer corners.
 */
public final class Roofs {
    private Roofs() {}

    public record Spec(int x1, int z1, int x2, int z2, int y, String type, String axis, int overhang, String stairs, String slab,
                       BlockPick fill, String facing) {}

    public static void roof(Placer p, StatePalette pal, Spec s) {
        int o = Math.max(0, s.overhang());
        int bx1 = Math.min(s.x1(), s.x2()) - o, bx2 = Math.max(s.x1(), s.x2()) + o;
        int bz1 = Math.min(s.z1(), s.z2()) - o, bz2 = Math.max(s.z1(), s.z2()) + o;
        String slabId = s.slab().isEmpty() ? s.stairs().replace("_stairs", "_slab") : s.slab();
        BlockPick slab = Picks.of(pal.id(slabId + "[type=bottom]"));
        switch (s.type()) {
            case "gable" -> {
                String axis = s.axis().isEmpty() ? (bx2 - bx1 >= bz2 - bz1 ? "x" : "z") : s.axis();
                gable(p, pal, s, bx1, bz1, bx2, bz2, axis.equals("x"), slab);
            }
            case "hip" -> hip(p, pal, s, bx1, bz1, bx2, bz2, slab);
            case "shed" -> shed(p, pal, s, bx1, bz1, bx2, bz2);
            case "flat" -> Shapes.fill(p, bx1, s.y(), bz1, bx2, s.y(), bz2, slab);
            default -> throw new IllegalArgumentException("roof type must be gable, hip, shed or flat, not '" + s.type() + "'");
        }
    }

    private static BlockPick stair(StatePalette pal, String stairs, String facing) {
        return Picks.of(pal.id(stairs + "[facing=" + facing + ",half=bottom]"));
    }

    private static void gable(Placer p, StatePalette pal, Spec s, int bx1, int bz1, int bx2, int bz2, boolean ridgeAlongX, BlockPick slab) {
        // Work in (along, across): along follows the ridge, across rises from both sides toward it.
        int a1 = ridgeAlongX ? bx1 : bz1, a2 = ridgeAlongX ? bx2 : bz2, c1 = ridgeAlongX ? bz1 : bx1, c2 = ridgeAlongX ? bz2 : bx2;
        int end1 = ridgeAlongX ? Math.min(s.x1(), s.x2()) : Math.min(s.z1(), s.z2());
        int end2 = ridgeAlongX ? Math.max(s.x1(), s.x2()) : Math.max(s.z1(), s.z2());
        BlockPick low = stair(pal, s.stairs(), ridgeAlongX ? "south" : "east");
        BlockPick high = stair(pal, s.stairs(), ridgeAlongX ? "north" : "west");
        for (int i = 0; ; i++) {
            int n = c1 + i, f = c2 - i, yy = s.y() + i;
            if (n > f) break;
            for (int a = a1; a <= a2; a++) {
                if (n == f) put(p, ridgeAlongX, a, yy, n, slab);
                else {
                    put(p, ridgeAlongX, a, yy, n, low);
                    put(p, ridgeAlongX, a, yy, f, high);
                }
            }
            if (s.fill() != null) {
                for (int c = n + 1; c < f; c++) {
                    put(p, ridgeAlongX, end1, yy, c, s.fill());
                    put(p, ridgeAlongX, end2, yy, c, s.fill());
                }
            }
            if (n == f) break;
        }
    }

    private static void put(Placer p, boolean alongX, int along, int y, int across, BlockPick b) {
        if (alongX) p.place(along, y, across, b);
        else p.place(across, y, along, b);
    }

    private static void hip(Placer p, StatePalette pal, Spec s, int bx1, int bz1, int bx2, int bz2, BlockPick slab) {
        BlockPick north = stair(pal, s.stairs(), "south"), south = stair(pal, s.stairs(), "north");
        BlockPick west = stair(pal, s.stairs(), "east"), east = stair(pal, s.stairs(), "west");
        for (int i = 0; ; i++) {
            int x1 = bx1 + i, x2 = bx2 - i, z1 = bz1 + i, z2 = bz2 - i, yy = s.y() + i;
            if (x1 > x2 || z1 > z2) break;
            if (x1 == x2 || z1 == z2) {
                Shapes.fill(p, x1, yy, z1, x2, yy, z2, slab);
                break;
            }
            for (int x = x1; x <= x2; x++) {
                p.place(x, yy, z1, north);
                p.place(x, yy, z2, south);
            }
            for (int z = z1 + 1; z < z2; z++) {
                p.place(x1, yy, z, west);
                p.place(x2, yy, z, east);
            }
        }
    }

    private static void shed(Placer p, StatePalette pal, Spec s, int bx1, int bz1, int bx2, int bz2) {
        BlockPick b = stair(pal, s.stairs(), s.facing());
        boolean alongZ = s.facing().equals("north") || s.facing().equals("south");
        int lo = alongZ ? bz1 : bx1, hi = alongZ ? bz2 : bx2;
        boolean towardLow = s.facing().equals("north") || s.facing().equals("west");
        for (int i = 0; i <= hi - lo; i++) {
            int c = towardLow ? hi - i : lo + i, yy = s.y() + i;
            if (alongZ) for (int x = bx1; x <= bx2; x++) p.place(x, yy, c, b);
            else for (int z = bz1; z <= bz2; z++) p.place(c, yy, z, b);
            if (s.fill() != null) {
                for (int y = s.y(); y < yy; y++) {
                    if (alongZ) {
                        p.place(Math.min(s.x1(), s.x2()), y, c, s.fill());
                        p.place(Math.max(s.x1(), s.x2()), y, c, s.fill());
                    } else {
                        p.place(c, y, Math.min(s.z1(), s.z2()), s.fill());
                        p.place(c, y, Math.max(s.z1(), s.z2()), s.fill());
                    }
                }
            }
        }
    }

    /** A straight staircase climbing toward dir, `width` wide to the right of dir. */
    public static void stairs(Placer p, StatePalette pal, int x, int y, int z, String dir, int length, String stairs, int width) {
        int[] f = switch (dir) {
            case "north" -> new int[] {0, -1};
            case "south" -> new int[] {0, 1};
            case "east" -> new int[] {1, 0};
            case "west" -> new int[] {-1, 0};
            default -> throw new IllegalArgumentException("dir must be north, south, east or west, not '" + dir + "'");
        };
        int rx = -f[1], rz = f[0];   // to the right: north -> east, east -> south
        BlockPick b = stair(pal, stairs, dir);
        for (int i = 0; i < length; i++)
            for (int w = 0; w < width; w++) p.place(x + f[0] * i + rx * w, y + i, z + f[1] * i + rz * w, b);
    }
}
