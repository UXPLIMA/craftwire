package com.uxplima.craftwire.bedrock.packet;

import com.uxplima.craftwire.bedrock.codec.BedrockCodecException;
import com.uxplima.craftwire.bedrock.codec.Bin;
import io.netty.buffer.ByteBuf;

/**
 * Turns one packet of a batch into its record. The header is a varuint: packet id in the low 10 bits, then sender and
 * target sub-client ids (always 0 for a bot). Packets the bot does not use decode to null and are dropped.
 */
public final class PacketDecoder {
    private PacketDecoder() {}

    /** The packet id from a packet's header, without consuming it. */
    public static int peekId(ByteBuf packet) {
        int at = packet.readerIndex();
        int id = Bin.readVarUInt(packet) & 0x3ff;
        packet.readerIndex(at);
        return id;
    }

    public static Clientbound decode(ByteBuf packet) {
        int id = Bin.readVarUInt(packet) & 0x3ff;
        try {
            return switch (id) {
                case PacketIds.START_GAME -> StartGame.read(packet);
                case PacketIds.PLAYER_LIST -> PlayerListPacket.read(packet);
                default -> any(id, packet);
            };
        } catch (IndexOutOfBoundsException e) {
            throw new BedrockCodecException("packet " + id + " is shorter than protocol " + PacketIds.PROTOCOL + " says", e);
        }
    }

    private static Clientbound any(int id, ByteBuf b) {
        Clientbound out = LoginPackets.decode(id, b);
        if (out == null) out = UiPackets.decode(id, b);
        if (out == null) out = EntityPackets.decode(id, b);
        if (out == null) out = WorldPackets.decode(id, b);
        if (out == null) out = ItemPackets.decode(id, b);
        return out;
    }
}
