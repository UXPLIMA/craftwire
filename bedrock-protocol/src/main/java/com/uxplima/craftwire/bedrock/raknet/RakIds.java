package com.uxplima.craftwire.bedrock.raknet;

/** RakNet message ids, flags and constants as Bedrock uses them (RakNet protocol version 11). */
final class RakIds {
    static final int PROTOCOL_VERSION = 11;

    static final int CONNECTED_PING = 0x00;
    static final int UNCONNECTED_PING = 0x01;
    static final int CONNECTED_PONG = 0x03;
    static final int DETECT_LOST_CONNECTIONS = 0x04;
    static final int OPEN_CONNECTION_REQUEST_1 = 0x05;
    static final int OPEN_CONNECTION_REPLY_1 = 0x06;
    static final int OPEN_CONNECTION_REQUEST_2 = 0x07;
    static final int OPEN_CONNECTION_REPLY_2 = 0x08;
    static final int CONNECTION_REQUEST = 0x09;
    static final int CONNECTION_REQUEST_ACCEPTED = 0x10;
    static final int NEW_INCOMING_CONNECTION = 0x13;
    static final int DISCONNECT_NOTIFICATION = 0x15;
    static final int INCOMPATIBLE_PROTOCOL_VERSION = 0x19;
    static final int UNCONNECTED_PONG = 0x1c;
    /** The Bedrock game packet batch, carried as a RakNet user message. */
    static final int GAME_PACKET = 0xfe;

    static final int FLAG_DATAGRAM = 0x80;
    static final int FLAG_ACK = 0x40;
    static final int FLAG_NACK = 0x20;
    static final int FLAG_NEEDS_B_AND_AS = 0x04;

    /** The "offline message" magic every unconnected message carries. */
    static final byte[] MAGIC = {0x00, (byte) 0xff, (byte) 0xff, 0x00, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
            (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, (byte) 0xfd, 0x12, 0x34, 0x56, 0x78};

    /** MTU sizes tried in OpenConnectionRequest1, largest first, as the vanilla client discovers its MTU. */
    static final int[] MTU_SIZES = {1492, 1200, 576};
    static final int MIN_MTU = 400;
    /** IP (20) and UDP (8) headers, which RakNet's MTU includes. */
    static final int UDP_OVERHEAD = 28;

    private RakIds() {}
}
