package com.uxplima.craftwire.fabric.compat.v26_3.mixin;

import com.uxplima.craftwire.fabric.compat.v26_3.GuiCanvas;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Tells the canvas whether this extraction ends with a tooltip (drawn in a stratum of its own, the last one). */
@Mixin(GuiGraphicsExtractor.class)
public abstract class DeferredTooltipMixin {
    @Shadow
    private Runnable deferredTooltip;

    @Inject(method = "extractDeferredElements", at = @At("HEAD"))
    private void craftwire$noteTooltip(int mouseX, int mouseY, float partial, CallbackInfo ci) {
        if (GuiCanvas.extracting()) GuiCanvas.noteTooltip(deferredTooltip != null);
    }
}
