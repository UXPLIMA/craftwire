package com.uxplima.craftwire.botrunner.javabot;

import java.time.Instant;
import java.util.List;
import java.util.ServiceLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerChatPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ServerGamePacketListener;

/**
 * The packets whose Java shape changed between the supported versions (records replacing classes, Optional fields,
 * SignTextSlot, the swing packet's removal). One implementation per version lives in javabot-compat-<mc>.jar; the
 * version's class loader carries exactly one, so ServiceLoader finds the right one.
 */
public interface JavaCompat {
    record SignEditor(BlockPos pos, boolean front) {}

    static JavaCompat load() {
        return ServiceLoader.load(JavaCompat.class, JavaCompat.class.getClassLoader()).findFirst()
                .orElseThrow(() -> new IllegalStateException("no javabot compat jar for this version"));
    }

    int chunkX(ClientboundLevelChunkWithLightPacket p);

    int chunkZ(ClientboundLevelChunkWithLightPacket p);

    ClientboundLevelChunkPacketData chunkData(ClientboundLevelChunkWithLightPacket p);

    List<Integer> removedEntities(ClientboundRemoveEntitiesPacket p);

    /** Where a relative move takes an entity that was at x, y, z; null when the packet only rotates. */
    double[] movedTo(ClientboundMoveEntityPacket p, double x, double y, double z);

    /** The absolute position x, y, z of a position sync. */
    double[] syncPosition(ClientboundEntityPositionSyncPacket p);

    SignEditor signEditor(ClientboundOpenSignEditorPacket p);

    /** What the chat line shows: the unsigned content when the server sent one, else the signed body. */
    Component chatContent(ClientboundPlayerChatPacket p);

    Packet<ServerGamePacketListener> signUpdate(BlockPos pos, boolean front, List<String> lines);

    /** Confirms a server teleport; from 26.3 the confirmation carries the position the client ended up at. */
    Packet<ServerGamePacketListener> acceptTeleport(int id, double x, double y, double z, float yaw, float pitch);

    /** An unsigned chat message (offline servers, or enforce-secure-profile=false). */
    Packet<ServerGamePacketListener> chat(String message, Instant time, long salt);

    /** The arm swing of an attack or a use; empty where the version has no packet for it. */
    List<Packet<ServerGamePacketListener>> swing(boolean mainHand);
}
