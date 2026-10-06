package com.uxplima.craftwire.paper.world.render;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws a {@link BlockSource} as an image: one square of {@code scale} pixels per block, an optional grid every
 * {@code grid} blocks with coordinate labels in margins, and markers (players). Pure: no server access.
 */
public final class Renderer {
    private static final int FONT = 2;

    private final BlockSource src;

    public Renderer(BlockSource src) {
        this.src = src;
    }

    /** The horizontal and vertical axes of a view, as world coordinates per cell. */
    private interface Axes {
        int columns();
        int rows();
        /** The world coordinate the grid and labels use for a column / row. */
        int columnCoord(int u);
        int rowCoord(int v);
        /** Cell position (in blocks, fractional) of a marker, or null when it is outside. */
        double[] place(Marker m);
    }

    public Render render(View view, Region region, int scale, int grid, List<Marker> markers) {
        Axes axes = axes(view, region);
        int[] cells = new int[axes.columns() * axes.rows()];
        int unloaded = switch (view) {
            case View.Top t -> top(region, cells);
            case View.Slice s -> slice(region, s.y(), cells);
            case View.Side s -> side(region, s, cells);
        };

        List<int[]> columnLines = new ArrayList<>();   // [cell index, coordinate]
        List<int[]> rowLines = new ArrayList<>();
        if (grid > 0) {
            for (int u = 0; u < axes.columns(); u++) if (Math.floorMod(axes.columnCoord(u), grid) == 0) columnLines.add(new int[] {u, axes.columnCoord(u)});
            for (int v = 0; v < axes.rows(); v++) if (Math.floorMod(axes.rowCoord(v), grid) == 0) rowLines.add(new int[] {v, axes.rowCoord(v)});
        }
        int left = 0, topMargin = 0;
        if (grid > 0) {
            int widest = 0;
            for (int[] l : rowLines) widest = Math.max(widest, PixelFont.width(Integer.toString(l[1]), FONT));
            left = widest + 6;
            topMargin = PixelFont.HEIGHT * FONT + 6;
        }

        int w = axes.columns() * scale, h = axes.rows() * scale;
        BufferedImage img = new BufferedImage(left + w, topMargin + h, BufferedImage.TYPE_INT_RGB);
        PixelFont.fill(img, 0, 0, img.getWidth(), img.getHeight(), Shading.MARGIN);
        for (int v = 0; v < axes.rows(); v++) {
            for (int u = 0; u < axes.columns(); u++) PixelFont.fill(img, left + u * scale, topMargin + v * scale, scale, scale, cells[v * axes.columns() + u]);
        }

        int lastLabelEnd = Integer.MIN_VALUE;
        for (int[] l : columnLines) {
            int px = left + l[0] * scale;
            PixelFont.fill(img, px, topMargin, 1, h, Shading.GRID);
            String text = Integer.toString(l[1]);
            int tx = Math.max(left, Math.min(px - PixelFont.width(text, FONT) / 2, img.getWidth() - PixelFont.width(text, FONT)));
            if (tx > lastLabelEnd + FONT * 2) {
                PixelFont.draw(img, text, tx, 2, FONT, Shading.LABEL);
                lastLabelEnd = tx + PixelFont.width(text, FONT);
            }
        }
        lastLabelEnd = Integer.MIN_VALUE;
        for (int[] l : rowLines) {
            int py = topMargin + l[0] * scale;
            PixelFont.fill(img, left, py, w, 1, Shading.GRID);
            String text = Integer.toString(l[1]);
            int ty = Math.max(topMargin, Math.min(py - PixelFont.HEIGHT * FONT / 2, img.getHeight() - PixelFont.HEIGHT * FONT));
            if (ty > lastLabelEnd + FONT) {
                PixelFont.draw(img, text, left - 3 - PixelFont.width(text, FONT), ty, FONT, Shading.LABEL);
                lastLabelEnd = ty + PixelFont.HEIGHT * FONT;
            }
        }

        int r = Math.max(2, scale / 2 + 1);
        for (Marker m : markers) {
            double[] at = axes.place(m);
            if (at == null) continue;
            int cx = left + (int) Math.floor(at[0] * scale), cy = topMargin + (int) Math.floor(at[1] * scale);
            PixelFont.fill(img, cx - r - 1, cy - r - 1, 2 * r + 3, 2 * r + 3, Shading.MARKER_EDGE);
            PixelFont.fill(img, cx - r, cy - r, 2 * r + 1, 2 * r + 1, Shading.MARKER);
            if (!m.label().isEmpty()) PixelFont.drawOutlined(img, m.label(), cx + r + 3, cy - PixelFont.HEIGHT, FONT, Shading.LABEL, Shading.MARGIN);
        }
        return new Render(img, left, topMargin, unloaded);
    }

