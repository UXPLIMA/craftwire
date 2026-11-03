package com.uxplima.craftwire.bedrock.raknet;

import io.netty.buffer.ByteBuf;

/**
 * One RakNet encapsulated packet inside a datagram: reliability (3 bits), the split flag, the length in bits, the
 * reliable message index, the sequence and order indexes with their channel, the split header, then the payload.
 * The three-byte indexes are little-endian. Event loop thread.
 */
final class Frame {
    static final int UNRELIABLE = 0;
    static final int UNRELIABLE_SEQUENCED = 1;
    static final int RELIABLE = 2;
    static final int RELIABLE_ORDERED = 3;
    static final int RELIABLE_SEQUENCED = 4;
    private static final int SPLIT_FLAG = 0x10;
    /** Datagram header (flags, sequence) plus the largest frame header without the split part. */
    static final int OVERHEAD = 1 + 3 + 1 + 2 + 3 + 3 + 1;
    static final int SPLIT_OVERHEAD = 4 + 2 + 4;

    int reliability;
    int messageIndex;
    int sequenceIndex;
    int orderIndex;
    boolean split;
    int splitCount;
    int splitId;
    int splitIndex;
    byte[] payload;

    static boolean reliable(int r) {
        return r == RELIABLE || r == RELIABLE_ORDERED || r == RELIABLE_SEQUENCED;
    }

    static boolean sequenced(int r) {
        return r == UNRELIABLE_SEQUENCED || r == RELIABLE_SEQUENCED;
    }

    static boolean ordered(int r) {
        return sequenced(r) || r == RELIABLE_ORDERED;
    }

    void write(ByteBuf b) {
        b.writeByte((reliability << 5) | (split ? SPLIT_FLAG : 0));
        b.writeShort(payload.length << 3);
        if (reliable(reliability)) b.writeMediumLE(messageIndex);
        if (sequenced(reliability)) b.writeMediumLE(sequenceIndex);
        if (ordered(reliability)) {
            b.writeMediumLE(orderIndex);
            b.writeByte(0);
        }
        if (split) {
            b.writeInt(splitCount);
            b.writeShort(splitId);
            b.writeInt(splitIndex);
        }
        b.writeBytes(payload);
    }

    static Frame read(ByteBuf b) {
        Frame f = new Frame();
        int header = b.readUnsignedByte();
        f.reliability = (header & 0xe0) >>> 5;
        f.split = (header & SPLIT_FLAG) != 0;
        int length = (b.readUnsignedShort() + 7) >>> 3;
        if (length == 0) throw new IllegalArgumentException("frame of length 0");
        if (reliable(f.reliability)) f.messageIndex = b.readUnsignedMediumLE();
        if (sequenced(f.reliability)) f.sequenceIndex = b.readUnsignedMediumLE();
        if (ordered(f.reliability)) {
            f.orderIndex = b.readUnsignedMediumLE();
            b.skipBytes(1);
        }
        if (f.split) {
            f.splitCount = b.readInt();
            f.splitId = b.readUnsignedShort();
            f.splitIndex = b.readInt();
        }
        if (length > b.readableBytes()) throw new IllegalArgumentException("frame longer than its datagram");
        f.payload = new byte[length];
        b.readBytes(f.payload);
        return f;
    }

    int size() {
        return OVERHEAD + (split ? SPLIT_OVERHEAD : 0) + payload.length;
    }
}
