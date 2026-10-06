package com.uxplima.craftwire.fabric.mixin;

import com.uxplima.craftwire.fabric.CaptureOptions;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Hud.class)
public abstract class HudChatMixin {
    /** Command feedback ("Teleported …") must not end up in HUD screenshots taken with chat:false. */
    @Inject(method = "extractChat", at = @At("HEAD"), cancellable = true)
    private void craftwire$hideChat(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
        if (CaptureOptions.hideChat) ci.cancel();
    }
}
