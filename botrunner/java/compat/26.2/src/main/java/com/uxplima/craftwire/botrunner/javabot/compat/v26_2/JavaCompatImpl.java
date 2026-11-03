package com.uxplima.craftwire.botrunner.javabot.compat.v26_2;

import com.uxplima.craftwire.botrunner.javabot.JavaCompat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
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
import net.minecraft.network.protocol.game.ServerboundSignUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.network.protocol.game.VecDeltaCodec;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.Vec3;

/** The Java bot's packets in Minecraft 26.2 shapes. */
public final class JavaCompatImpl implements JavaCompat {
    @Override
    public int chunkX(ClientboundLevelChunkWithLightPacket p) {
        return p.getX();
    }

    @Override
    public int chunkZ(ClientboundLevelChunkWithLightPacket p) {
        return p.getZ();
    }

    @Override
    public ClientboundLevelChunkPacketData chunkData(ClientboundLevelChunkWithLightPacket p) {
        return p.getChunkData();
    }

    @Override
    public List<Integer> removedEntities(ClientboundRemoveEntitiesPacket p) {
        return new ArrayList<>(p.getEntityIds());
    }

    @Override
    public double[] movedTo(ClientboundMoveEntityPacket p, double x, double y, double z) {
        if (!p.hasPosition()) return null;
        VecDeltaCodec codec = new VecDeltaCodec();
        codec.setBase(new Vec3(x, y, z));
        Vec3 v = codec.decode(p.getXa(), p.getYa(), p.getZa());
        return new double[] {v.x, v.y, v.z};
    }

    @Override
    public double[] syncPosition(ClientboundEntityPositionSyncPacket p) {
        Vec3 v = p.values().position();
        return new double[] {v.x, v.y, v.z};
    }

    @Override
    public SignEditor signEditor(ClientboundOpenSignEditorPacket p) {
        return new SignEditor(p.getPos(), p.isFrontText());
    }

    @Override
    public Component chatContent(ClientboundPlayerChatPacket p) {
        return p.unsignedContent() != null ? p.unsignedContent() : Component.literal(p.body().content());
    }

    @Override
    public Packet<ServerGamePacketListener> signUpdate(BlockPos pos, boolean front, List<String> lines) {
        return new ServerboundSignUpdatePacket(pos, front, lines.get(0), lines.get(1), lines.get(2), lines.get(3));
    }

    @Override
    public Packet<ServerGamePacketListener> acceptTeleport(int id, double x, double y, double z, float yaw, float pitch) {
        return new ServerboundAcceptTeleportationPacket(id);
    }

    @Override
    public Packet<ServerGamePacketListener> chat(String message, Instant time, long salt) {
        LastSeenMessages.Update seen = new LastSeenMessages.Update(0, new BitSet(), LastSeenMessages.Update.IGNORE_CHECKSUM);
        return new ServerboundChatPacket(message, time, salt, null, seen);
    }

    @Override
    public List<Packet<ServerGamePacketListener>> swing(boolean mainHand) {
        return List.of(new ServerboundSwingPacket(mainHand ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND));
    }
}
