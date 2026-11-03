package com.uxplima.craftwire.fabric.mixin;

import com.uxplima.craftwire.fabric.resourcepack.ClientPacks;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Reports each resource pack answer the client sends; every Connection.send overload ends in this one. */
@Mixin(Connection.class)
public abstract class ResourcePackStatusMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
    private void craftwire$reportPack(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (packet instanceof ServerboundResourcePackPacket p) ClientPacks.reported(p);
    }
}
