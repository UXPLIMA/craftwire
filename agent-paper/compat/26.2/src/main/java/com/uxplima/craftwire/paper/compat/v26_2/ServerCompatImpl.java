package com.uxplima.craftwire.paper.compat.v26_2;

import com.uxplima.craftwire.paper.compat.ServerCompat;
import java.lang.reflect.Field;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;

/** Minecraft 26.2. */
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

    @Override
    public Component chatComponent(ClientboundPlayerChatPacket p) {
        return p.unsignedContent() != null ? p.unsignedContent() : Component.literal(p.body().content());
    }

    @Override
    public SignTarget signEditor(ClientboundOpenSignEditorPacket p) {
        return new SignTarget(p.getPos(), p.isFrontText());
    }

    @Override
    public void signUpdate(ServerGamePacketListenerImpl listener, BlockPos pos, boolean front, List<String> lines) {
        listener.handleSignUpdate(new ServerboundSignUpdatePacket(pos, front, lines.get(0), lines.get(1), lines.get(2), lines.get(3)));
    }

    @Override
    public void swing(ServerGamePacketListenerImpl listener, InteractionHand hand) {
        listener.handleAnimate(new ServerboundSwingPacket(hand));
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
