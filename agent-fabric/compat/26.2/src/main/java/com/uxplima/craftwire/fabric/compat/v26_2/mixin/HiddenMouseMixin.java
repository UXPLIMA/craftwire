package com.uxplima.craftwire.fabric.compat.v26_2.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A hidden client (client_process, -Dcraftwire.hidden=true) counts as the active window on Windows, so joining a world
 * grabs the mouse: the game would lock the user's cursor to the middle of the screen, where the invisible window is.
 * The game still counts the mouse as grabbed (MouseHandler state); only the system cursor is left alone.
 */
@Mixin(InputConstants.class)
public abstract class HiddenMouseMixin {
    @Inject(method = "grabOrReleaseMouse", at = @At("HEAD"), cancellable = true)
    private static void craftwire$leaveCursor(Window window, int mode, double x, double y, CallbackInfo ci) {
        if (Boolean.getBoolean("craftwire.hidden")) ci.cancel();
    }
}
