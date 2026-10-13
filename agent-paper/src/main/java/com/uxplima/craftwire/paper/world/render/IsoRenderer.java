package com.uxplima.craftwire.paper.world.render;

import com.uxplima.craftwire.paper.Args;
import com.uxplima.craftwire.paper.world.Box;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Isometric views without a client: each visible block is three shaded faces, drawn back to front. The viewer
 * stands at `corner`; `a` grows toward the viewer on the right, `b` toward the viewer on the left.
 */
public final class IsoRenderer {
    static final int MAX_FOOTPRINT = 256;
    static final long MAX_VOLUME = 8_000_000L;

    private IsoRenderer() {}

    private record Axes(boolean aIsX, boolean aFlip, boolean bFlip) {}

    private static Axes axes(String corner) {
        return switch (corner) {
            case "se" -> new Axes(true, false, false);   // a = x, b = z
            case "sw" -> new Axes(false, false, true);   // a = z, b = -x
            case "nw" -> new Axes(true, true, true);     // a = -x, b = -z
            case "ne" -> new Axes(false, true, false);   // a = -z, b = x
            default -> throw Args.invalid("corner must be ne, nw, se or sw");
        };
    }

    public static BufferedImage sheet(BlockSource src, Box box, int size) {
        int half = size / 2;
        BufferedImage out = new BufferedImage(half * 2, half * 2, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        String[] corners = {"nw", "ne", "sw", "se"};
        for (int i = 0; i < 4; i++) g.drawImage(render(src, box, corners[i], half), (i % 2) * half, (i / 2) * half, null);
        g.dispose();
        return out;
    }

    public static BufferedImage render(BlockSource src, Box box, String corner, int size) {
        if (box.sizeX() > MAX_FOOTPRINT || box.sizeZ() > MAX_FOOTPRINT) throw Args.invalid("iso views draw at most " + MAX_FOOTPRINT + "x" + MAX_FOOTPRINT + " blocks");
        if (box.volume() > MAX_VOLUME) throw Args.invalid("iso views draw at most " + MAX_VOLUME + " blocks");
        Axes ax = axes(corner);
        int na = ax.aIsX() ? box.sizeX() : box.sizeZ(), nb = ax.aIsX() ? box.sizeZ() : box.sizeX(), ny = box.sizeY();
        double s = Math.max(1.0, Math.min((size * 0.92) / (na + nb), (size * 0.92) / ((na + nb) / 2.0 + ny + 1)));
        double offX = size / 2.0 - (na - nb) * s / 2.0;
        double offY = (size - ((na + nb) / 2.0 + ny + 1) * s) / 2.0 + ny * s;
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setColor(new java.awt.Color(Shading.SKY));
        g.fillRect(0, 0, size, size);
        for (int sum = 0; sum <= na + nb - 2; sum++) {
            for (int a = Math.max(0, sum - nb + 1); a <= Math.min(na - 1, sum); a++) {
                int b = sum - a;
                int x = worldX(box, ax, a, b), z = worldZ(box, ax, a, b);
                if (!src.loaded(x, z)) continue;
                for (int dy = 0; dy < ny; dy++) {
                    int y = box.minY() + dy;
                    if (src.color(x, y, z) == 0 && src.topColor(x, y, z) == 0) continue;
                    double sx = offX + (a - b) * s, sy = offY + (a + b) * s / 2.0 - dy * s;
                    if (empty(src, box, ax, a, b, dy + 1)) face(g, shade(src.topColor(x, y, z), occlusion(src, box, ax, a, b, dy + 1)), sx, sy, s, 0);
                    if (empty(src, box, ax, a, b + 1, dy)) face(g, Shading.shade(src.sideColor(x, y, z), 204), sx, sy, s, 1);
                    if (empty(src, box, ax, a + 1, b, dy)) face(g, Shading.shade(src.sideColor(x, y, z), 166), sx, sy, s, 2);
                }
            }
        }
        g.dispose();
        PixelFont.draw(img, corner.toUpperCase(), 4, 4, Math.max(1, size / 256), Shading.LABEL);
        return img;
    }

    private static int worldX(Box box, Axes ax, int a, int b) {
        return ax.aIsX() ? (ax.aFlip() ? box.maxX() - a : box.minX() + a) : (ax.bFlip() ? box.maxX() - b : box.minX() + b);
    }

    private static int worldZ(Box box, Axes ax, int a, int b) {
        return ax.aIsX() ? (ax.bFlip() ? box.maxZ() - b : box.minZ() + b) : (ax.aFlip() ? box.maxZ() - a : box.minZ() + a);
    }

    private static boolean empty(BlockSource src, Box box, Axes ax, int a, int b, int dy) {
        int na = ax.aIsX() ? box.sizeX() : box.sizeZ(), nb = ax.aIsX() ? box.sizeZ() : box.sizeX();
        if (a < 0 || b < 0 || a >= na || b >= nb || dy >= box.sizeY()) return true;
        int x = worldX(box, ax, a, b), z = worldZ(box, ax, a, b);
        return !src.loaded(x, z) || src.color(x, box.minY() + dy, z) == 0 && src.topColor(x, box.minY() + dy, z) == 0;
    }

    /** How many of the four blocks around the one above are filled: darkens the top face a little each. */
    private static int occlusion(BlockSource src, Box box, Axes ax, int a, int b, int dy) {
        int n = 0;
        if (!empty(src, box, ax, a + 1, b, dy)) n++;
        if (!empty(src, box, ax, a - 1, b, dy)) n++;
        if (!empty(src, box, ax, a, b + 1, dy)) n++;
        if (!empty(src, box, ax, a, b - 1, dy)) n++;
        return n;
    }

    private static int shade(int rgb, int occluders) {
        return Shading.shade(rgb, 255 - 18 * occluders);
    }

    /** kind 0 = top rhombus, 1 = left side, 2 = right side; (sx, sy) is the top vertex of the top face. */
    private static void face(Graphics2D g, int rgb, double sx, double sy, double s, int kind) {
        double[][] p = switch (kind) {
            case 0 -> new double[][] {{sx, sy}, {sx + s, sy + s / 2}, {sx, sy + s}, {sx - s, sy + s / 2}};
            case 1 -> new double[][] {{sx - s, sy + s / 2}, {sx, sy + s}, {sx, sy + 2 * s}, {sx - s, sy + 1.5 * s}};
            default -> new double[][] {{sx, sy + s}, {sx + s, sy + s / 2}, {sx + s, sy + 1.5 * s}, {sx, sy + 2 * s}};
        };
        Polygon poly = new Polygon();
        for (double[] v : p) poly.addPoint((int) Math.round(v[0]), (int) Math.round(v[1]));
        g.setColor(new java.awt.Color(rgb));
        g.fillPolygon(poly);
        g.drawPolygon(poly);   // closes hairline gaps between neighbours
    }
}
