package com.uxplima.craftwire.fabric.video;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Path;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.SOFTLoopback;
import org.lwjgl.system.MemoryStack;

/**
 * Game sound for videos through OpenAL Soft's loopback device. While armed, the sound engine (re)opens on a loopback
 * device instead of a speaker (LibraryMixin); a thread of ours then does what an audio backend's mixer thread does:
 * renders the mix in 10 ms blocks, paced by the wall clock, here into a WAV file.
 */
public final class AudioTap {
    public static final int RATE = 48_000;
    public static final int CHANNELS = 2;
    private static final int BLOCK = RATE / 100;

    private static volatile boolean armed;
    private static volatile long device;

    private Thread thread;
    private volatile boolean running;
    private volatile long endNanos = Long.MAX_VALUE;
    private volatile IOException failure;
    private WavWriter wav;

    public static boolean armed() {
        return armed;
    }

    /** Render thread, before SoundEngine.reload(): the next device the engine opens is a loopback device. */
    public static void arm() {
        device = 0;
        armed = true;
    }

    public static void disarm() {
        armed = false;
        device = 0;
    }

    /** The loopback device the engine opened while armed, or 0 when it could not. */
    public static long device() {
        return device;
    }

    /** Called by LibraryMixin in place of opening a speaker. */
    public static long openLoopbackDevice() {
        if (!ALC10.alcIsExtensionPresent(0, "ALC_SOFT_loopback")) throw new IllegalStateException("OpenAL has no ALC_SOFT_loopback");
        long d = SOFTLoopback.alcLoopbackOpenDeviceSOFT((ByteBuffer) null);
        if (d == 0) throw new IllegalStateException("could not open an OpenAL loopback device");
        if (!SOFTLoopback.alcIsRenderFormatSupportedSOFT(d, RATE, SOFTLoopback.ALC_STEREO_SOFT, SOFTLoopback.ALC_SHORT_SOFT)) {
            ALC10.alcCloseDevice(d);
            throw new IllegalStateException("the loopback device cannot render 48 kHz 16-bit stereo");
        }
        device = d;
        return d;
    }

    /** The engine's context attributes plus the loopback render format (a loopback context needs it). */
    public static IntBuffer withLoopbackFormat(MemoryStack stack, IntBuffer attributes) {
        IntBuffer out = stack.callocInt(attributes.remaining() + 7);
        for (int i = attributes.position(); i + 1 < attributes.limit() && attributes.get(i) != 0; i += 2) {
            out.put(attributes.get(i)).put(attributes.get(i + 1));
        }
        out.put(SOFTLoopback.ALC_FORMAT_CHANNELS_SOFT).put(SOFTLoopback.ALC_STEREO_SOFT);
        out.put(SOFTLoopback.ALC_FORMAT_TYPE_SOFT).put(SOFTLoopback.ALC_SHORT_SOFT);
        out.put(ALC10.ALC_FREQUENCY).put(RATE);
        out.put(0);
        return out.flip();
    }

    /** Starts rendering the device's mix into {@code file}; the audio clock starts at {@code startNanos}. */
    public void start(Path file, long startNanos) throws IOException {
        long d = device;
        if (d == 0) throw new IllegalStateException("no loopback device is open");
        wav = new WavWriter(file, RATE, CHANNELS);
        running = true;
        thread = Thread.ofPlatform().daemon().name("Craftwire audio").start(() -> run(d, startNanos));
    }

    private void run(long d, long startNanos) {
        ByteBuffer buf = ByteBuffer.allocateDirect(BLOCK * CHANNELS * 2).order(ByteOrder.LITTLE_ENDIAN);
        long rendered = 0;
        try {
            while (true) {
                long now = Math.min(System.nanoTime(), endNanos);
                long due = Math.max(0, (now - startNanos) * RATE / 1_000_000_000L);
                while (rendered < due) {
                    int n = (int) Math.min(BLOCK, due - rendered);
                    buf.clear().limit(n * CHANNELS * 2);
                    SOFTLoopback.alcRenderSamplesSOFT(d, buf, n);
                    wav.write(buf);
                    rendered += n;
                }
                if (!running) return;   // stopped: rendered up to endNanos above
                Thread.sleep(5);
            }
        } catch (IOException e) {
            failure = e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Renders up to {@code end} (same clock as start), stops the thread and closes the WAV. */
    public void stop(long end) throws IOException {
        endNanos = end;
        running = false;
        if (thread != null) {
            try {
                thread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (wav != null) wav.close();
        if (failure != null) throw failure;
    }

    public long frames() {
        return wav == null ? 0 : wav.frames();
    }
}
