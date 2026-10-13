package com.uxplima.craftwire.fabric.capture;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.fabric.ClientScheduler;
import com.uxplima.craftwire.fabric.Params;
import com.uxplima.craftwire.fabric.compat.ClientCompat;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;

/** Screenshots of the open GUI and/or tooltip on a transparent background. */
public final class GuiCapture {
    private GuiCapture() {}

    public record Options(GuiLayer layer, boolean split, boolean trim, Integer guiScale) {
        public static Options parse(JsonObject p, String savePath) {
            GuiLayer layer;
            try {
                layer = GuiLayer.parse(Params.optString(p, "layer").orElse(null));
            } catch (IllegalArgumentException e) {
                throw Params.invalid(e.getMessage());
            }
            if (layer == null) return null;
            String name = savePath == null ? "" : savePath.toLowerCase(Locale.ROOT);
            if (name.endsWith(".jpg") || name.endsWith(".jpeg")) throw Params.invalid("a transparent layer needs a .png savePath (JPEG has no transparency)");
            boolean split = Params.optBool(p, "split").orElse(false);
            if (split && layer != GuiLayer.GUI_TOOLTIP) throw Params.invalid("split needs layer 'gui+tooltip'");
            Integer scale = Params.optInt(p, "guiScale").orElse(null);
            if (scale != null && (scale < 1 || scale > 8)) throw Params.invalid("guiScale must be 1-8");
            return new Options(layer, split, Params.optBool(p, "trim").orElse(true), scale);
        }
    }

    public static Path splitPath(Path savePath, String part) {
        String n = savePath.getFileName().toString();
        int dot = n.lastIndexOf('.');
        String named = dot < 0 ? n + "." + part : n.substring(0, dot) + "." + part + n.substring(dot);
        return savePath.resolveSibling(named);
    }

    /** One image per part: [gui, tooltip] when split, else the layer. Fails with NO_SCREEN / NO_TOOLTIP. */
    public static CompletableFuture<List<BufferedImage>> grab(ClientScheduler s, Options o) {
        List<GuiLayer> parts = o.split() ? List.of(GuiLayer.GUI, GuiLayer.TOOLTIP) : List.of(o.layer());
        List<CompletableFuture<BufferedImage>> images = new ArrayList<>();
        return s.call(() -> {
            Minecraft mc = Minecraft.getInstance();
            RenderTarget main = mc.gameRenderer.mainRenderTarget();
            ClientCompat compat = ClientCompat.get();
            for (GuiLayer part : parts) {
                // One canvas per part: the reads complete a frame later, so a shared canvas would be overwritten.
                RenderTarget canvas = part == GuiLayer.TOOLTIP && o.split() ? compat.guiCanvas(main.width, main.height, 1) : compat.guiCanvas(main.width, main.height, 0);
                ClientCompat.Drawn d = compat.drawGui(part, canvas, 0);
                if (part.screen() && !d.screen()) throw new AgentError("NO_SCREEN", "No screen is open.", "Open the menu first (gui_action, a command or the plugin's item), then capture.");
                if (part == GuiLayer.TOOLTIP && !d.tooltip()) throw new AgentError("NO_TOOLTIP", "No tooltip is shown.", "Hover an item first: gui_action {action:'hover', slot}.");
                CompletableFuture<BufferedImage> img = new CompletableFuture<>();
                int w = canvas.width, h = canvas.height;
                compat.readPixels(canvas, b -> {
                    try {
                        img.complete(AlphaFrame.fromPremultiplied(b, w, h));
                    } catch (RuntimeException e) {
                        img.completeExceptionally(e);
                    }
                });
                images.add(img);
            }
            return Boolean.TRUE;
        }).thenCompose(v -> CompletableFuture.allOf(images.toArray(CompletableFuture[]::new)))
                .thenApply(v -> images.stream().map(CompletableFuture::join).toList());
    }
}
