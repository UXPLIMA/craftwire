package com.uxplima.craftwire.bedrock.codec;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The protocol's primitive encodings, against byte sequences from the protocol documentation. */
class BinTest {
    private static String hex(ByteBuf b) {
        return ByteBufUtil.hexDump(b);
    }

    @Test
    void varintsUseSevenBitGroupsLowFirst() {
        ByteBuf b = Unpooled.buffer();
        Bin.writeVarUInt(b, 300);
        Bin.writeVarUInt(b, -1);
        assertEquals("ac02" + "ffffffff0f", hex(b));
        assertEquals(300, Bin.readVarUInt(b));
        assertEquals(-1, Bin.readVarUInt(b));
    }

    @Test
    void signedVarintsAreZigZag() {
        ByteBuf b = Unpooled.buffer();
        for (int v : new int[] {0, -1, 1, -2, Integer.MAX_VALUE, Integer.MIN_VALUE}) Bin.writeVarInt(b, v);
        assertEquals("00" + "01" + "02" + "03" + "feffffff0f" + "ffffffff0f", hex(b.copy()));
        for (int v : new int[] {0, -1, 1, -2, Integer.MAX_VALUE, Integer.MIN_VALUE}) assertEquals(v, Bin.readVarInt(b));
    }

    @Test
    void longVarintsRoundTripAtTheirLimits() {
        ByteBuf b = Unpooled.buffer();
        long[] values = {0, -1, 1, Long.MAX_VALUE, Long.MIN_VALUE, 1L << 35};
        for (long v : values) Bin.writeVarLong(b, v);
        Bin.writeVarULong(b, -1L);
        for (long v : values) assertEquals(v, Bin.readVarLong(b));
        assertEquals(-1L, Bin.readVarULong(b));
        assertFalse(b.isReadable());
    }

    @Test
    void aVarintThatNeverEndsIsRefused() {
        ByteBuf b = Unpooled.wrappedBuffer(new byte[] {(byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 1});
        assertThrows(BedrockCodecException.class, () -> Bin.readVarUInt(b));
    }

    @Test
    void aStringLengthPastTheEndIsRefusedBeforeReading() {
        ByteBuf b = Unpooled.buffer();
        Bin.writeVarUInt(b, 1000);
        b.writeBytes(new byte[] {'h', 'i'});
        assertThrows(BedrockCodecException.class, () -> Bin.readString(b));
    }

    @Test
    void stringsAreUtf8WithAByteLength() {
        ByteBuf b = Unpooled.buffer();
        Bin.writeString(b, "çay");
        assertEquals("04" + "c3a76179", hex(b.copy()));
        assertEquals("çay", Bin.readString(b));
    }

    @Test
    void uuidsAreTwoLittleEndianHalvesMostSignificantFirst() {
        ByteBuf b = Unpooled.buffer();
        Bin.writeUuid(b, UUID.fromString("00010203-0405-0607-0809-0a0b0c0d0e0f"));
        assertEquals("0706050403020100" + "0f0e0d0c0b0a0908", hex(b.copy()));
        assertEquals(UUID.fromString("00010203-0405-0607-0809-0a0b0c0d0e0f"), Bin.readUuid(b));
    }
}
