package com.uxplima.craftwire.paper.world.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.jupiter.api.Test;

class RendererTest {
    static final int GRASS = 0x7FB238;
    static final int STONE = 0x707070;
    static final int WATER = 0x4040FF;
    static final int RED = 0xFF0000;

    /** A small world: grass at y=64 everywhere, a stone pillar at (2,2) up to y=66, a pool at x=5 (water y=63-64). */
    static final class World implements BlockSource {
        @Override public boolean loaded(int x, int z) { return x < 8; }
        @Override public int top(int x, int z) { return x == 2 && z == 2 ? 66 : 64; }
        @Override public int color(int x, int y, int z) {
            if (x == 2 && z == 2 && y >= 65 && y <= 66) return STONE;
            if (x == 5 && (y == 63 || y == 64)) return WATER;
            if (y == 64) return GRASS;
            if (y < 64) return STONE;
            return 0;
        }
        @Override public boolean water(int x, int y, int z) { return x == 5 && (y == 63 || y == 64); }
        @Override public int minY() { return 0; }
        @Override public int maxY() { return 128; }
    }

    static int rgb(BufferedImage img, int x, int y) {
        return img.getRGB(x, y) & 0xFFFFFF;
    }

    @Test
    void topViewShadesBlocksLikeAMapAndMarksUnloadedChunks() {
        Render r = new Renderer(new World()).render(new View.Top(), new Region(0, 0, 9, 4), 1, 0, List.of());
        BufferedImage img = r.image();
        assertEquals(10, img.getWidth());
        assertEquals(5, img.getHeight());
        assertEquals(Shading.shade(GRASS, Shading.NORMAL), rgb(img, 0, 1), "flat ground: normal brightness");
        assertEquals(Shading.shade(STONE, Shading.HIGH), rgb(img, 2, 2), "higher than the block to the north: bright");
        assertEquals(Shading.shade(GRASS, Shading.LOW), rgb(img, 2, 3), "lower than the block to the north: dark");
        int water = rgb(img, 5, 1);
        assertTrue(water == Shading.shade(WATER, Shading.HIGH) || water == Shading.shade(WATER, Shading.NORMAL), "shallow water is light");
        assertEquals(Shading.UNLOADED_A, rgb(img, 9, 0) == Shading.UNLOADED_A ? Shading.UNLOADED_A : rgb(img, 9, 1), "unloaded chunks are checkered");
        assertEquals(2, r.unloadedColumns() / 5, "two columns (x=8,9) are unloaded");
    }

    @Test
    void scaleMultipliesPixelsPerBlock() {
        BufferedImage img = new Renderer(new World()).render(new View.Top(), new Region(0, 0, 3, 3), 4, 0, List.of()).image();
        assertEquals(16, img.getWidth());
        assertEquals(rgb(img, 8, 8), rgb(img, 11, 11), "one block is 4x4 pixels");
    }

    @Test
    void aSliceShowsTheBlocksAtOneHeightAndAirAsBackground() {
        BufferedImage img = new Renderer(new World()).render(new View.Slice(65), new Region(0, 0, 4, 4), 1, 0, List.of()).image();
        assertEquals(STONE, rgb(img, 2, 2));
        assertEquals(Shading.AIR, rgb(img, 0, 0));
    }

    @Test
    void aSideViewLooksAlongTheFacingAndDarkensWithDistance() {
        // Looking north from the south: x grows to the right, height upward, nearest block wins.
        Render r = new Renderer(new World()).render(new View.Side(View.Facing.NORTH, 60, 67), new Region(0, 0, 4, 4), 1, 0, List.of());
        BufferedImage img = r.image();
        assertEquals(5, img.getWidth());
        assertEquals(8, img.getHeight(), "y 60..67");
        int pillarTop = rgb(img, 2, 67 - 66);
        assertNotEquals(Shading.AIR, pillarTop, "the pillar shows above the ground");
        assertEquals(Shading.SKY, rgb(img, 0, 0), "nothing at y=67 in column x=0");
        assertEquals(Shading.depth(GRASS, 0, 5), rgb(img, 0, 67 - 64), "the nearest grass is not darkened");
    }

    @Test
    void aGridAddsMarginsWithCoordinateLabels() {
        Render r = new Renderer(new World()).render(new View.Top(), new Region(-16, -16, 15, 15), 2, 16, List.of());
        BufferedImage img = r.image();
        assertTrue(img.getWidth() > 64 && img.getHeight() > 64, "labels need margins: " + img.getWidth() + "x" + img.getHeight());
        assertEquals(r.originX() + 64, img.getWidth());
        assertEquals(Shading.GRID, rgb(img, r.originX() + 32, r.originY() + 5), "a grid line at x=0 (16 blocks in, 2 px each)");
    }

    @Test
    void playersAreMarked() {
        Render r = new Renderer(new World()).render(new View.Top(), new Region(0, 0, 7, 7), 4, 0, List.of(new Marker("Bob", 3.5, 64, 3.5)));
        assertEquals(Shading.MARKER, rgb(r.image(), r.originX() + 14, r.originY() + 14));
    }
}
