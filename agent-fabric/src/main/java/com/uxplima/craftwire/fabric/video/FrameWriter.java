package com.uxplima.craftwire.fabric.video;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

/**
 * Feeds raw frames to ffmpeg's stdin on its own thread, so the render thread never waits on the encoder. When the
 * encoder falls behind and the queue is full, frames are dropped and the previous frame is written in their place:
 * the video keeps its real-time length.
 */
public final class FrameWriter {
    private record Item(byte[] data, int lead, int repeats) {}

    private static final Item END = new Item(null, 0, 0);

    private final OutputStream out;
    private final int frameBytes;
    private final BlockingQueue<Item> queue;
    private final ConcurrentLinkedQueue<byte[]> pool = new ConcurrentLinkedQueue<>();
    private final Thread thread;
    private int pendingLead;   // render thread: dropped frames still to be filled by the previous frame
    private volatile long written, dropped;
    private volatile IOException failure;

    public FrameWriter(OutputStream out, int frameBytes, int maxQueued) {
        this.out = out;
        this.frameBytes = frameBytes;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, maxQueued) + 1);   // +1: room for END
        this.thread = Thread.ofPlatform().daemon().name("Craftwire video writer").start(this::run);
    }

    /** Render thread: copies the frame; it is written {@code repeats} times. False (counted as dropped) if the queue is full. */
    public boolean submit(ByteBuffer pixels, int repeats) {
        if (failure != null || queue.remainingCapacity() <= 1) {
            dropFrames(repeats);
            return false;
        }
        byte[] data = pool.poll();
        if (data == null) data = new byte[frameBytes];
        pixels.get(0, data, 0, frameBytes);
        queue.add(new Item(data, pendingLead, repeats));
        pendingLead = 0;
        return true;
    }

    /** Frames that will never arrive; the previous frame stands in for them. */
    public void dropFrames(int n) {
        dropped += n;
        pendingLead += n;
    }

    /** Repeats the last frame {@code pad} more times, closes the stream and waits for the writer thread. */
    public void finish(int pad) throws IOException {
        try {
            // put, not add: the queue may still be full of frames the encoder has not taken yet
            queue.put(new Item(null, pendingLead + pad, 0));
            pendingLead = 0;
            queue.put(END);
            thread.join(TimeUnit.MINUTES.toMillis(2));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        try {
            out.close();
        } catch (IOException e) {
            if (failure == null) failure = e;
        }
        if (failure != null) throw failure;
    }

    private void run() {
        byte[] previous = null;
        try {
            while (true) {
                Item item = queue.take();
                if (item == END) return;
                if (failure != null) continue;   // drain after a failure; finish() reports it
                try {
                    byte[] fill = previous != null ? previous : item.data();
                    for (int i = 0; i < item.lead() && fill != null; i++) write(fill);
                    if (item.data() == null) continue;
                    for (int i = 0; i < item.repeats(); i++) write(item.data());
                    if (previous != null) pool.add(previous);
                    previous = item.data();
                } catch (IOException e) {
                    failure = e;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void write(byte[] frame) throws IOException {
        out.write(frame, 0, frameBytes);
        written++;
    }

    public long written() {
        return written;
    }

    public long dropped() {
        return dropped;
    }

    public int queued() {
        return queue.size();
    }

    public IOException failure() {
        return failure;
    }
}
