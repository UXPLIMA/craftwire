package com.uxplima.craftwire.paper.world;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.paper.Args;
import java.util.ArrayList;
import java.util.List;

/** An inclusive block box. */
public record Box(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    public static Box of(int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Box(Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    /** Reads {min:{x,y,z}, max:{x,y,z}}; the corners may be given in any order. */
    public static Box from(JsonObject p) {
        JsonObject min = Args.object(p, "min");
        JsonObject max = Args.object(p, "max");
        return of(Args.integer(min, "x"), Args.integer(min, "y"), Args.integer(min, "z"),
                Args.integer(max, "x"), Args.integer(max, "y"), Args.integer(max, "z"));
    }

    public int sizeX() {
        return maxX - minX + 1;
    }

    public int sizeY() {
        return maxY - minY + 1;
    }

    public int sizeZ() {
        return maxZ - minZ + 1;
    }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    /** Chunk coordinates this box touches, as {x, z} pairs, x-major. */
    public List<int[]> chunks() {
        List<int[]> out = new ArrayList<>();
        for (int cx = Math.floorDiv(minX, 16); cx <= Math.floorDiv(maxX, 16); cx++) {
            for (int cz = Math.floorDiv(minZ, 16); cz <= Math.floorDiv(maxZ, 16); cz++) out.add(new int[] {cx, cz});
        }
        return out;
    }

    /** The part of this box inside chunk (cx, cz), or null when they do not overlap. */
    public Box clipToChunk(int cx, int cz) {
        int x0 = Math.max(minX, cx * 16), x1 = Math.min(maxX, cx * 16 + 15);
        int z0 = Math.max(minZ, cz * 16), z1 = Math.min(maxZ, cz * 16 + 15);
        return x0 > x1 || z0 > z1 ? null : new Box(x0, minY, z0, x1, maxY, z1);
    }

    /** This box limited to build height [worldMinY, worldMaxY], or null when nothing is left. */
    public Box clampY(int worldMinY, int worldMaxY) {
        int y0 = Math.max(minY, worldMinY), y1 = Math.min(maxY, worldMaxY);
        return y0 > y1 ? null : new Box(minX, y0, minZ, maxX, y1, maxZ);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.add("min", corner(minX, minY, minZ));
        o.add("max", corner(maxX, maxY, maxZ));
        return o;
    }

    private static JsonObject corner(int x, int y, int z) {
        JsonObject c = new JsonObject();
        c.addProperty("x", x);
        c.addProperty("y", y);
        c.addProperty("z", z);
        return c;
    }
}
