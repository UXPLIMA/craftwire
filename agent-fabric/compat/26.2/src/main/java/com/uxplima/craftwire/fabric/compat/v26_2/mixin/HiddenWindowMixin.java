package com.uxplima.craftwire.fabric.compat.v26_2.mixin;

import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * client_process starts clients with -Dcraftwire.hidden=true. Minecraft creates its window invisible and shows it at
 * the end of its constructor; a hidden client skips that one call and keeps rendering into its own framebuffers.
 */
@Mixin(Minecraft.class)
public abstract class HiddenWindowMixin {
    @Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwShowWindow(J)V"))
    private void craftwire$showUnlessHidden(long window) {
        if (!Boolean.getBoolean("craftwire.hidden")) GLFW.glfwShowWindow(window);
    }
}
