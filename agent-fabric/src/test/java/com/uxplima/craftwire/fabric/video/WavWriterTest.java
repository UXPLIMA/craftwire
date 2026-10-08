package com.uxplima.craftwire.fabric.video;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WavWriterTest {
    @TempDir Path dir;

    @Test
    void writesAWavThatJavaSoundReadsBack() throws IOException, UnsupportedAudioFileException {
        Path f = dir.resolve("a.wav");
        try (WavWriter w = new WavWriter(f, 48000, 2)) {
            ByteBuffer block = ByteBuffer.allocateDirect(480 * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < 480; i++) block.putShort((short) i).putShort((short) -i);
            block.flip();
            for (int i = 0; i < 100; i++) w.write(block.duplicate());   // one second
            assertEquals(48000, w.frames());
        }
        try (AudioInputStream in = AudioSystem.getAudioInputStream(f.toFile())) {
            AudioFormat fmt = in.getFormat();
            assertEquals(48000, fmt.getSampleRate(), 0);
            assertEquals(2, fmt.getChannels());
            assertEquals(16, fmt.getSampleSizeInBits());
            assertEquals(48000, in.getFrameLength());
        }
        assertEquals(44 + 48000 * 4, Files.size(f));
    }
}
