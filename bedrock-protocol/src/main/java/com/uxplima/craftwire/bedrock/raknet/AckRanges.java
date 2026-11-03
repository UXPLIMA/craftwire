package com.uxplima.craftwire.bedrock.raknet;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ACK and NACK bodies: a big-endian record count, then records that are a single datagram sequence number
 * (type 1) or an inclusive range (type 0), each number a little-endian uint24.
 */
final class AckRanges {
    private static final int RANGE = 0;
    private static final int SINGLE = 1;
    /** Bounds what a malicious ACK can make the client iterate. */
    private static final int MAX_NUMBERS = 8192;

    private AckRanges() {}

    /** Writes as many of `numbers` (sorted here) as fit in `room` bytes; returns how many were written. */
    static int write(ByteBuf b, List<Integer> numbers, int room) {
        Collections.sort(numbers);
        int countAt = b.writerIndex();
        b.writeShort(0);
        int records = 0;
        int written = 0;
        int i = 0;
        while (i < numbers.size() && b.writerIndex() - countAt + 7 <= room) {
            int first = numbers.get(i);
            int last = first;
            int j = i + 1;
            while (j < numbers.size() && numbers.get(j) == last + 1) last = numbers.get(j++);
            if (first == last) {
                b.writeByte(SINGLE);
                b.writeMediumLE(first);
            } else {
                b.writeByte(RANGE);
                b.writeMediumLE(first);
                b.writeMediumLE(last);
            }
            records++;
            written += j - i;
            i = j;
        }
        b.setShort(countAt, records);
        return written;
    }

    static List<Integer> read(ByteBuf b) {
        List<Integer> out = new ArrayList<>();
        int records = b.readUnsignedShort();
        for (int r = 0; r < records; r++) {
            int type = b.readUnsignedByte();
            int first = b.readUnsignedMediumLE();
            int last = type == RANGE ? b.readUnsignedMediumLE() : first;
            if (out.size() + (last - first) > MAX_NUMBERS) throw new IllegalArgumentException("acknowledgement too large");
            for (int n = first; n <= last; n++) out.add(n);
        }
        return out;
    }
}
