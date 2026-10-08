package com.uxplima.craftwire.fabric.video;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class FrameWriterTest {
    static ByteBuffer frame(int value) {
        ByteBuffer b = ByteBuffer.allocateDirect(4);
        for (int i = 0; i < 4; i++) b.put(i, (byte) value);
        return b;
    }

    static String frames(byte[] out) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < out.length; i += 4) s.append(out[i]);
        return s.toString();
    }

    @Test
    void framesAreWrittenInOrderAsOftenAsTheyRepeat() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameWriter w = new FrameWriter(out, 4, 8);
        assertTrue(w.submit(frame(1), 1));
        assertTrue(w.submit(frame(2), 3));
        assertTrue(w.submit(frame(3), 1));
        w.finish(2);
        assertEquals("1222333", frames(out.toByteArray()));
        assertEquals(7, w.written());
        assertEquals(0, w.dropped());
    }

    @Test
    void aFullQueueDropsFramesAndThePreviousOneFillsTheirPlace() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        ByteArrayOutputStream sink = new ByteArrayOutputStream();
        OutputStream slow = new OutputStream() {
            @Override public void write(int b) {
                sink.write(b);
            }

            @Override public void write(byte[] b, int off, int len) throws IOException {
                try {
                    release.await(5, TimeUnit.SECONDS);   // ffmpeg is busy
                } catch (InterruptedException e) {
                    throw new IOException(e);
                }
                sink.write(b, off, len);
            }
        };
        FrameWriter w = new FrameWriter(slow, 4, 2);
        assertTrue(w.submit(frame(1), 1));
        Thread.sleep(100);   // the writer took frame 1 and is stuck writing it
        assertTrue(w.submit(frame(2), 1));
        assertTrue(w.submit(frame(3), 1));
        assertFalse(w.submit(frame(4), 2), "queue full: dropped");
        assertEquals(2, w.dropped());
        release.countDown();
        w.submit(frame(5), 1);   // taken or dropped, depending on how fast the writer catches up
        w.finish(0);
        String written = frames(sink.toByteArray());
        assertEquals(written.length(), w.written());
        assertTrue(written.startsWith("12333"), "the two dropped frames are frame 3 repeated, got " + written);
    }

    @Test
    void droppedFramesAtTheEndRepeatTheLastFrame() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FrameWriter w = new FrameWriter(out, 4, 8);
        w.submit(frame(7), 1);
        w.dropFrames(2);   // a readback that never came back
        w.finish(1);
        assertEquals("7777", frames(out.toByteArray()));
    }

    @Test
    void aWriteFailureIsKeptForTheCaller() throws Exception {
        OutputStream broken = new OutputStream() {
            @Override public void write(int b) throws IOException {
                throw new IOException("pipe is closed");
            }
        };
        FrameWriter w = new FrameWriter(broken, 4, 8);
        w.submit(frame(1), 1);
        for (int i = 0; i < 50 && w.failure() == null; i++) Thread.sleep(10);
        assertNotNull(w.failure());
        assertFalse(w.submit(frame(2), 1), "nothing more is accepted after a failure");
        assertThrows(IOException.class, () -> w.finish(0));
    }
}
