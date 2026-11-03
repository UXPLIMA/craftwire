package com.uxplima.craftwire.paper.compat.v26_3;

import com.uxplima.craftwire.paper.compat.ServerCompat;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.entity.SignTextSlot;

/** Minecraft 26.3: the teleport confirmation carries the client's position; the server swings arms itself. */
public final class ServerCompatImpl implements ServerCompat {
    /** ServerCommonPacketListenerImpl.latency has no setter; only keep-alives (which bots never answer) change it. */
    private static final Field LATENCY = latencyField();

    private static Field latencyField() {
        try {
            Field f = ServerCommonPacketListenerImpl.class.getDeclaredField("latency");
            f.setAccessible(true);
            return f;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

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

    @Override
    public Component chatComponent(ClientboundPlayerChatPacket p) {
        return p.unsignedContent().orElseGet(() -> Component.literal(p.body().content()));
    }

    @Override
    public SignTarget signEditor(ClientboundOpenSignEditorPacket p) {
        return new SignTarget(p.pos(), p.slot() == SignTextSlot.FRONT);
    }

    @Override
    public void signUpdate(ServerGamePacketListenerImpl listener, BlockPos pos, boolean front, List<String> lines) {
        listener.handleSignUpdate(new ServerboundSignUpdatePacket(pos, List.copyOf(lines), front ? SignTextSlot.FRONT : SignTextSlot.BACK));
    }

    @Override
    public void swing(ServerGamePacketListenerImpl listener, InteractionHand hand) {
        // 26.3 has no swing packet: a left click on nothing (the punch) is what makes the arm swing.
        if (hand == InteractionHand.MAIN_HAND) listener.handlePunch(ServerboundPunchPacket.INSTANCE);
    }

    @Override
    public void setLatency(ServerGamePacketListenerImpl listener, int ms) {
        if (LATENCY == null) return;
        try {
            LATENCY.setInt(listener, ms);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Could not set the bot's latency", e);
        }
    }
}
