package com.uxplima.craftwire.fabric.mixin;

import com.mojang.blaze3d.audio.Library;
import com.uxplima.craftwire.fabric.video.AudioTap;
import java.nio.IntBuffer;
import org.lwjgl.system.MemoryStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** While a video records sound, the sound engine opens an OpenAL loopback device instead of a speaker (AudioTap). */
@Mixin(Library.class)
public abstract class LibraryMixin {
    @Inject(method = "openDeviceOrFallback", at = @At("HEAD"), cancellable = true)
    private static void craftwire$openLoopback(String preferredDevice, String defaultDevice, CallbackInfoReturnable<Long> cir) {
        if (AudioTap.armed()) cir.setReturnValue(AudioTap.openLoopbackDevice());
    }

    @Inject(method = "createAttributes", at = @At("RETURN"), cancellable = true)
    private void craftwire$loopbackFormat(MemoryStack stack, boolean enableHrtf, CallbackInfoReturnable<IntBuffer> cir) {
        if (AudioTap.armed()) cir.setReturnValue(AudioTap.withLoopbackFormat(stack, cir.getReturnValue()));
    }
}
