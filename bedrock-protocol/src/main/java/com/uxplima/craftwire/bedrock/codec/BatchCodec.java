package com.uxplima.craftwire.bedrock.codec;

import com.uxplima.craftwire.bedrock.crypto.SessionCipher;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * The game-packet batch inside a RakNet message: header 0xfe, then (once NetworkSettings arrived) one byte naming
 * the compression of the rest (0 raw deflate, 1 snappy, 0xff none; batches under the threshold go uncompressed),
 * then varint-length-prefixed packets, the whole body encrypted once the handshake enabled it. One instance per
 * connection; encode and decode each keep their own cipher state.
 */
public final class BatchCodec {
    public static final int HEADER = 0xfe;
    private static final int ALGORITHM_NONE = 0xff;
    /** The vanilla client refuses batches that decompress to more than this. */
    private static final int MAX_DECOMPRESSED = 16 * 1024 * 1024;

    public enum Compression {
        DEFLATE(0), SNAPPY(1), NONE(ALGORITHM_NONE);

        final int id;

        Compression(int id) {
            this.id = id;
        }

        /** NetworkSettings' CompressionAlgorithm field (uint16; 0xffff for none). */
        public static Compression of(int algorithm) {
            return switch (algorithm) {
                case 0 -> DEFLATE;
                case 1 -> SNAPPY;
                default -> NONE;
            };
        }
    }

    private boolean compressing;
    private Compression compression = Compression.NONE;
    private int threshold;
    private SessionCipher encrypt;
    private SessionCipher decrypt;

    /** NetworkSettings: from now on every batch carries the compression byte. */
    public void compression(Compression c, int threshold) {
        this.compressing = true;
        this.compression = c;
        this.threshold = threshold;
    }

    /** The handshake's shared key: both directions are encrypted from the next batch on. */
    public void encryption(byte[] key) {
        encrypt = SessionCipher.encrypting(key);
        decrypt = SessionCipher.decrypting(key);
    }

    /** One batch holding `packets` (each a full packet: header varint then body), ready for RakNet. */
    public ByteBuf encode(List<ByteBuf> packets) {
        ByteBuf payload = Unpooled.buffer();
        try {
            for (ByteBuf p : packets) {
                Bin.writeVarUInt(payload, p.readableBytes());
                payload.writeBytes(p, p.readerIndex(), p.readableBytes());
            }
            byte[] raw = new byte[payload.readableBytes()];
            payload.readBytes(raw);
            byte[] body = body(raw);
            if (encrypt != null) body = encrypt.seal(body);
            ByteBuf out = Unpooled.buffer(body.length + 1);
            out.writeByte(HEADER);
            out.writeBytes(body);
            return out;
        } finally {
            payload.release();
        }
    }

    private byte[] body(byte[] raw) {
        if (!compressing) return raw;
        Compression c = raw.length < threshold ? Compression.NONE : compression;
        byte[] data = switch (c) {
            case DEFLATE -> deflate(raw);
            case SNAPPY -> Snappy.compress(raw);
            case NONE -> raw;
        };
        byte[] body = new byte[data.length + 1];
        body[0] = (byte) c.id;
        System.arraycopy(data, 0, body, 1, data.length);
        return body;
    }

    /** The packets of one batch, each a buffer starting at its header varint. */
    public List<ByteBuf> decode(ByteBuf in) {
        if (in.readableBytes() == 0 || in.readUnsignedByte() != HEADER) throw new BedrockCodecException("not a game packet batch");
        byte[] body = new byte[in.readableBytes()];
        in.readBytes(body);
        if (decrypt != null) body = decrypt.open(body);
        byte[] raw = body;
        if (compressing) {
            if (body.length == 0) throw new BedrockCodecException("batch without a compression byte");
            int alg = body[0] & 0xff;
            byte[] data = new byte[body.length - 1];
            System.arraycopy(body, 1, data, 0, data.length);
            raw = switch (alg) {
                case 0 -> inflate(data);
                case 1 -> Snappy.decompress(data, MAX_DECOMPRESSED);
                case ALGORITHM_NONE -> data;
                default -> throw new BedrockCodecException("unknown compression algorithm " + alg);
            };
        }
        ByteBuf all = Unpooled.wrappedBuffer(raw);
        List<ByteBuf> out = new ArrayList<>();
        while (all.isReadable()) {
            int n = Bin.readVarUInt(all);
            if (n < 0 || n > all.readableBytes()) throw new BedrockCodecException("packet length " + n + " exceeds the batch");
            out.add(all.readSlice(n));
        }
        return out;
    }

    private static byte[] deflate(byte[] raw) {
        Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        try {
            d.setInput(raw);
            d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 2));
            byte[] buf = new byte[8192];
            while (!d.finished()) out.write(buf, 0, d.deflate(buf));
            return out.toByteArray();
        } finally {
            d.end();
        }
    }

    private static byte[] inflate(byte[] data) {
        Inflater i = new Inflater(true);
        try {
            i.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length * 3));
            byte[] buf = new byte[16384];
            while (!i.finished()) {
                int n = i.inflate(buf);
                if (n == 0 && (i.needsInput() || i.needsDictionary())) break;
                out.write(buf, 0, n);
                if (out.size() > MAX_DECOMPRESSED) throw new BedrockCodecException("batch decompresses to more than 16 MiB");
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new BedrockCodecException("corrupt deflate data", e);
        } finally {
            i.end();
        }
    }
}
