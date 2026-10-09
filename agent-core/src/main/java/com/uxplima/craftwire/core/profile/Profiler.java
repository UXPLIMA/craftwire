package com.uxplima.craftwire.core.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.random.RandomGenerator;

/**
 * Samples the stack of the game thread every few milliseconds and sums the samples into a {@link ProfileReport}.
 * A server with several tick threads (Folia's region threads) has each of them sampled, summed into one report.
 *
 * <p>Only those threads are sampled (a handshake stops it for a few microseconds per sample). Flight Recorder's
 * sampler is not used: it samples a few threads per period in rotation, so on a server with dozens of threads the
 * game thread would get a handful of samples per second.
 */
public final class Profiler {
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private Profiler() {}

    /** Wall-clock nanoseconds, the clock of the samples and of {@link Session#tick}. */
    public static long now() {
        Instant i = Instant.now();
        return i.getEpochSecond() * 1_000_000_000L + i.getNano();
    }

    /**
     * Starts sampling the thread named `thread` (none sampled when no such thread runs).
     * `eventOf` names the event a listener method handles, for frames whose descriptor is unknown.
     */
    public static Session start(String thread, int intervalMs, OwnerIndex index, Function<Frame, String> eventOf) {
        return start(thread, thread::equals, intervalMs, index, eventOf);
    }

    /** Samples every thread whose name `threads` accepts; `label` names them in the report. */
    public static Session start(String label, Predicate<String> threads, int intervalMs, OwnerIndex index,
            Function<Frame, String> eventOf) {
        return new Session(label, threads, intervalMs, index, eventOf);
    }

    /**
     * The wait before the next sample: anywhere from half the interval to one and a half, so the samples average
     * the interval without keeping step with the game loop. At a fixed 10 ms against a 50 ms tick every sample falls
     * at the same few moments of each tick, and work that always runs at another moment is never seen.
     */
    static long nextDelayNanos(int intervalMs, RandomGenerator random) {
        long interval = intervalMs * 1_000_000L;
        return interval / 2 + random.nextLong(interval);
    }

    private static Map<Long, String> threads(Predicate<String> names) {
        Map<Long, String> out = new TreeMap<>();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (names.test(t.getName())) out.put(t.threadId(), t.getName());
        }
        return out;
    }

    public static final class Session {
        private final String thread;
        private final int intervalMs;
        private final Map<Long, String> threadIds;
        private final long[] ids;
        /** Busy samples per thread name (reported unless the one thread is the label itself). */
        private final Map<String, Integer> busy = new TreeMap<>();
        private final ProfileReport report;
        private final long started = System.nanoTime();
        private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "craftwire-sampler");
            t.setDaemon(true);
            t.setPriority(Thread.MAX_PRIORITY);
            return t;
        });

        private Session(String thread, Predicate<String> names, int intervalMs, OwnerIndex index, Function<Frame, String> eventOf) {
            this.thread = thread;
            this.intervalMs = intervalMs;
            this.threadIds = threads(names);
            this.ids = threadIds.keySet().stream().mapToLong(Long::longValue).toArray();
            this.report = new ProfileReport(index, eventOf);
            threadIds.values().forEach(n -> busy.put(n, 0));
            if (ids.length > 0) sampler.execute(this::sampleAndReschedule);
        }

        private void sampleAndReschedule() {
            try {
                sample();
            } finally {
                try {
                    sampler.schedule(this::sampleAndReschedule,
                            nextDelayNanos(intervalMs, ThreadLocalRandom.current()), TimeUnit.NANOSECONDS);
                } catch (RejectedExecutionException stopped) {
                    // finish() or cancel() shut the sampler down
                }
            }
        }

        private void sample() {
            ThreadInfo[] infos = THREADS.getThreadInfo(ids, Integer.MAX_VALUE);
            long now = now();
            synchronized (report) {
                for (ThreadInfo info : infos) {
                    if (info == null) continue;   // the thread ended
                    Thread.State state = info.getThreadState();
                    // Parked or sleeping between ticks/frames is idle time, not time spent; blocked on a lock is spent.
                    if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                        report.idle();
                        continue;
                    }
                    StackTraceElement[] trace = info.getStackTrace();
                    List<Frame> frames = new ArrayList<>(trace.length);
                    for (StackTraceElement e : trace) frames.add(new Frame(e.getClassName(), e.getMethodName(), "", e.getLineNumber()));
                    report.add(frames, now);
                    busy.merge(threadIds.get(info.getThreadId()), 1, Integer::sum);
                }
            }
        }

        /** A finished game tick (from any thread): its number, end ({@link #now()}) and duration. */
        public void tick(long number, long endNanos, long durationNanos) {
            synchronized (report) {
                report.tick(number, endNanos, durationNanos);
            }
        }

        /** Stops sampling and returns the report. */
        public JsonObject finish(int top) {
            sampler.shutdown();
            try {
                sampler.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            JsonObject o = new JsonObject();
            o.addProperty("thread", thread);
            o.addProperty("durationMs", elapsed);
            o.addProperty("intervalMs", intervalMs);
            synchronized (report) {
                report.json(top).entrySet().forEach(e -> o.add(e.getKey(), e.getValue()));
                // Listed whenever the label is not simply the one sampled thread (Folia: its region threads, even one).
                if (!(busy.size() == 1 && busy.containsKey(thread))) {
                    JsonArray threads = new JsonArray();
                    busy.forEach((name, n) -> {
                        JsonObject t = new JsonObject();
                        t.addProperty("name", name);
                        t.addProperty("samples", n);
                        threads.add(t);
                    });
                    o.add("threads", threads);
                }
            }
            return o;
        }

        /** Stops without a report (the agent is shutting down). */
        public void cancel() {
            sampler.shutdownNow();
        }
    }
}
