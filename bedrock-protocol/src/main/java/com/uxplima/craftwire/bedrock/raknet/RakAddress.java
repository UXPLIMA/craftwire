package com.uxplima.craftwire.bedrock.raknet;

import io.netty.buffer.ByteBuf;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;

/**
 * RakNet's address encoding: version 4 with the IPv4 bytes inverted and a big-endian port, or version 6 with the
 * address family (23, Windows' AF_INET6), port, flow info, the 16 address bytes and the scope id.
 */
final class RakAddress {
    private RakAddress() {}

    static void write(ByteBuf b, InetSocketAddress a) {
        if (a.getAddress() instanceof Inet6Address v6) {
            b.writeByte(6);
            b.writeShortLE(23);
            b.writeShort(a.getPort());
            b.writeInt(0);
            b.writeBytes(v6.getAddress());
            b.writeInt(v6.getScopeId());
        } else {
            byte[] ip = a.getAddress() instanceof Inet4Address v4 ? v4.getAddress() : new byte[] {(byte) 255, (byte) 255, (byte) 255, (byte) 255};
            b.writeByte(4);
            for (byte x : ip) b.writeByte(~x & 0xff);
            b.writeShort(a.getPort());
        }
    }

    /** The empty address RakNet fills its 20 "system addresses" with (0.0.0.0:0). */
    static void writeEmpty(ByteBuf b) {
        b.writeByte(4);
        b.writeInt(-1);
        b.writeShort(0);
    }

    /** Skips one encoded address. */
    static void skip(ByteBuf b) {
        int version = b.readUnsignedByte();
        b.skipBytes(version == 6 ? 2 + 2 + 4 + 16 + 4 : 4 + 2);
    }
}
