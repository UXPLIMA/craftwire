package com.uxplima.craftwire.fabric.mixin;

import com.uxplima.craftwire.fabric.video.AudioTap;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keeps the sound engine on the recording's loopback device even when the system's audio devices change. */
@Mixin(SoundEngine.class)
public abstract class SoundEngineMixin {
    @Inject(method = "shouldChangeDevice", at = @At("HEAD"), cancellable = true)
    private void craftwire$stayOnLoopback(CallbackInfoReturnable<Boolean> cir) {
        if (AudioTap.armed()) cir.setReturnValue(false);
    }
}
