package com.uxplima.craftwire.fabric.capture;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;

class AlphaFrameTest {
    /** 2x2 RGBA, rows bottom-up: pixels given top-down as {r,g,b,a}. */
    static ByteBuffer frame(int[][] topDown, int w, int h) {
        ByteBuffer b = ByteBuffer.allocate(w * h * 4);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int[] p = topDown[(h - 1 - y) * w + x];
                b.put((byte) p[0]).put((byte) p[1]).put((byte) p[2]).put((byte) p[3]);
            }
        }
        return b.flip();
    }

    @Test
    void premultipliedColourBecomesStraightAndRowsFlip() {
        // top-left: half-transparent red stored premultiplied (128,0,0,128); bottom-right opaque blue; others empty
        ByteBuffer f = frame(new int[][] {{128, 0, 0, 128}, {0, 0, 0, 0}, {0, 0, 0, 0}, {0, 0, 255, 255}}, 2, 2);
        BufferedImage img = AlphaFrame.fromPremultiplied(f, 2, 2);
        assertEquals(0x80FF0000, img.getRGB(0, 0));
        assertEquals(0x00000000, img.getRGB(1, 0));
        assertEquals(0xFF0000FF, img.getRGB(1, 1));
    }

    @Test
    void matteRecoversAlphaFromBlackAndWhite() {
        // 50% white over black = 128 grey; over white = 255
        ByteBuffer black = frame(new int[][] {{128, 128, 128, 255}, {0, 0, 0, 255}, {0, 0, 0, 255}, {0, 0, 0, 255}}, 2, 2);
        ByteBuffer white = frame(new int[][] {{255, 255, 255, 255}, {255, 255, 255, 255}, {255, 255, 255, 255}, {0, 0, 0, 255}}, 2, 2);
        BufferedImage m = AlphaFrame.matte(black, white, 2, 2);
        int top = m.getRGB(0, 0);
        assertTrue(Math.abs((top >>> 24) - 128) <= 1, Integer.toHexString(top));
        assertTrue(Math.abs((top >> 16 & 255) - 255) <= 2, Integer.toHexString(top));
        assertEquals(0, m.getRGB(1, 0) >>> 24);                 // black over black, white over white: nothing there
        assertEquals(0xFF000000, m.getRGB(1, 1));                // opaque black in both
    }

    @Test
    void trimKeepsContentPlusPadding() {
        BufferedImage img = new BufferedImage(100, 50, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(40, 20, 0xFFFFFFFF);
        img.setRGB(59, 29, 0x10FFFFFF);
        BufferedImage t = AlphaFrame.trim(img, 8);
        assertEquals(20 + 16, t.getWidth());
        assertEquals(10 + 16, t.getHeight());
        assertEquals(0xFFFFFFFF, t.getRGB(8, 8));
        BufferedImage edge = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        edge.setRGB(0, 0, 0xFFFFFFFF);
        assertEquals(9, AlphaFrame.trim(edge, 8).getWidth());   // padding is clamped to the image
        BufferedImage empty = new BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB);
        assertSame(empty, AlphaFrame.trim(empty, 8));
    }

    @Test
    void maxDifferenceIgnoresInvisiblePixels() {
        BufferedImage a = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        BufferedImage b = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        a.setRGB(0, 0, 0x01FF0000);   // nearly invisible: colour does not matter
        b.setRGB(0, 0, 0x0100FF00);
        a.setRGB(1, 0, 0xFF808080);
        b.setRGB(1, 0, 0xFF848080);
        assertEquals(4, AlphaFrame.maxDifference(a, b, 16));
    }
}
