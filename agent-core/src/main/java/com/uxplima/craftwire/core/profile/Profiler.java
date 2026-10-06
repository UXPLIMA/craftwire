package com.uxplima.craftwire.core.profile;

import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingStream;

/**
 * Samples one thread with Flight Recorder's execution sampler (no safepoint bias, no stop of the thread) and sums
 * the samples into a {@link ProfileReport}.
 */
public final class Profiler {
    private Profiler() {}

    /** Wall-clock nanoseconds, the clock of the samples' timestamps and of {@link Session#tick}. */
    public static long now() {
        return nanos(Instant.now());
    }

    static long nanos(Instant i) {
        return i.getEpochSecond() * 1_000_000_000L + i.getNano();
    }

    public static Session start(String thread, int intervalMs, OwnerIndex index) {
        return new Session(thread, intervalMs, index);
    }

    /** Java frames of a stack trace, innermost first. */
    static List<Frame> frames(RecordedStackTrace trace, int max) {
        List<Frame> out = new ArrayList<>();
        if (trace == null) return out;
        for (RecordedFrame f : trace.getFrames()) {
            if (!f.isJavaFrame() || f.getMethod() == null) continue;
            out.add(new Frame(f.getMethod().getType().getName(), f.getMethod().getName(), f.getMethod().getDescriptor(), f.getLineNumber()));
            if (out.size() == max) break;
        }
        return out;
    }

    public static final class Session {
        private final String thread;
        private final int intervalMs;
        private final ProfileReport report;
        private final RecordingStream stream = new RecordingStream();
        private final long started = System.nanoTime();

        private Session(String thread, int intervalMs, OwnerIndex index) {
            this.thread = thread;
            this.intervalMs = intervalMs;
            this.report = new ProfileReport(index);
            stream.setSettings(Map.of(
                    "jdk.ExecutionSample#enabled", "true",
                    "jdk.ExecutionSample#period", intervalMs + " ms"));
            stream.onEvent("jdk.ExecutionSample", this::sample);
            stream.startAsync();
        }

        private void sample(RecordedEvent e) {
            RecordedThread t = e.getThread("sampledThread");
            if (t == null || !thread.equals(t.getJavaName())) return;
            RecordedStackTrace trace = e.getStackTrace();
            List<Frame> frames = frames(trace, Integer.MAX_VALUE);
            synchronized (report) {
                report.add(frames, nanos(e.getStartTime()), trace != null && trace.isTruncated());
            }
        }

        /** A finished game tick (from any thread): its number, end ({@link #now()}) and duration. */
        public void tick(long number, long endNanos, long durationNanos) {
            synchronized (report) {
                report.tick(number, endNanos, durationNanos);
            }
        }

        /** Stops sampling, waits for the samples still in flight, and returns the report. */
        public JsonObject finish(int top) {
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            try {
                stream.stop();
            } catch (IllegalStateException ignored) {
                // already stopped
            } finally {
                stream.close();
            }
            JsonObject o = new JsonObject();
            o.addProperty("thread", thread);
            o.addProperty("durationMs", elapsed);
            o.addProperty("intervalMs", intervalMs);
            synchronized (report) {
                report.json(top).entrySet().forEach(e -> o.add(e.getKey(), e.getValue()));
            }
            return o;
        }

        /** Stops without a report (the agent is shutting down). */
        public void cancel() {
            stream.close();
        }
    }
}
