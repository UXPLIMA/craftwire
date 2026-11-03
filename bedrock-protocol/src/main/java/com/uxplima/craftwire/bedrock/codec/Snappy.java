package com.uxplima.craftwire.bedrock.codec;

import java.io.ByteArrayOutputStream;

/**
 * Snappy block format, as servers may pick it in NetworkSettings (compression algorithm 1). Decompression handles
 * every element type; compression writes the input as literals only, which every decoder accepts and which costs
 * nothing to produce (a bot's own packets are small, and the server picks the algorithm, not the bot).
 */
public final class Snappy {
    private static final int MAX_LITERAL = 60;

    private Snappy() {}

    public static byte[] decompress(byte[] in, int maxLength) {
        int[] pos = {0};
        long length = varint(in, pos);
        if (length < 0 || length > maxLength) throw new BedrockCodecException("snappy length " + length + " exceeds " + maxLength);
        byte[] out = new byte[(int) length];
        int o = 0;
        int p = pos[0];
        while (p < in.length) {
            int tag = in[p++] & 0xff;
            switch (tag & 3) {
                case 0 -> {
                    int n = tag >>> 2;
                    if (n >= MAX_LITERAL) {
                        int bytes = n - 59;
                        n = 0;
                        for (int i = 0; i < bytes; i++) n |= (in[p++] & 0xff) << (8 * i);
                    }
                    n++;
                    check(o + n <= out.length && p + n <= in.length);
                    System.arraycopy(in, p, out, o, n);
                    p += n;
                    o += n;
                }
                case 1 -> {
                    int n = ((tag >>> 2) & 7) + 4;
                    int offset = ((tag & 0xe0) << 3) | (in[p++] & 0xff);
                    o = copy(out, o, offset, n);
                }
                case 2 -> {
                    int n = (tag >>> 2) + 1;
                    int offset = (in[p] & 0xff) | (in[p + 1] & 0xff) << 8;
                    p += 2;
                    o = copy(out, o, offset, n);
                }
                default -> {
                    int n = (tag >>> 2) + 1;
                    int offset = (in[p] & 0xff) | (in[p + 1] & 0xff) << 8 | (in[p + 2] & 0xff) << 16 | (in[p + 3] & 0xff) << 24;
                    p += 4;
                    o = copy(out, o, offset, n);
                }
            }
        }
        check(o == out.length);
        return out;
    }

    /** Overlapping copies are allowed (a run repeating its last bytes), so this copies byte by byte. */
    private static int copy(byte[] out, int o, int offset, int n) {
        check(offset > 0 && offset <= o && o + n <= out.length);
        for (int i = 0; i < n; i++) out[o + i] = out[o - offset + i];
        return o + n;
    }

    public static byte[] compress(byte[] in) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length + in.length / 60 + 16);
        long v = in.length;
        while ((v & ~0x7fL) != 0) {
            out.write((int) ((v & 0x7f) | 0x80));
            v >>>= 7;
        }
        out.write((int) v);
        int p = 0;
        while (p < in.length) {
            int n = Math.min(65536, in.length - p);
            int len = n - 1;
            if (len < MAX_LITERAL) {
                out.write(len << 2);
            } else if (len < 256) {
                out.write(60 << 2);
                out.write(len);
            } else {
                out.write(61 << 2);
                out.write(len & 0xff);
                out.write(len >>> 8);
            }
            out.write(in, p, n);
            p += n;
        }
        return out.toByteArray();
    }

    private static long varint(byte[] in, int[] pos) {
        long v = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            check(pos[0] < in.length);
            int x = in[pos[0]++] & 0xff;
            v |= (long) (x & 0x7f) << shift;
            if ((x & 0x80) == 0) return v;
        }
        throw new BedrockCodecException("bad snappy length");
    }

    private static void check(boolean ok) {
        if (!ok) throw new BedrockCodecException("corrupt snappy data");
    }
}
