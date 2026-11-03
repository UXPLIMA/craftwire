package com.uxplima.craftwire.bedrock.packet;

import com.uxplima.craftwire.bedrock.codec.Bin;
import io.netty.buffer.ByteBuf;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The packets of joining: network settings, handshake, play status, disconnect, resource packs and transfers. */
public final class LoginPackets {
    /** NetworkSettings: from now on batches are compressed with `algorithm` (0 deflate, 1 snappy, 0xffff none). */
    public record NetworkSettings(int threshold, int algorithm) implements Clientbound {}

    /** ServerToClientHandshake: the server's token carrying its key (x5u) and the salt for the session key. */
    public record ServerHandshake(String jwt) implements Clientbound {}

    /** PlayStatus: 0 login success, 1-2 outdated client or server, 3 player spawn, 7 server full, others refused. */
    public record PlayStatus(int status) implements Clientbound {}

    public record Disconnect(int reason, String message) implements Clientbound {}

    public record PackInfo(UUID id, String version, long size, String contentKey, String subPackName, String downloadUrl) {}

    public record ResourcePacksInfo(boolean required, List<PackInfo> packs) implements Clientbound {}

    public record StackPack(String id, String version) {}

    public record ResourcePackStack(boolean required, List<StackPack> packs) implements Clientbound {}

    public record ResourcePackDataInfo(String id, int chunkSize, int chunkCount, long size, byte[] hash) implements Clientbound {}

    public record ResourcePackChunkData(String id, int index, long offset, byte[] data) implements Clientbound {}

    /** Transfer: the server sends the player to another server (a proxy or a hub's server selector). */
    public record Transfer(String address, int port) implements Clientbound {}

    private LoginPackets() {}

    static Clientbound decode(int id, ByteBuf b) {
        return switch (id) {
            case PacketIds.NETWORK_SETTINGS -> new NetworkSettings(b.readUnsignedShortLE(), b.readUnsignedShortLE());
            case PacketIds.SERVER_TO_CLIENT_HANDSHAKE -> new ServerHandshake(new String(Bin.readBytes(b), StandardCharsets.UTF_8));
            case PacketIds.PLAY_STATUS -> new PlayStatus(b.readInt());
            case PacketIds.DISCONNECT -> disconnect(b);
            case PacketIds.RESOURCE_PACKS_INFO -> packsInfo(b);
            case PacketIds.RESOURCE_PACK_STACK -> stack(b);
            case PacketIds.RESOURCE_PACK_DATA_INFO -> new ResourcePackDataInfo(Bin.readString(b), b.readIntLE(), b.readIntLE(), b.readLongLE(),
                    Bin.readBytes(b));
            case PacketIds.RESOURCE_PACK_CHUNK_DATA -> new ResourcePackChunkData(Bin.readString(b), b.readIntLE(), b.readLongLE(), Bin.readBytes(b));
            case PacketIds.TRANSFER -> new Transfer(Bin.readString(b), b.readUnsignedShortLE());
            default -> null;
        };
    }

    private static Disconnect disconnect(ByteBuf b) {
        int reason = Bin.readVarInt(b);
        boolean hidden = Bin.readBool(b);
        return new Disconnect(reason, hidden ? "" : Bin.readString(b));
    }

    /** ResourcePacksInfo: flags, world template, then each pack (TexturePackInfo). */
    private static ResourcePacksInfo packsInfo(ByteBuf b) {
        boolean required = Bin.readBool(b);
        b.skipBytes(3);
        Bin.readUuid(b);
        Bin.readString(b);
        int n = Bin.readVarUInt(b);
        List<PackInfo> packs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            UUID id = Bin.readUuid(b);
            String version = Bin.readString(b);
            long size = b.readLongLE();
            String contentKey = Bin.readString(b);
            String subPack = Bin.readString(b);
            Bin.readString(b);
            b.skipBytes(3);
            String url = Bin.readString(b);
            packs.add(new PackInfo(id, version, size, contentKey, subPack, url));
        }
        return new ResourcePacksInfo(required, packs);
    }

    private static ResourcePackStack stack(ByteBuf b) {
        boolean required = Bin.readBool(b);
        int n = Bin.readVarUInt(b);
        List<StackPack> packs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String id = Bin.readString(b);
            String version = Bin.readString(b);
            Bin.readString(b);
            packs.add(new StackPack(id, version));
        }
        return new ResourcePackStack(required, packs);
    }
}
