package com.uxplima.craftwire.bedrock.codec;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Snappy against Netty's independent implementation, which (unlike ours) also writes back-references. Netty's class
 * has the same simple name as ours, hence its qualified name. Its raw encoder only handles blocks under 64 KiB (its
 * frame encoder never gives it more; above that it cannot read its own output), so the oracle stays below that.
 */
class SnappyTest {
    private static final int MAX = 16 * 1024 * 1024;

    private static byte[] nettyCompress(byte[] data) {
        ByteBuf in = Unpooled.wrappedBuffer(data);
        ByteBuf out = Unpooled.buffer();
        new io.netty.handler.codec.compression.Snappy().encode(in, out, data.length);
        byte[] bytes = new byte[out.readableBytes()];
        out.readBytes(bytes);
        return bytes;
    }

    private static byte[] nettyDecompress(byte[] data) {
        ByteBuf out = Unpooled.buffer();
        new io.netty.handler.codec.compression.Snappy().decode(Unpooled.wrappedBuffer(data), out);
        byte[] bytes = new byte[out.readableBytes()];
        out.readBytes(bytes);
        return bytes;
    }

    /** Text that compresses well: Netty's output is then mostly 1-, 2- and overlapping copies. */
    private static byte[] chatty(int size) {
        StringBuilder s = new StringBuilder();
        Random r = new Random(size);
        String[] words = {"minecraft:stone", "minecraft:air", "{\"text\":\"", "&a", "Welcome ", "aaaaaaaaaaaaaaaa", "\n"};
        while (s.length() < size) s.append(words[r.nextInt(words.length)]);
        return s.substring(0, size).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] noise(int size) {
        byte[] b = new byte[size];
        new Random(size * 31L).nextBytes(b);
        return b;
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 17, 4096, 65_000})
    void decodesWhatAnotherEncoderWrote(int size) {
        for (byte[] data : new byte[][] {chatty(size), noise(size)}) {
            byte[] compressed = nettyCompress(data);
            assertArrayEquals(data, Snappy.decompress(compressed, MAX));
        }
    }

    /** Lengths around the literal tag's boundaries: inline up to 60, then 1, 2 extra length bytes, 64 KiB chunks. */
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 59, 60, 61, 255, 256, 257, 65_535, 65_536, 65_537, 200_000})
    void whatWeWriteOtherDecodersRead(int size) {
        byte[] data = noise(size);
        byte[] compressed = Snappy.compress(data);
        assertArrayEquals(data, nettyDecompress(compressed));
        assertArrayEquals(data, Snappy.decompress(compressed, MAX));
    }

    @Test
    void readsEveryCopyElement() {
        // length 32; literal "abcd"; copy1 (len 4, offset 4); copy2 (len 8, offset 8); copy4 (len 16, offset 16)
        byte[] stream = {32, (3 << 2), 'a', 'b', 'c', 'd', 0x01, 4, (7 << 2) | 2, 8, 0, (15 << 2) | 3, 16, 0, 0, 0};
        assertEquals("abcd".repeat(8), new String(Snappy.decompress(stream, MAX), StandardCharsets.US_ASCII));
    }

    @Test
    void overlappingCopyRepeatsTheLastBytes() {
        // length 8; literal "ab"; copy with 1-byte offset (tag type 1): length 6 = ((tag >> 2) & 7) + 4, offset 2
        byte[] stream = {8, 1 << 2, 'a', 'b', (byte) (((6 - 4) << 2) | 1), 2};
        assertEquals("abababab", new String(Snappy.decompress(stream, MAX), StandardCharsets.US_ASCII));
    }

    @Test
    void refusesALengthOverTheLimitBeforeAllocating() {
        byte[] compressed = Snappy.compress(new byte[1000]);
        assertThrows(BedrockCodecException.class, () -> Snappy.decompress(compressed, 999));
    }

    @Test
    void refusesACopyFromBeforeTheStart() {
        // length 4; copy with offset 1 while nothing has been written yet
        byte[] stream = {4, (byte) 1, 1};
        assertThrows(BedrockCodecException.class, () -> Snappy.decompress(stream, MAX));
    }

    /** A server (or a broken proxy) can cut a batch anywhere; every cut must be reported as corrupt data. */
    @Test
    void everyTruncationIsReportedAsCorrupt() {
        byte[] compressed = nettyCompress(chatty(3000));
        for (int cut = 0; cut < compressed.length; cut++) {
            byte[] part = Arrays.copyOf(compressed, cut);
            assertThrows(BedrockCodecException.class, () -> Snappy.decompress(part, MAX), "cut at " + cut);
        }
    }
}
