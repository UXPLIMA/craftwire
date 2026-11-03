package com.uxplima.craftwire.botrunner.javabot.compat.v26_3;

import com.uxplima.craftwire.botrunner.javabot.JavaCompat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.LastSeenMessages;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.world.level.block.entity.SignTextSlot;
import net.minecraft.world.phys.Vec3;

/**
 * The Java bot's packets in Minecraft 26.3 shapes: records, Optional chat fields, SignTextSlot, a teleport
 * confirmation with the position, and the punch packet in place of the swing packet (sent for the main hand only:
 * 26.3 has no packet for an off-hand swing).
 */
public final class JavaCompatImpl implements JavaCompat {
    @Override
    public int chunkX(ClientboundLevelChunkWithLightPacket p) {
        return p.x();
    }

    @Override
    public int chunkZ(ClientboundLevelChunkWithLightPacket p) {
        return p.z();
    }

    @Override
    public ClientboundLevelChunkPacketData chunkData(ClientboundLevelChunkWithLightPacket p) {
        return p.chunkData();
    }

    @Override
    public List<Integer> removedEntities(ClientboundRemoveEntitiesPacket p) {
        return new ArrayList<>(p.entityIds());
    }

    @Override
    public double[] movedTo(ClientboundMoveEntityPacket p, double x, double y, double z) {
        if (!p.hasPosition()) return null;
        VecDeltaCodec codec = new VecDeltaCodec();
        codec.setBase(new Vec3(x, y, z));
        Vec3 v = p.getPositionDelta().decode(codec).endPosition();
        return new double[] {v.x, v.y, v.z};
    }

    @Override
    public double[] syncPosition(ClientboundEntityPositionSyncPacket p) {
        Vec3 v = p.position().endPosition();
        return new double[] {v.x, v.y, v.z};
    }

    @Override
    public SignEditor signEditor(ClientboundOpenSignEditorPacket p) {
        return new SignEditor(p.pos(), p.slot() == SignTextSlot.FRONT);
    }

    @Override
    public Component chatContent(ClientboundPlayerChatPacket p) {
        return p.unsignedContent().orElseGet(() -> Component.literal(p.body().content()));
    }

    @Override
    public Packet<ServerGamePacketListener> signUpdate(BlockPos pos, boolean front, List<String> lines) {
        return new ServerboundSignUpdatePacket(pos, List.copyOf(lines), front ? SignTextSlot.FRONT : SignTextSlot.BACK);
    }

    @Override
    public Packet<ServerGamePacketListener> acceptTeleport(int id, double x, double y, double z, float yaw, float pitch) {
        return new ServerboundAcceptTeleportationPacket(id, x, y, z, yaw, pitch);
    }

    @Override
    public Packet<ServerGamePacketListener> chat(String message, Instant time, long salt) {
        LastSeenMessages.Update none = new LastSeenMessages.Update(0, new BitSet(), LastSeenMessages.Update.IGNORE_CHECKSUM);
        return new ServerboundChatPacket(message, time, salt, Optional.empty(), none);
    }

    @Override
    public List<Packet<ServerGamePacketListener>> swing(boolean mainHand) {
        return mainHand ? List.of(ServerboundPunchPacket.INSTANCE) : List.of();
    }
}
