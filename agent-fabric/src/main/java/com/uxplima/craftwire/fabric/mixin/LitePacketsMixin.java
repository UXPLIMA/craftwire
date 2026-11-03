package com.uxplima.craftwire.fabric.mixin;

import com.uxplima.craftwire.fabric.lite.LiteMode;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAwardStatsPacket;
import net.minecraft.network.protocol.game.ClientboundUpdateAdvancementsPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lite mode drops the advancement tree (sent whole on join, with toasts after) and statistics: a hidden test client
 * never shows them. Cancelled at the head, before the handler hops to the render thread.
 */
@Mixin(ClientPacketListener.class)
public abstract class LitePacketsMixin {
    @Inject(method = "handleUpdateAdvancementsPacket", at = @At("HEAD"), cancellable = true)
    private void craftwire$dropAdvancements(ClientboundUpdateAdvancementsPacket packet, CallbackInfo ci) {
        if (LiteMode.ENABLED) ci.cancel();
    }

    @Inject(method = "handleAwardStats", at = @At("HEAD"), cancellable = true)
    private void craftwire$dropStats(ClientboundAwardStatsPacket packet, CallbackInfo ci) {
        if (LiteMode.ENABLED) ci.cancel();
    }
}
