package com.uxplima.craftwire.fabric.mixin;

import com.uxplima.craftwire.fabric.resourcepack.ClientPacks;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applies client_process's resourcePacks policy to the server entry just before vanilla decides on a pushed pack. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ResourcePackPolicyMixin {
    @Shadow @Final protected ServerData serverData;

    @Inject(method = "handleResourcePackPush", at = @At("HEAD"))
    private void craftwire$applyPolicy(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
        ClientPacks.applyPolicy(serverData);
    }
}