    private Axes axes(View view, Region rg) {
        if (view instanceof View.Side s) {
            int y1 = Math.min(s.y1(), s.y2()), y2 = Math.max(s.y1(), s.y2());
            boolean alongX = s.facing() == View.Facing.NORTH || s.facing() == View.Facing.SOUTH;
            int span = alongX ? rg.width() : rg.depth();
            return new Axes() {
                public int columns() { return span; }
                public int rows() { return y2 - y1 + 1; }
                public int columnCoord(int u) {
                    return switch (s.facing()) {
                        case NORTH -> rg.x1() + u;
                        case SOUTH -> rg.x2() - u;
                        case EAST -> rg.z1() + u;
                        case WEST -> rg.z2() - u;
                    };
                }
                public int rowCoord(int v) { return y2 - v; }
                public double[] place(Marker m) {
                    double u = switch (s.facing()) {
                        case NORTH -> m.x() - rg.x1();
                        case SOUTH -> rg.x2() + 1 - m.x();
                        case EAST -> m.z() - rg.z1();
                        case WEST -> rg.z2() + 1 - m.z();
                    };
                    double v = y2 + 1 - (m.y() + 0.9);   // a player's middle, not their feet
                    return u < 0 || u > span || v < 0 || v > rows() ? null : new double[] {u, v};
                }
            };
        }
        return new Axes() {
            public int columns() { return rg.width(); }
            public int rows() { return rg.depth(); }
            public int columnCoord(int u) { return rg.x1() + u; }
            public int rowCoord(int v) { return rg.z1() + v; }
            public double[] place(Marker m) {
                double u = m.x() - rg.x1(), v = m.z() - rg.z1();
                return u < 0 || u > rg.width() || v < 0 || v > rg.depth() ? null : new double[] {u, v};
            }
        };
    }

    /** The highest block a map draws in the column, or below minY when there is none. */
    private int surface(int x, int z) {
        int y = Math.min(src.top(x, z), src.maxY() - 1);
        while (y >= src.minY() && src.color(x, y, z) == 0) y--;
        return y;
    }

    private int top(Region rg, int[] cells) {
        int unloaded = 0;
        for (int v = 0; v < rg.depth(); v++) {
            for (int u = 0; u < rg.width(); u++) {
                int x = rg.x1() + u, z = rg.z1() + v;
                int i = v * rg.width() + u;
                if (!src.loaded(x, z)) {
                    cells[i] = Shading.unloaded(x, z);
                    unloaded++;
                    continue;
                }
                int y = surface(x, z);
                if (y < src.minY()) {
                    cells[i] = Shading.AIR;
                    continue;
                }
                int color = src.color(x, y, z);
                if (src.water(x, y, z)) {
                    int depth = 0;
                    while (y - depth >= src.minY() && src.water(x, y - depth, z)) depth++;
                    cells[i] = Shading.shade(color, Shading.waterLevel(depth, x, z));
                    continue;
                }
                int north = src.loaded(x, z - 1) ? surface(x, z - 1) : y;
                cells[i] = Shading.shade(color, y > north ? Shading.HIGH : y < north ? Shading.LOW : Shading.NORMAL);
            }
        }
        return unloaded;
    }

    private int slice(Region rg, int y, int[] cells) {
        int unloaded = 0;
        for (int v = 0; v < rg.depth(); v++) {
            for (int u = 0; u < rg.width(); u++) {
                int x = rg.x1() + u, z = rg.z1() + v;
                int i = v * rg.width() + u;
                if (!src.loaded(x, z)) {
                    cells[i] = Shading.unloaded(x, z);
                    unloaded++;
                    continue;
                }
                int c = y < src.minY() || y >= src.maxY() ? 0 : src.color(x, y, z);
                cells[i] = c == 0 ? Shading.AIR : c;
            }
        }
        return unloaded;
    }

    private int side(Region rg, View.Side s, int[] cells) {
        int y1 = Math.min(s.y1(), s.y2()), y2 = Math.max(s.y1(), s.y2());
        boolean alongX = s.facing() == View.Facing.NORTH || s.facing() == View.Facing.SOUTH;
        int span = alongX ? rg.width() : rg.depth();
        int range = alongX ? rg.depth() : rg.width();
        int unloaded = 0;
        for (int u = 0; u < span; u++) {
            // The columns behind this pixel column, nearest first.
            int[][] line = new int[range][];
            boolean anyLoaded = false;
            for (int d = 0; d < range; d++) {
                int x, z;
                switch (s.facing()) {
                    case NORTH -> { x = rg.x1() + u; z = rg.z2() - d; }
                    case SOUTH -> { x = rg.x2() - u; z = rg.z1() + d; }
                    case EAST -> { z = rg.z1() + u; x = rg.x1() + d; }
                    default -> { z = rg.z2() - u; x = rg.x2() - d; }
                }
                line[d] = new int[] {x, z};
                if (src.loaded(x, z)) anyLoaded = true;
                else unloaded++;
            }
            for (int v = 0; v <= y2 - y1; v++) {
                int y = y2 - v;
                int color = anyLoaded ? Shading.SKY : Shading.unloaded(u, v);
                if (anyLoaded && y >= src.minY() && y < src.maxY()) {
                    for (int d = 0; d < range; d++) {
                        int x = line[d][0], z = line[d][1];
                        if (!src.loaded(x, z) || y > src.top(x, z)) continue;
                        int c = src.color(x, y, z);
                        if (c != 0) {
                            color = Shading.depth(c, d, range);
                            break;
                        }
                    }
                }
                cells[v * span + u] = color;
            }
        }
        return unloaded;
    }
}
