package com.uxplima.craftwire.bedrock.codec;

import static org.junit.jupiter.api.Assertions.*;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Batches as the client writes them and as a server's batches are read back, through each connection stage. */
class BatchCodecTest {
    private static ByteBuf packet(int size, int seed) {
        byte[] b = new byte[size];
        new Random(seed).nextBytes(b);
        return Unpooled.wrappedBuffer(b);
    }

    private static List<byte[]> bytes(List<ByteBuf> packets) {
        List<byte[]> out = new ArrayList<>();
        for (ByteBuf p : packets) {
            byte[] b = new byte[p.readableBytes()];
            p.getBytes(p.readerIndex(), b);
            out.add(b);
        }
        return out;
    }

    private static void assertSamePackets(List<ByteBuf> sent, List<ByteBuf> received) {
        List<byte[]> a = bytes(sent);
        List<byte[]> b = bytes(received);
        assertEquals(a.size(), b.size());
        for (int i = 0; i < a.size(); i++) assertArrayEquals(a.get(i), b.get(i), "packet " + i);
    }

    private static List<ByteBuf> somePackets() {
        return List.of(packet(1, 1), packet(300, 2), packet(5000, 3), packet(127, 4), packet(128, 5));
    }

    @Test
    void beforeNetworkSettingsABatchIsHeaderAndLengthPrefixedPackets() {
        ByteBuf batch = new BatchCodec().encode(List.of(Unpooled.wrappedBuffer(new byte[] {(byte) 0xc1, 0x01})));
        byte[] wire = new byte[batch.readableBytes()];
        batch.readBytes(wire);
        // RequestNetworkSettings goes out uncompressed: 0xfe, varint length 2, the packet
        assertArrayEquals(new byte[] {(byte) 0xfe, 2, (byte) 0xc1, 0x01}, wire);
    }

    @ParameterizedTest
    @EnumSource(BatchCodec.Compression.class)
    void packetsSurviveEachCompression(BatchCodec.Compression compression) {
        BatchCodec client = new BatchCodec();
        BatchCodec server = new BatchCodec();
        client.compression(compression, 0);
        server.compression(compression, 0);
        List<ByteBuf> sent = somePackets();
        ByteBuf batch = client.encode(sent);
        assertEquals(compression.id, batch.getUnsignedByte(1));
        assertSamePackets(sent, server.decode(batch));
    }

    @Test
    void batchesUnderTheThresholdGoOutUncompressed() {
        BatchCodec client = new BatchCodec();
        client.compression(BatchCodec.Compression.DEFLATE, 256);
        assertEquals(0xff, client.encode(List.of(packet(10, 1))).getUnsignedByte(1));
        assertEquals(0, client.encode(List.of(packet(400, 1))).getUnsignedByte(1));
    }

    @Test
    void readsAServerBatchWhoseAlgorithmDiffersFromOurs() {
        // Servers compress with what they announced, but may send small batches uncompressed (0xff) at any time.
        BatchCodec server = new BatchCodec();
        server.compression(BatchCodec.Compression.SNAPPY, 0);
        BatchCodec client = new BatchCodec();
        client.compression(BatchCodec.Compression.DEFLATE, 100_000);
        List<ByteBuf> sent = somePackets();
        assertSamePackets(sent, client.decode(server.encode(sent)));
    }

    @Test
    void encryptedBatchesDecodeInOrder() {
        byte[] key = new byte[32];
        new Random(7).nextBytes(key);
        BatchCodec client = new BatchCodec();
        BatchCodec server = new BatchCodec();
        for (BatchCodec c : List.of(client, server)) {
            c.compression(BatchCodec.Compression.DEFLATE, 0);
            c.encryption(key);
        }
        for (int i = 0; i < 5; i++) {
            List<ByteBuf> sent = List.of(packet(50 + i * 400, i));
            assertSamePackets(sent, server.decode(client.encode(sent)));
        }
    }

    @Test
    void anEncryptedBatchThatWasSkippedBreaksTheStream() {
        byte[] key = new byte[32];
        BatchCodec client = new BatchCodec();
        BatchCodec server = new BatchCodec();
        client.encryption(key);
        server.encryption(key);
        client.encode(List.of(packet(20, 1)));
        ByteBuf second = client.encode(List.of(packet(20, 2)));
        assertThrows(SecurityException.class, () -> server.decode(second));
    }

    @Test
    void refusesADeflateBombOverSixteenMebibytes() {
        byte[] zeros = new byte[17 * 1024 * 1024];
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
        d.setInput(zeros);
        d.finish();
        byte[] buf = new byte[1 << 20];
        int n = d.deflate(buf);
        d.end();
        ByteBuf batch = Unpooled.buffer();
        batch.writeByte(0xfe);
        batch.writeByte(0);
        batch.writeBytes(buf, 0, n);
        BatchCodec client = new BatchCodec();
        client.compression(BatchCodec.Compression.DEFLATE, 0);
        assertThrows(BedrockCodecException.class, () -> client.decode(batch));
    }

    @Test
    void refusesAPacketLengthPastTheEndOfTheBatch() {
        ByteBuf batch = Unpooled.wrappedBuffer(new byte[] {(byte) 0xfe, 10, 1, 2, 3});
        assertThrows(BedrockCodecException.class, () -> new BatchCodec().decode(batch));
    }

    @Test
    void refusesAnUnknownCompressionAlgorithm() {
        BatchCodec client = new BatchCodec();
        client.compression(BatchCodec.Compression.DEFLATE, 0);
        ByteBuf batch = Unpooled.wrappedBuffer(new byte[] {(byte) 0xfe, 7, 1, 0});
        assertThrows(BedrockCodecException.class, () -> client.decode(batch));
    }

    @Test
    void refusesSomethingThatIsNotABatch() {
        assertThrows(BedrockCodecException.class, () -> new BatchCodec().decode(Unpooled.wrappedBuffer(new byte[] {0x01, 0})));
        assertThrows(BedrockCodecException.class, () -> new BatchCodec().decode(Unpooled.buffer()));
    }
}
