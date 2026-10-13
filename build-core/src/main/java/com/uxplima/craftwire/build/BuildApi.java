package com.uxplima.craftwire.build;

import java.util.HashMap;
import java.util.Map;
import java.util.SplittableRandom;

/** The host object scripts reach as __cw; build-api.js wraps it in the documented global functions. */
public final class BuildApi {
    private final BuildContext c;
    private final SplittableRandom random;

    public BuildApi(BuildContext c) {
        this.c = c;
        this.random = new SplittableRandom(c.seed());
    }

    public BlockPick block(String spec) { return Picks.of(c.state(spec)); }
    public int state(String spec) { return c.state(spec); }

    public BlockPick mix(String[] specs, int[] weights) {
        int[] ids = new int[specs.length];
        for (int i = 0; i < specs.length; i++) ids[i] = c.state(specs[i]);
        return Picks.mix(ids, weights, c.seed());
    }

    public BlockPick gradient(String axis, int from, int to, String[] specs) {
        int[] ids = new int[specs.length];
        for (int i = 0; i < specs.length; i++) ids[i] = c.state(specs[i]);
        return Picks.gradient(axis.isEmpty() ? 'y' : axis.charAt(0), from, to, ids, c.seed());
    }

    public BlockPick custom(BlockPick f) { return f; }

    public void set(int x, int y, int z, BlockPick b) { c.place(x, y, z, b); }
    public void fill(int x1, int y1, int z1, int x2, int y2, int z2, BlockPick b) { Shapes.fill(c, x1, y1, z1, x2, y2, z2, b); }
    public void hollow(int x1, int y1, int z1, int x2, int y2, int z2, BlockPick b, boolean floor, boolean ceiling) {
        Shapes.hollow(c, x1, y1, z1, x2, y2, z2, b, c.palette().air(), floor, ceiling);
    }
    public void walls(int x1, int y1, int z1, int x2, int y2, int z2, BlockPick b) { Shapes.walls(c, x1, y1, z1, x2, y2, z2, b); }
    public void outline(int x1, int y1, int z1, int x2, int y2, int z2, BlockPick b) { Shapes.outline(c, x1, y1, z1, x2, y2, z2, b); }
    public void clear(int x1, int y1, int z1, int x2, int y2, int z2) { Shapes.fill(c, x1, y1, z1, x2, y2, z2, Picks.of(c.palette().air())); }

    public void replace(int x1, int y1, int z1, int x2, int y2, int z2, String from, BlockPick to) {
        BlockMatch match = new BlockMatch(from);
        Map<Integer, Boolean> memo = new HashMap<>();
        for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
            for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++)
                for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                    int s = c.read(x, y, z);
                    if (memo.computeIfAbsent(s, k -> match.test(c.palette().name(k)))) c.place(x, y, z, to);
                }
    }

    public void sphere(int cx, int cy, int cz, int r, BlockPick b, boolean hollow, int half) { Shapes.sphere(c, cx, cy, cz, r, b, hollow, half); }
    public void ellipsoid(int cx, int cy, int cz, int rx, int ry, int rz, BlockPick b, boolean hollow) { Shapes.ellipsoid(c, cx, cy, cz, rx, ry, rz, b, hollow); }
    public void cylinder(int cx, int y, int cz, int r, int h, BlockPick b, boolean hollow, String axis) { Shapes.cylinder(c, cx, y, cz, r, h, b, hollow, axis.isEmpty() ? 'y' : axis.charAt(0)); }
    public void cone(int cx, int y, int cz, int r, int h, BlockPick b, boolean hollow) { Shapes.cone(c, cx, y, cz, r, h, b, hollow); }
    public void line(int x1, int y1, int z1, int x2, int y2, int z2, BlockPick b, int width) { Shapes.line(c, x1, y1, z1, x2, y2, z2, b, width); }
    public void arch(int x1, int y, int z1, int x2, int z2, int height, BlockPick b, int thickness) { Shapes.arch(c, x1, y, z1, x2, z2, height, b, thickness); }
    public void polygon(int[] xs, int[] zs, int y, int h, BlockPick b, boolean hollow) { Shapes.polygon(c, xs, zs, y, h, b, hollow); }

    public void roof(int x1, int z1, int x2, int z2, int y, String type, String axis, int overhang, String stairs, String slab, BlockPick fill, String facing) {
        Roofs.roof(c, c.palette(), new Roofs.Spec(x1, z1, x2, z2, y, type, axis, overhang, stairs, slab, fill, facing));
    }

    public void stairs(int x, int y, int z, String dir, int length, String stairs, int width) { Roofs.stairs(c, c.palette(), x, y, z, dir, length, stairs, width); }

    public void layers(int x, int y, int z, String[] letters, BlockPick[] picks, String[] layers) {
        Map<Character, BlockPick> legend = new HashMap<>();
        for (int i = 0; i < letters.length; i++) {
            if (letters[i].length() != 1 || ".~?/".contains(letters[i])) throw new IllegalArgumentException("Legend keys are single letters other than . ~ ? /, not '" + letters[i] + "'");
            legend.put(letters[i].charAt(0), picks[i]);
        }
        Layers.place(c, x, y, z, legend, layers, c.palette().air());
    }

    public void push(int dx, int dy, int dz, int quarterTurns, String mirror) { c.push(Transform.of(dx, dy, dz, quarterTurns, mirror)); }
    public void pop() { c.pop(); }

    public String get(int x, int y, int z) { return c.palette().name(c.read(x, y, z)); }
    public int ground(int x, int z) { return c.ground(x, z); }
    public boolean isAir(int x, int y, int z) { return c.palette().isAir(c.read(x, y, z)); }
    public double rand() { return random.nextDouble(); }
    public void print(String s) { c.print(s); }
}
