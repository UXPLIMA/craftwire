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

        // An enchanted book in the first hotbar slot, hovered: a tooltip with a translucent background.
        sp.getServer().runCommand("item replace entity @p hotbar.0 with minecraft:enchanted_book[minecraft:stored_enchantments={\"minecraft:sharpness\":5},minecraft:lore=['\"Craftwire lore line\"']]");
        ctx.waitTicks(3);
        Calls.call(ctx, "gui.action", "{\"action\":\"hover\",\"slot\":36}");
        ctx.waitTicks(3);
        enchantedItemIsOpaque(ctx);
        tooltipIsTranslucent(ctx);
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("craftwire-gui");
        String save = dir.resolve("panel.png").toString().replace("\\", "\\\\");
        int scaleBefore = ctx.computeOnClient(mc -> mc.options.guiScale().get());
        // A scale other than the current one: the menu is laid out again and the hovered item must stay hovered.
        int current = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale()), other = current > 1 ? current - 1 : 2;   // a smaller scale always fits the window
        var shot = Calls.call(ctx, "screenshot", "{\"layer\":\"gui+tooltip\",\"split\":true,\"guiScale\":" + other + ",\"savePath\":\"" + save + "\"}").getAsJsonObject();
        check(shot.getAsJsonArray("savedPaths").size() == 2, "split writes two files: " + shot);
        var guiPng = javax.imageio.ImageIO.read(dir.resolve("panel.gui.png").toFile());
        var tipPng = javax.imageio.ImageIO.read(dir.resolve("panel.tooltip.png").toFile());
        check(guiPng.getColorModel().hasAlpha() && tipPng.getColorModel().hasAlpha(), "PNG keeps alpha");
        check(guiPng.getRGB(0, 0) >>> 24 == 0, "trimmed image keeps a transparent margin");
        check(tipPng.getWidth() < guiPng.getWidth(), "the tooltip file holds only the tooltip");
        guiScaleIsRestoredEvenWhenClamped(ctx, scaleBefore);
        canvasFollowsWindowSize(ctx);
        check(Calls.error(ctx, "screenshot", "{\"layer\":\"gui\",\"savePath\":\"x.jpg\"}").code().equals("INVALID_PARAMS"), "jpg refused");
        Calls.call(ctx, "gui.action", "{\"action\":\"close\"}");
        check("NO_SCREEN".equals(Calls.error(ctx, "screenshot", "{\"layer\":\"gui\"}").code()), "no screen open");
        sp.getServer().runCommand("setblock ~ ~1 ~2 minecraft:air");
    }

    /** Review Focus #1: an item with glint is drawn opaque on the canvas. */
    static void enchantedItemIsOpaque(ClientGameTestContext ctx) throws Exception {
        int[] slot = ctx.computeOnClient(mc -> {
            var s = (net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) mc.gui.screen();
            var sl = s.getMenu().slots.get(36);
            int scale = mc.getWindow().getGuiScale();
            return new int[] {(((com.uxplima.craftwire.fabric.mixin.AbstractContainerScreenAccessor) s).craftwire$getLeftPos() + sl.x + 8) * scale,
                    (((com.uxplima.craftwire.fabric.mixin.AbstractContainerScreenAccessor) s).craftwire$getTopPos() + sl.y + 8) * scale};
        });
        BufferedImage img = draw(ctx, GuiLayer.GUI, 0).image();
        check(img.getRGB(slot[0], slot[1]) >>> 24 == 255, "the book's centre must be opaque: " + Integer.toHexString(img.getRGB(slot[0], slot[1])));
    }

    static void tooltipIsTranslucent(ClientGameTestContext ctx) throws Exception {
        Shot t = draw(ctx, GuiLayer.TOOLTIP, 0);
        check(t.drawn().tooltip(), "a tooltip was shown");
        BufferedImage img = t.image();
        int translucent = 0, opaque = 0, visible = 0;
        for (int y = 0; y < img.getHeight(); y += 2) for (int x = 0; x < img.getWidth(); x += 2) {
            int a = img.getRGB(x, y) >>> 24;
            if (a > 0) visible++;
            if (a > 0 && a < 255) translucent++;
            if (a == 255) opaque++;
        }
        check(translucent > 50, "tooltip background must be translucent (" + translucent + " px)");
        check(opaque > 10, "tooltip text must be opaque (" + opaque + " px)");
        // Only the tooltip: far fewer visible pixels than the GUI layer.
        Shot gui = draw(ctx, GuiLayer.GUI, 0);
        int guiVisible = 0;
        for (int y = 0; y < gui.image().getHeight(); y += 2) for (int x = 0; x < gui.image().getWidth(); x += 2) if (gui.image().getRGB(x, y) >>> 24 > 0) guiVisible++;
        check(visible < guiVisible, "tooltip layer must not contain the inventory (" + visible + " vs " + guiVisible + ")");
        Shot both = draw(ctx, GuiLayer.GUI_TOOLTIP, 0);
        check(both.drawn().tooltip() && both.drawn().screen(), "gui+tooltip draws both");
    }

    /** Review Focus #3. */
    static void guiScaleIsRestoredEvenWhenClamped(ClientGameTestContext ctx, int before) throws Exception {
        Calls.call(ctx, "screenshot", "{\"layer\":\"gui\",\"guiScale\":8}");
        check(ctx.computeOnClient(mc -> mc.options.guiScale().get()) == before, "guiScale restored");
    }

    /** Review Focus #5: the canvas follows a window resize. */
    static void canvasFollowsWindowSize(ClientGameTestContext ctx) throws Exception {
        var a = Calls.call(ctx, "screenshot", "{\"layer\":\"gui\",\"trim\":false}").getAsJsonObject();
        int w = ctx.computeOnClient(mc -> mc.getWindow().getWidth()), h = ctx.computeOnClient(mc -> mc.getWindow().getHeight());
        ctx.runOnClient(mc -> mc.getWindow().setWindowed(w - 100, h - 60));
        ctx.waitTicks(5);
        var b = Calls.call(ctx, "screenshot", "{\"layer\":\"gui\",\"trim\":false}").getAsJsonObject();
        int bw = ctx.computeOnClient(mc -> mc.gameRenderer.mainRenderTarget().width);
        check(b.get("fullWidth").getAsInt() == bw && b.get("fullWidth").getAsInt() != a.get("fullWidth").getAsInt(), "canvas follows the window: " + a + " -> " + b);
        ctx.runOnClient(mc -> mc.getWindow().setWindowed(w, h));
        ctx.waitTicks(5);
    }
}
