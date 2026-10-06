package com.uxplima.craftwire.fabric.compat.v26_3.mixin;

import com.mojang.renderpearl.backend.opengl.GlBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * client_process starts clients with -Dcraftwire.hidden=true. From 26.3 the window is created visible by
 * SDL_CreateWindow (both renderer backends); a hidden client adds SDL_WINDOW_HIDDEN to the creation flags and
 * keeps rendering into its own framebuffers.
 */
@Mixin({GlBackend.class, VulkanBackend.class})
public abstract class HiddenWindowMixin {
    @ModifyVariable(method = "createWindow", at = @At("HEAD"), argsOnly = true)
    private long craftwire$hideWindow(long flags) {
        return Boolean.getBoolean("craftwire.hidden") ? flags | SDLVideo.SDL_WINDOW_HIDDEN : flags;
    }
}
