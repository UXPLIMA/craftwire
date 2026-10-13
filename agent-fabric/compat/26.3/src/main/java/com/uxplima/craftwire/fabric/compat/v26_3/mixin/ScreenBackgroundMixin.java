package com.uxplima.craftwire.fabric.compat.v26_3.mixin;

import com.uxplima.craftwire.fabric.compat.v26_3.GuiCanvas;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The canvas pass skips the menu background (darkening, blur); the player's own frame keeps it. */
@Mixin(Screen.class)
public abstract class ScreenBackgroundMixin {
    @Inject(method = "extractBackground", at = @At("HEAD"), cancellable = true)
    private void craftwire$noBackgroundOnCanvas(GuiGraphicsExtractor g, int mouseX, int mouseY, float partial, CallbackInfo ci) {
        if (GuiCanvas.extracting()) ci.cancel();
    }
}
