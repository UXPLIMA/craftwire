package com.uxplima.craftwire.fabric.compat.v26_3.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.uxplima.craftwire.fabric.compat.v26_3.GuiCanvas;
import net.minecraft.client.gui.render.GuiRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** While the canvas draws, the GUI renderer targets the canvas instead of the main render target. */
@Mixin(GuiRenderer.class)
public abstract class GuiRendererTargetMixin {
    @Redirect(method = "draw", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;mainRenderTarget()Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
    private RenderTarget craftwire$canvasTarget(GameRenderer renderer) {
        RenderTarget canvas = GuiCanvas.drawingInto();
        return canvas != null ? canvas : renderer.mainRenderTarget();
    }
}
