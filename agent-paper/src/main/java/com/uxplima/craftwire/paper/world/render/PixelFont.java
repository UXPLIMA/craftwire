package com.uxplima.craftwire.paper.world.render;

import java.awt.image.BufferedImage;
import java.util.Locale;
import java.util.Map;

/**
 * A 3×5 pixel font for coordinates and names, drawn pixel by pixel: servers often run without fonts installed, and
 * AWT text rendering needs them.
 */
final class PixelFont {
    private PixelFont() {}

    static final int WIDTH = 3;
    static final int HEIGHT = 5;

    private static final Map<Character, String[]> GLYPHS = Map.ofEntries(
            Map.entry('0', rows("###", "#.#", "#.#", "#.#", "###")),
            Map.entry('1', rows(".#.", "##.", ".#.", ".#.", "###")),
            Map.entry('2', rows("###", "..#", "###", "#..", "###")),
            Map.entry('3', rows("###", "..#", "###", "..#", "###")),
            Map.entry('4', rows("#.#", "#.#", "###", "..#", "..#")),
            Map.entry('5', rows("###", "#..", "###", "..#", "###")),
            Map.entry('6', rows("###", "#..", "###", "#.#", "###")),
            Map.entry('7', rows("###", "..#", "..#", ".#.", ".#.")),
            Map.entry('8', rows("###", "#.#", "###", "#.#", "###")),
            Map.entry('9', rows("###", "#.#", "###", "..#", "###")),
            Map.entry('-', rows("...", "...", "###", "...", "...")),
            Map.entry('_', rows("...", "...", "...", "...", "###")),
            Map.entry('.', rows("...", "...", "...", "...", ".#.")),
            Map.entry(' ', rows("...", "...", "...", "...", "...")),
            Map.entry('A', rows(".#.", "#.#", "###", "#.#", "#.#")),
            Map.entry('B', rows("##.", "#.#", "##.", "#.#", "##.")),
            Map.entry('C', rows(".##", "#..", "#..", "#..", ".##")),
            Map.entry('D', rows("##.", "#.#", "#.#", "#.#", "##.")),
            Map.entry('E', rows("###", "#..", "##.", "#..", "###")),
            Map.entry('F', rows("###", "#..", "##.", "#..", "#..")),
            Map.entry('G', rows(".##", "#..", "#.#", "#.#", ".##")),
            Map.entry('H', rows("#.#", "#.#", "###", "#.#", "#.#")),
            Map.entry('I', rows("###", ".#.", ".#.", ".#.", "###")),
            Map.entry('J', rows("..#", "..#", "..#", "#.#", ".#.")),
            Map.entry('K', rows("#.#", "#.#", "##.", "#.#", "#.#")),
            Map.entry('L', rows("#..", "#..", "#..", "#..", "###")),
            Map.entry('M', rows("#.#", "###", "###", "#.#", "#.#")),
            Map.entry('N', rows("##.", "#.#", "#.#", "#.#", "#.#")),
            Map.entry('O', rows(".#.", "#.#", "#.#", "#.#", ".#.")),
            Map.entry('P', rows("##.", "#.#", "##.", "#..", "#..")),
            Map.entry('Q', rows(".#.", "#.#", "#.#", "##.", ".##")),
            Map.entry('R', rows("##.", "#.#", "##.", "#.#", "#.#")),
            Map.entry('S', rows(".##", "#..", ".#.", "..#", "##.")),
            Map.entry('T', rows("###", ".#.", ".#.", ".#.", ".#.")),
            Map.entry('U', rows("#.#", "#.#", "#.#", "#.#", "###")),
            Map.entry('V', rows("#.#", "#.#", "#.#", "#.#", ".#.")),
            Map.entry('W', rows("#.#", "#.#", "###", "###", "#.#")),
            Map.entry('X', rows("#.#", "#.#", ".#.", "#.#", "#.#")),
            Map.entry('Y', rows("#.#", "#.#", ".#.", ".#.", ".#.")),
            Map.entry('Z', rows("###", "..#", ".#.", "#..", "###")));
    private static final String[] UNKNOWN = rows("##.", "..#", ".#.", "...", ".#.");

    private static String[] rows(String... r) {
        return r;
    }

    /** Width in pixels of `text` at `scale` (one pixel column of space between glyphs). */
    static int width(String text, int scale) {
        return text.isEmpty() ? 0 : (text.length() * (WIDTH + 1) - 1) * scale;
    }

    static void draw(BufferedImage img, String text, int x, int y, int scale, int rgb) {
        String upper = text.toUpperCase(Locale.ROOT);
        for (int i = 0; i < upper.length(); i++) {
            String[] g = GLYPHS.getOrDefault(upper.charAt(i), UNKNOWN);
            int gx = x + i * (WIDTH + 1) * scale;
            for (int row = 0; row < HEIGHT; row++) {
                for (int col = 0; col < WIDTH; col++) {
                    if (g[row].charAt(col) == '#') fill(img, gx + col * scale, y + row * scale, scale, scale, rgb);
                }
            }
        }
    }

    /** Text with a light outline, readable on any colour. */
    static void drawOutlined(BufferedImage img, String text, int x, int y, int scale, int rgb, int outline) {
        for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) if (dx != 0 || dy != 0) draw(img, text, x + dx, y + dy, scale, outline);
        draw(img, text, x, y, scale, rgb);
    }

    static void fill(BufferedImage img, int x, int y, int w, int h, int rgb) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(img.getWidth(), x + w), y1 = Math.min(img.getHeight(), y + h);
        for (int py = y0; py < y1; py++) for (int px = x0; px < x1; px++) img.setRGB(px, py, rgb);
    }
}
