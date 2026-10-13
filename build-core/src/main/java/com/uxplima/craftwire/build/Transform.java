package com.uxplima.craftwire.build;

/**
 * A frame: world = offset + rotate(mirror(local)). rot is clockwise quarter turns seen from above
 * ((x, z) -> (-z, x) per turn); mirrorX negates x before turning. A Z mirror is mirrorX plus two turns.
 */
public record Transform(int ox, int oy, int oz, int rot, boolean mirrorX) {
    public static final Transform IDENTITY = new Transform(0, 0, 0, 0, false);

    /** A frame from script arguments; mirror is "", "x" or "z". */
    public static Transform of(int dx, int dy, int dz, int quarterTurns, String mirror) {
        int q = Math.floorMod(quarterTurns, 4);
        return switch (mirror) {
            case "" -> new Transform(dx, dy, dz, q, false);
            case "x" -> new Transform(dx, dy, dz, q, true);
            case "z" -> new Transform(dx, dy, dz, (q + 2) & 3, true);
            default -> throw new IllegalArgumentException("mirror must be 'x' or 'z', not '" + mirror + "'");
        };
    }

    public int worldX(int x, int z) {
        int mx = mirrorX ? -x : x;
        return ox + switch (rot) {
            case 0 -> mx;
            case 1 -> -z;
            case 2 -> -mx;
            default -> z;
        };
    }

    public int worldY(int y) {
        return oy + y;
    }

    public int worldZ(int x, int z) {
        int mx = mirrorX ? -x : x;
        return oz + switch (rot) {
            case 0 -> z;
            case 1 -> mx;
            case 2 -> -z;
            default -> -mx;
        };
    }

    /** The state as it looks in this frame: mirrored first, then turned. */
    public int state(int id, StatePalette p) {
        int s = mirrorX ? p.mirror(id, Axis.X) : id;
        return rot == 0 ? s : p.rotate(s, rot);
    }

    /** The frame of child placed inside this one. */
    public Transform then(Transform child) {
        int r = Math.floorMod(rot + (mirrorX ? -child.rot : child.rot), 4);
        return new Transform(worldX(child.ox, child.oz), worldY(child.oy), worldZ(child.ox, child.oz), r, mirrorX ^ child.mirrorX);
    }
}
