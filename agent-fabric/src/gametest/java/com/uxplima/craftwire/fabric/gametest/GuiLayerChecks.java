package com.uxplima.craftwire.fabric.gametest;

import static com.uxplima.craftwire.fabric.gametest.Calls.check;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.uxplima.craftwire.fabric.capture.AlphaFrame;
import com.uxplima.craftwire.fabric.capture.GuiLayer;
import com.uxplima.craftwire.fabric.compat.ClientCompat;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

/** The transparent GUI canvas on the real renderer (Task 2 spike; later tasks add more). */
final class GuiLayerChecks {
    private GuiLayerChecks() {}

    record Shot(BufferedImage image, ClientCompat.Drawn drawn) {}

    /** Draws the layer on the render thread and reads it back (two frames later). */
    static Shot draw(ClientGameTestContext ctx, GuiLayer layer, int clearArgb) throws Exception {
        CompletableFuture<ByteBuffer> pixels = new CompletableFuture<>();
        int[] size = new int[2];
        ClientCompat.Drawn drawn = ctx.computeOnClient(mc -> {
            RenderTarget main = mc.gameRenderer.mainRenderTarget();
            RenderTarget canvas = ClientCompat.get().guiCanvas(main.width, main.height, 0);
            ClientCompat.Drawn d = ClientCompat.get().drawGui(layer, canvas, clearArgb);
            size[0] = canvas.width;
            size[1] = canvas.height;
            ClientCompat.get().readPixels(canvas, b -> {
                ByteBuffer copy = ByteBuffer.allocate(b.remaining());
                copy.put(b).flip();
                pixels.complete(copy);
            });
            return d;
        });
        ctx.waitTicks(3);
        ByteBuffer b = pixels.get(10, TimeUnit.SECONDS);
        return new Shot(clearArgb == 0 ? AlphaFrame.fromPremultiplied(b, size[0], size[1]) : rawImage(b, size[0], size[1]), drawn);
    }

    /** An opaque background frame kept as raw RGBA (for the matte). */
    static BufferedImage rawImage(ByteBuffer b, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = ((h - 1 - y) * w + x) * 4;
            img.setRGB(x, y, (b.get(i + 3) & 255) << 24 | (b.get(i) & 255) << 16 | (b.get(i + 1) & 255) << 8 | (b.get(i + 2) & 255));
        }
        return img;
    }

    static ByteBuffer rawBuffer(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        ByteBuffer b = ByteBuffer.allocate(w * h * 4);
        for (int y = h - 1; y >= 0; y--) for (int x = 0; x < w; x++) {
            int p = img.getRGB(x, y);
            b.put((byte) (p >> 16)).put((byte) (p >> 8)).put((byte) p).put((byte) (p >>> 24));
        }
        return b.flip();
    }

    static void run(ClientGameTestContext ctx, TestSingleplayerContext sp) throws Exception {
        // A red block right in front of the player: it must never appear in a GUI layer.
        sp.getServer().runCommand("setblock ~ ~1 ~2 minecraft:red_concrete");
        ctx.setScreen(() -> new InventoryScreen(Minecraft.getInstance().player));
        ctx.waitTicks(3);

        Shot gui = draw(ctx, GuiLayer.GUI, 0);
        check(gui.drawn().screen(), "a screen was drawn");
        BufferedImage img = gui.image();
        check(img.getRGB(2, 2) >>> 24 == 0, "corner must be transparent: " + Integer.toHexString(img.getRGB(2, 2)));
        int cx = img.getWidth() / 2, cy = img.getHeight() / 2;
        check(img.getRGB(cx, cy) >>> 24 == 255, "the inventory panel centre must be opaque: " + Integer.toHexString(img.getRGB(cx, cy)));
        for (int y = 0; y < img.getHeight(); y += 4) for (int x = 0; x < img.getWidth(); x += 4) {
            int p = img.getRGB(x, y);
            boolean red = (p >>> 24) > 200 && (p >> 16 & 255) > 150 && (p >> 8 & 255) < 60 && (p & 255) < 60;
            check(!red, "the world (red concrete) must not show at " + x + "," + y);
        }

        // The canvas must agree with the difference matte (the approach check of the spike).
        BufferedImage black = draw(ctx, GuiLayer.GUI, 0xFF000000).image();
        BufferedImage white = draw(ctx, GuiLayer.GUI, 0xFFFFFFFF).image();
        BufferedImage matte = AlphaFrame.matte(rawBuffer(black), rawBuffer(white), img.getWidth(), img.getHeight());
        // The player preview animates between the three draws, so a few pixels there differ; the rest must match.
        int visible = 0, off = 0;
        for (int y = 0; y < img.getHeight(); y++) for (int x = 0; x < img.getWidth(); x++) {
            int p = img.getRGB(x, y), q = matte.getRGB(x, y);
            if ((p >>> 24) < 16 && (q >>> 24) < 16) continue;
            visible++;
            int d = 0;
            for (int sh = 0; sh < 32; sh += 8) d = Math.max(d, Math.abs((p >>> sh & 255) - (q >>> sh & 255)));
            if (d > 6) off++;
        }
        check(visible > 1000 && off * 200 <= visible, "canvas vs matte: " + off + " of " + visible + " pixels differ by more than 6");

        // The player's own frame still shows the world behind the screen.
        ctx.waitTicks(2);
        CompletableFuture<ByteBuffer> main = new CompletableFuture<>();
        int[] ms = ctx.computeOnClient(mc -> {
            RenderTarget t = mc.gameRenderer.mainRenderTarget();
            ClientCompat.get().readPixels(t, b -> {
                ByteBuffer c = ByteBuffer.allocate(b.remaining());
                c.put(b).flip();
                main.complete(c);
            });
            return new int[] {t.width, t.height};
        });
        ctx.waitTicks(3);
        BufferedImage frame = rawImage(main.get(10, TimeUnit.SECONDS), ms[0], ms[1]);
        check(frame.getRGB(2, 2) >>> 24 == 255 && (frame.getRGB(2, 2) & 0xFFFFFF) != 0, "the player's frame is untouched");

        Calls.call(ctx, "gui.action", "{\"action\":\"close\"}");
        sp.getServer().runCommand("setblock ~ ~1 ~2 minecraft:air");
    }
}
