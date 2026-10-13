package com.uxplima.craftwire.paper.world.render;

import static org.junit.jupiter.api.Assertions.*;
import com.uxplima.craftwire.paper.world.Box;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

class IsoRendererTest {
    /** One red block at the origin on nothing. */
    static final BlockSource ONE = new BlockSource() {
        @Override public boolean loaded(int x, int z) { return true; }
        @Override public int top(int x, int z) { return 1; }
        @Override public int color(int x, int y, int z) { return x == 0 && y == 0 && z == 0 ? 0xFF0000 : 0; }
        @Override public boolean water(int x, int y, int z) { return false; }
        @Override public int minY() { return 0; }
        @Override public int maxY() { return 16; }
    };

    @Test
    void drawsTopBrighterThanSides() {
        BufferedImage img = IsoRenderer.render(ONE, new Box(0, 0, 0, 0, 0, 0), "se", 200);
        int[] reds = img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
        int brightest = 0, darkest = 255, background = img.getRGB(1, 1) & 0xFFFFFF;
        for (int c : reds) {
            int r = c >> 16 & 0xFF, g = c >> 8 & 0xFF;
            if (g == 0 && r > 0) { brightest = Math.max(brightest, r); darkest = Math.min(darkest, r); }
        }
        assertEquals(255, brightest);             // the top face is unshaded
        assertTrue(darkest < 200, "sides are darker: " + darkest);
        assertEquals(Shading.SKY, background);
    }

    @Test
    void emptyBoxIsJustSky() {
        BufferedImage img = IsoRenderer.render(ONE, new Box(5, 0, 5, 6, 3, 6), "nw", 128);
        for (int y = 0; y < img.getHeight(); y += 7) for (int x = 0; x < img.getWidth(); x += 7) {
            int c = img.getRGB(x, y) & 0xFFFFFF;
            assertTrue(c == Shading.SKY || c == Shading.LABEL || c == 0xFFFFFF, Integer.toHexString(c));
        }
    }

    @Test
    void sheetHasFourQuarters() {
        BufferedImage img = IsoRenderer.sheet(ONE, new Box(0, 0, 0, 0, 0, 0), 400);
        assertEquals(400, img.getWidth());
        assertEquals(400, img.getHeight());
        for (int[] q : new int[][] {{0, 0}, {200, 0}, {0, 200}, {200, 200}}) {
            boolean red = false;
            for (int y = q[1]; y < q[1] + 200 && !red; y++) for (int x = q[0]; x < q[0] + 200 && !red; x++) red = (img.getRGB(x, y) & 0xFFFF) == 0 && (img.getRGB(x, y) >> 16 & 0xFF) > 0;
            assertTrue(red, "quarter " + q[0] + "," + q[1]);
        }
    }

    @Test
    void refusesHugeFootprints() {
        assertThrows(RuntimeException.class, () -> IsoRenderer.render(ONE, new Box(0, 0, 0, 300, 0, 0), "se", 256));
    }
}
