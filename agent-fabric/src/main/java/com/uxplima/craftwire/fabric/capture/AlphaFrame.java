package com.uxplima.craftwire.fabric.capture;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;

/** Pixels read back from the GPU (RGBA, rows bottom-up) as images with straight alpha. */
public final class AlphaFrame {
    private AlphaFrame() {}

    /** The GUI blends colour with SRC_ALPHA and alpha with ONE, so a canvas cleared to 0 holds premultiplied colour. */
    public static BufferedImage fromPremultiplied(ByteBuffer rgba, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            int base = (height - 1 - y) * width * 4;
            for (int x = 0; x < width; x++) {
                int i = base + x * 4;
                int a = rgba.get(i + 3) & 255;
                if (a == 0) {
                    row[x] = 0;
                    continue;
                }
                int r = Math.min(255, ((rgba.get(i) & 255) * 255 + a / 2) / a);
                int g = Math.min(255, ((rgba.get(i + 1) & 255) * 255 + a / 2) / a);
                int b = Math.min(255, ((rgba.get(i + 2) & 255) * 255 + a / 2) / a);
                row[x] = a << 24 | r << 16 | g << 8 | b;
            }
            img.setRGB(0, y, width, 1, row, 0, width);
        }
        return img;
    }

    /** Difference matte: the same layer over opaque black and opaque white. alpha = 1 - (white - black). */
    public static BufferedImage matte(ByteBuffer overBlack, ByteBuffer overWhite, int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            int base = (height - 1 - y) * width * 4;
            for (int x = 0; x < width; x++) {
                int i = base + x * 4;
                int diff = 0;
                for (int c = 0; c < 3; c++) diff += (overWhite.get(i + c) & 255) - (overBlack.get(i + c) & 255);
                int a = Math.max(0, Math.min(255, 255 - Math.round(diff / 3f)));
                if (a == 0) {
                    row[x] = 0;
                    continue;
                }
                int r = Math.min(255, ((overBlack.get(i) & 255) * 255 + a / 2) / a);
                int g = Math.min(255, ((overBlack.get(i + 1) & 255) * 255 + a / 2) / a);
                int b = Math.min(255, ((overBlack.get(i + 2) & 255) * 255 + a / 2) / a);
                row[x] = a << 24 | r << 16 | g << 8 | b;
            }
            img.setRGB(0, y, width, 1, row, 0, width);
        }
        return img;
    }

    /** Crops to the visible pixels plus {@code pad} on each side (clamped); an empty image is returned as is. */
    public static BufferedImage trim(BufferedImage img, int pad) {
        int minX = img.getWidth(), minY = img.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (img.getRGB(x, y) >>> 24 == 0) continue;
                minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            }
        }
        if (maxX < 0) return img;
        int x0 = Math.max(0, minX - pad), y0 = Math.max(0, minY - pad);
        int x1 = Math.min(img.getWidth() - 1, maxX + pad), y1 = Math.min(img.getHeight() - 1, maxY + pad);
        BufferedImage out = new BufferedImage(x1 - x0 + 1, y1 - y0 + 1, BufferedImage.TYPE_INT_ARGB);
        out.getGraphics().drawImage(img.getSubimage(x0, y0, out.getWidth(), out.getHeight()), 0, 0, null);
        return out;
    }

    /** Largest channel difference (a, r, g, b) over pixels where either image has alpha ≥ {@code minAlpha}. */
    public static int maxDifference(BufferedImage a, BufferedImage b, int minAlpha) {
        int max = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                if ((p >>> 24) < minAlpha && (q >>> 24) < minAlpha) continue;
                for (int s = 0; s < 32; s += 8) max = Math.max(max, Math.abs((p >>> s & 255) - (q >>> s & 255)));
            }
        }
        return max;
    }
}
