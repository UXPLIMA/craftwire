package com.uxplima.craftwire.fabric.mixin;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import com.uxplima.craftwire.fabric.lite.LiteMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Lite mode: while no frame is drawn, the game loop sleeps to the tick rate instead of spinning. */
@Mixin(FramerateLimitTracker.class)
public abstract class LiteFramerateMixin {
    @Inject(method = "getFramerateLimit", at = @At("HEAD"), cancellable = true)
    private void craftwire$idle(CallbackInfoReturnable<Integer> cir) {
        if (!LiteMode.drawFrame()) cir.setReturnValue(LiteMode.IDLE_FPS);
    }
}
