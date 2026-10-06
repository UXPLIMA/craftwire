package com.uxplima.craftwire.paper.compat.v26_3;

import com.uxplima.craftwire.paper.compat.ServerCompat;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;

/** Minecraft 26.3: the teleport confirmation carries the client's position; the server swings arms itself. */
public final class ServerCompatImpl implements ServerCompat {
    @Override
    public void acceptTeleport(ServerGamePacketListenerImpl listener, ServerPlayer player, int id) {
        // The server moves the player to its own target; the client's numbers are only checked for being finite.
        listener.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(
                id, player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
    }

    @Override
    public void swingAfterInteraction(ServerGamePacketListenerImpl listener, InteractionHand hand) {
        // From 26.3 the server swings the arm itself for uses and entity attacks; a client sends a punch only for a
        // left click that hits nothing, which the bot actions never do.
    }

    @Override
    public String chatText(ClientboundPlayerChatPacket p) {
        return p.unsignedContent().map(c -> c.getString()).orElseGet(() -> p.body().content());
    }
}
