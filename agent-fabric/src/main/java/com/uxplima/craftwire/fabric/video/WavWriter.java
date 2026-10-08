package com.uxplima.craftwire.fabric.video;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** 16-bit PCM WAV; the header's sizes are filled in on close. */
public final class WavWriter implements AutoCloseable {
    private static final int HEADER = 44;

    private final FileChannel ch;
    private final int rate, channels;
    private long bytes;

    public WavWriter(Path file, int rate, int channels) throws IOException {
        this.ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        this.rate = rate;
        this.channels = channels;
        ch.write(header(0), 0);
        ch.position(HEADER);
    }

    /** Writes the remaining bytes of {@code samples} (interleaved, little-endian 16-bit). */
    public void write(ByteBuffer samples) throws IOException {
        while (samples.hasRemaining()) bytes += ch.write(samples);
    }

    /** Sample frames written (one sample per channel). */
    public long frames() {
        return bytes / (2L * channels);
    }

    @Override
    public void close() throws IOException {
        try {
            ch.write(header(bytes), 0);
        } finally {
            ch.close();
        }
    }

    private ByteBuffer header(long dataBytes) {
        ByteBuffer h = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt((int) Math.min(0xFFFFFFFFL, 36 + dataBytes));
        h.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) channels);
        h.putInt(rate).putInt(rate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16);
        h.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt((int) Math.min(0xFFFFFFFFL, dataBytes));
        return h.flip();
    }
}
