package com.uxplima.craftwire.fabric.compat.v26_3;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.uxplima.craftwire.fabric.capture.GuiLayer;
import com.uxplima.craftwire.fabric.compat.ClientCompat;
import com.uxplima.craftwire.fabric.compat.v26_3.mixin.GameRendererGuiAccessor;
import com.uxplima.craftwire.fabric.compat.v26_3.mixin.GuiRenderStateAccessor;
import com.uxplima.craftwire.fabric.compat.v26_3.mixin.GuiRendererAccessor;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.state.gui.GuiRenderState;
import org.joml.Vector4f;

/**
 * Minecraft 26.3: draws the open screen a second time, after the frame, into an offscreen target. The screen is
 * extracted again into the GUI render state without its background; strata are trimmed to the layer (the deferred
 * tooltip is the last stratum); the game's GuiRenderer draws them with the canvas as its target.
 */
public final class GuiCanvas {
    private static boolean extracting;
    private static boolean tooltipSeen;
    private static RenderTarget drawingInto;
    private final RenderTarget[] canvases = new RenderTarget[2];

    public static boolean extracting() {
        return extracting;
    }

    public static void noteTooltip(boolean shown) {
        tooltipSeen = shown;
    }

    public static RenderTarget drawingInto() {
        return drawingInto;
    }

    RenderTarget canvas(int width, int height, int index) {
        RenderTarget c = canvases[index];
        if (c != null && (c.width != width || c.height != height)) {
            c.destroyBuffers();
            c = null;
        }
        if (c == null) c = canvases[index] = new TextureTarget("Craftwire GUI canvas", width, height, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        return c;
    }

    ClientCompat.Drawn draw(GuiLayer layer, RenderTarget target, int clearArgb) {
        Minecraft mc = Minecraft.getInstance();
        Vector4f clear = new Vector4f((clearArgb >> 16 & 255) / 255f, (clearArgb >> 8 & 255) / 255f, (clearArgb & 255) / 255f, (clearArgb >>> 24) / 255f);
        RenderSystem.getDevice().createCommandEncoder().clearColorAndDepthTextures(target.getColorTexture(), clear, target.getDepthTexture(), 1.0);
        Screen screen = mc.gui.screen();
        if (screen == null) return new ClientCompat.Drawn(false, false);

        GuiRenderer renderer = ((GameRendererGuiAccessor) mc.gameRenderer).craftwire$guiRenderer();
        GuiRenderState state = ((GuiRendererAccessor) renderer).craftwire$renderState();
        var window = mc.getWindow();
        int mx = (int) mc.mouseHandler.getScaledXPos(window), my = (int) mc.mouseHandler.getScaledYPos(window);
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        state.reset();
        tooltipSeen = false;
        extracting = true;
        try {
            // Runs the background (skipped by ScreenBackgroundMixin), the screen and the deferred tooltip.
            screen.extractRenderStateWithTooltipAndSubtitles(new GuiGraphicsExtractor(mc, state, mx, my), mx, my, partial);
        } finally {
            extracting = false;
        }
        boolean tooltip = tooltipSeen;
        List<Object> strata = ((GuiRenderStateAccessor) state).craftwire$strata();
        if (tooltip && layer == GuiLayer.GUI) strata.removeLast();
        if (layer == GuiLayer.TOOLTIP) {
            Object last = tooltip ? strata.getLast() : null;
            strata.clear();
            if (last != null) strata.add(last);
        }
        drawingInto = target;
        try {
            renderer.render();
        } finally {
            drawingInto = null;
            renderer.endFrame();
        }
        return new ClientCompat.Drawn(true, tooltip);
    }
}
