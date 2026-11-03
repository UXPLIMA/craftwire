package com.uxplima.craftwire.fabric.compat.v26_3.mixin;

import com.uxplima.craftwire.fabric.lite.LiteMode;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lite mode: skips extracting and drawing the frame while nobody needs it (LiteMode.drawFrame). Ticks, packets and
 * screens keep running; the window shows its last frame.
 */
@Mixin(GameRenderer.class)
public abstract class LiteRenderMixin {
    @Inject(method = "extract(Lnet/minecraft/client/DeltaTracker;Z)V", at = @At("HEAD"), cancellable = true)
    private void craftwire$skipExtract(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
        if (!LiteMode.drawFrame()) ci.cancel();
    }

    @Inject(method = "render()V", at = @At("HEAD"), cancellable = true)
    private void craftwire$skipRender(CallbackInfo ci) {
        if (!LiteMode.drawFrame()) ci.cancel();
    }
}
