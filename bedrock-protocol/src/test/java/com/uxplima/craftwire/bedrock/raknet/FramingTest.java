package com.uxplima.craftwire.bedrock.raknet;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** RakNet frame and ACK record layouts, byte for byte as RakNet writes them. */
class FramingTest {
    private static String hex(ByteBuf b) {
        return ByteBufUtil.hexDump(b);
    }

    @Test
    void aReliableOrderedSplitFrameHasRakNetsLayout() {
        Frame f = new Frame();
        f.reliability = Frame.RELIABLE_ORDERED;
        f.messageIndex = 0x010203;
        f.orderIndex = 0x0a0b0c;
        f.split = true;
        f.splitCount = 3;
        f.splitId = 7;
        f.splitIndex = 1;
        f.payload = new byte[] {(byte) 0xfe, 0x01};
        ByteBuf b = Unpooled.buffer();
        f.write(b);
        // flags 3 << 5 | split 0x10; length in bits (BE); message index (LE24); order index (LE24) and channel;
        // split count (BE32), id (BE16), index (BE32); payload
        assertEquals("70" + "0010" + "030201" + "0c0b0a" + "00" + "00000003" + "0007" + "00000001" + "fe01", hex(b));
    }

    @ParameterizedTest
    @ValueSource(ints = {Frame.UNRELIABLE, Frame.UNRELIABLE_SEQUENCED, Frame.RELIABLE, Frame.RELIABLE_ORDERED,
            Frame.RELIABLE_SEQUENCED})
    void everyReliabilityReadsBackWhatWasWritten(int reliability) {
        Frame f = new Frame();
        f.reliability = reliability;
        f.messageIndex = 5;
        f.sequenceIndex = 6;
        f.orderIndex = 7;
        f.payload = new byte[] {1, 2, 3, 4, 5};
        ByteBuf b = Unpooled.buffer();
        f.write(b);
        f.write(b);
        for (int i = 0; i < 2; i++) {
            Frame r = Frame.read(b);
            assertEquals(reliability, r.reliability);
            assertArrayEquals(f.payload, r.payload);
            if (Frame.reliable(reliability)) assertEquals(5, r.messageIndex);
            if (Frame.sequenced(reliability)) assertEquals(6, r.sequenceIndex);
            if (Frame.ordered(reliability)) assertEquals(7, r.orderIndex);
        }
        assertFalse(b.isReadable(), "two frames in one datagram are read one after the other");
    }

    @Test
    void aFrameLongerThanItsDatagramIsRefused() {
        ByteBuf b = Unpooled.buffer();
        b.writeByte(0);
        b.writeShort(100 << 3);
        b.writeBytes(new byte[10]);
        assertThrows(IllegalArgumentException.class, () -> Frame.read(b));
    }

    @Test
    void ackRecordsJoinConsecutiveNumbersIntoRanges() {
        ByteBuf b = Unpooled.buffer();
        int written = AckRanges.write(b, new ArrayList<>(List.of(9, 1, 3, 2, 7, 10)), 1000);
        assertEquals(6, written);
        // 3 records: range 1-3, single 7, range 9-10; numbers are LE24
        assertEquals("0003" + "00" + "010000" + "030000" + "01" + "070000" + "00" + "090000" + "0a0000", hex(b));
        assertEquals(List.of(1, 2, 3, 7, 9, 10), AckRanges.read(b));
    }

    @Test
    void anAckThatDoesNotFitIsWrittenInParts() {
        List<Integer> left = new ArrayList<>();
        for (int i = 0; i < 100; i += 2) left.add(i);
        List<Integer> read = new ArrayList<>();
        while (!left.isEmpty()) {
            ByteBuf b = Unpooled.buffer();
            int n = AckRanges.write(b, left, 40);
            assertTrue(n > 0);
            assertTrue(b.readableBytes() <= 40, "record of " + b.readableBytes() + " bytes");
            left.subList(0, n).clear();
            read.addAll(AckRanges.read(b));
        }
        List<Integer> expected = new ArrayList<>();
        for (int i = 0; i < 100; i += 2) expected.add(i);
        assertEquals(expected, read);
    }

    @Test
    void anAckNamingMillionsOfNumbersIsRefused() {
        ByteBuf b = Unpooled.buffer();
        b.writeShort(1);
        b.writeByte(0);
        b.writeMediumLE(0);
        b.writeMediumLE(0xffffff);
        assertThrows(IllegalArgumentException.class, () -> AckRanges.read(b));
    }
}
