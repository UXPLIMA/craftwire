package com.uxplima.craftwire.bedrock.packet;

import com.uxplima.craftwire.bedrock.codec.Bin;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.List;

/** Packets the bot sends while joining: network settings, login, handshake, resource packs, chunk radius, spawn. */
public final class LoginOut {
    /** ResourcePackClientResponse answers, by their varuint value. */
    public static final int PACKS_REFUSED = 0;
    public static final int PACKS_SEND = 1;
    public static final int PACKS_HAVE_ALL = 2;
    public static final int PACKS_COMPLETED = 3;

    /** The name the protocol sends after each answer, in answer order. */
    private static final List<String> RESPONSE_NAMES = List.of("cancel", "downloading", "downloadingfinished", "resourcepackstackfinished");

    private LoginOut() {}

    /** The packet header: the id, with sender and target sub-client 0. */
    static ByteBuf start(int id) {
        ByteBuf b = Unpooled.buffer(32);
        Bin.writeVarUInt(b, id);
        return b;
    }

    /** RequestNetworkSettings: the only packet before compression; its protocol number is big-endian. */
    public static ByteBuf requestNetworkSettings(BedrockVersion v) {
        ByteBuf b = start(PacketIds.REQUEST_NETWORK_SETTINGS);
        b.writeInt(v.protocol());
        return b;
    }

    /** Login: big-endian protocol, then the connection request (LoginRequest) as a byte slice. */
    public static ByteBuf login(byte[] connectionRequest, BedrockVersion v) {
        ByteBuf b = start(PacketIds.LOGIN);
        b.writeInt(v.protocol());
        Bin.writeBytes(b, connectionRequest);
        return b;
    }

    /** ClientToServerHandshake: empty; the first packet sent encrypted. */
    public static ByteBuf clientHandshake() {
        return start(PacketIds.CLIENT_TO_SERVER_HANDSHAKE);
    }

    /** ResourcePackClientResponse: varuint answer, its name, and for PACKS_SEND the "uuid_version" ids wanted. */
    public static ByteBuf packResponse(int response, List<String> download) {
        ByteBuf b = start(PacketIds.RESOURCE_PACK_CLIENT_RESPONSE);
        Bin.writeVarUInt(b, response);
        Bin.writeString(b, RESPONSE_NAMES.get(response));
        if (response == PACKS_SEND) {
            Bin.writeVarUInt(b, download.size());
            for (String id : download) Bin.writeString(b, id);
        }
        return b;
    }

    /** ResourcePackChunkRequest: pack id ("uuid_version") and chunk index. */
    public static ByteBuf packChunkRequest(String packId, int chunk) {
        ByteBuf b = start(PacketIds.RESOURCE_PACK_CHUNK_REQUEST);
        Bin.writeString(b, packId);
        b.writeIntLE(chunk);
        return b;
    }

    /** ClientCacheStatus false: chunks arrive whole instead of as blob hashes the bot would have to cache. */
    public static ByteBuf cacheOff() {
        ByteBuf b = start(PacketIds.CLIENT_CACHE_STATUS);
        Bin.writeBool(b, false);
        return b;
    }

    public static ByteBuf requestChunkRadius(int radius) {
        ByteBuf b = start(PacketIds.REQUEST_CHUNK_RADIUS);
        Bin.writeVarInt(b, radius);
        b.writeByte(radius);
        return b;
    }

    /** SetLocalPlayerAsInitialised: tells the server the player is in the world after PlayStatus 3. */
    public static ByteBuf initialised(long runtimeId) {
        ByteBuf b = start(PacketIds.SET_LOCAL_PLAYER_AS_INITIALISED);
        Bin.writeVarULong(b, runtimeId);
        return b;
    }

    /** ServerBoundLoadingScreen: type 1 start, 2 end of the loading screen; some servers wait for the end. */
    public static ByteBuf loadingScreen(int type) {
        ByteBuf b = start(PacketIds.SERVER_BOUND_LOADING_SCREEN);
        Bin.writeVarInt(b, type);
        Bin.writeBool(b, false);
        return b;
    }

    /** NetworkStackLatency echo: the timestamp back, needsResponse false. */
    public static ByteBuf latency(long timestamp) {
        ByteBuf b = start(PacketIds.NETWORK_STACK_LATENCY);
        b.writeLongLE(timestamp);
        Bin.writeBool(b, false);
        return b;
    }
}
