package com.uxplima.craftwire.bedrock.raknet;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.net.InetSocketAddress;

/**
 * The offline half of a RakNet connection: OpenConnectionRequest1 padded to each MTU size in turn (four tries
 * each, every 500 ms) until the server answers, then OpenConnectionRequest2 with the agreed MTU and the server's
 * cookie until OpenConnectionReply2 arrives. Event loop thread.
 */
final class RakHandshake {
    private static final int TRIES_PER_SIZE = 4;

    private final RakClient client;
    private final InetSocketAddress server;
    private final long guid;
    private int size;
    private int tries;
    private boolean second;
    private int mtu;
    private boolean security;
    private int cookie;

    RakHandshake(RakClient client, InetSocketAddress server, long guid) {
        this.client = client;
        this.server = server;
        this.guid = guid;
    }

    void retry() {
        if (second) {
            request2();
            return;
        }
        if (tries >= TRIES_PER_SIZE && size < RakIds.MTU_SIZES.length - 1) {
            size++;
            tries = 0;
        }
        tries++;
        request1(RakIds.MTU_SIZES[size]);
    }

    /** An unconnected message from the server (its id already read). */
    void reply(int id, ByteBuf b) {
        switch (id) {
            case RakIds.OPEN_CONNECTION_REPLY_1 -> {
                if (second) return;
                b.skipBytes(RakIds.MAGIC.length);
                long serverGuid = b.readLong();
                security = b.readBoolean();
                if (security) cookie = b.readInt();
                int offered = b.readUnsignedShort();
                int tried = RakIds.MTU_SIZES[size];
                mtu = serverGuid == 0 || offered < RakIds.MIN_MTU || offered > 1500 ? tried : Math.min(offered, tried);
                second = true;
                request2();
            }
            case RakIds.OPEN_CONNECTION_REPLY_2 -> {
                if (!second) return;
                b.skipBytes(RakIds.MAGIC.length + 8);
                RakAddress.skip(b);
                int agreed = b.readUnsignedShort();
                client.open(agreed >= RakIds.MIN_MTU && agreed <= mtu ? agreed : mtu);
            }
            case RakIds.INCOMPATIBLE_PROTOCOL_VERSION -> {
                int theirs = b.readUnsignedByte();
                client.refused("the server speaks RakNet protocol " + theirs + ", this client " + RakIds.PROTOCOL_VERSION);
            }
            default -> { }
        }
    }

    private void request1(int size) {
        int length = size - RakIds.UDP_OVERHEAD;
        ByteBuf b = Unpooled.buffer(length);
        b.writeByte(RakIds.OPEN_CONNECTION_REQUEST_1);
        b.writeBytes(RakIds.MAGIC);
        b.writeByte(RakIds.PROTOCOL_VERSION);
        b.writeZero(length - b.writerIndex());
        client.raw(b);
    }

    private void request2() {
        ByteBuf b = Unpooled.buffer(64);
        b.writeByte(RakIds.OPEN_CONNECTION_REQUEST_2);
        b.writeBytes(RakIds.MAGIC);
        if (security) {
            b.writeInt(cookie);
            // "Client wrote challenge": false, as the vanilla client does.
            b.writeByte(0);
        }
        RakAddress.write(b, server);
        b.writeShort(mtu);
        b.writeLong(guid);
        client.raw(b);
    }
}
