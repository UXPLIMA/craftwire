package com.uxplima.craftwire.paper.compat.v26_2;

import com.uxplima.craftwire.paper.compat.ServerCompat;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;

/** Minecraft 26.2. */
public final class ServerCompatImpl implements ServerCompat {
    @Override
    public void acceptTeleport(ServerGamePacketListenerImpl listener, ServerPlayer player, int id) {
        listener.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(id));
    }

    @Override
    public void swingAfterInteraction(ServerGamePacketListenerImpl listener, InteractionHand hand) {
        listener.handleAnimate(new ServerboundSwingPacket(hand));
    }

    @Override
    public String chatText(ClientboundPlayerChatPacket p) {
        return p.unsignedContent() != null ? p.unsignedContent().getString() : p.body().content();
    }
}
