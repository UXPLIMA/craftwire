package com.uxplima.craftwire.core.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedMethod;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingStream;

/**
 * Times every call of the methods a filter names, with Flight Recorder's method tracing (JDK 25+): the JVM
 * instruments those methods for the length of the recording and removes the instrumentation afterwards.
 */
public final class MethodTracer {
    /** A trace stops once it has seen this many calls, so a very hot method cannot flood the game. */
    static final int MAX_CALLS = 100_000;

    private static final String NAME = "[A-Za-z_$][\\w$]*";
    private static final Pattern ONE = Pattern.compile(
            "@?" + NAME + "(\\." + NAME + ")*(::(" + NAME + "|<init>|<clinit>))?");

    private MethodTracer() {}

    /** JFR's filter syntax, limited to what a person means: classes, methods, annotations; up to 5, `;`-separated. */
    public static void validate(String filter) {
        String[] parts = filter.split(";", -1);
        boolean ok = !filter.isBlank() && parts.length <= 5;
        for (String p : parts) ok &= ONE.matcher(p.strip()).matches() && !(p.startsWith("@") && p.contains("::"));
        if (!ok) {
            throw new AgentError("INVALID_PARAMS", "Not a method filter: " + filter,
                    "Use com.example.Shop::buy (one method), com.example.Shop (all its methods) or @com.example.Timed "
                            + "(methods with that annotation); nested classes as Outer$Inner; up to 5 separated by ';'.");
        }
    }

    public static Session start(String filter, long minMs, int stackDepth, int limit, OwnerIndex index) {
        validate(filter);
        if (Runtime.version().feature() < 25) {
            throw new AgentError("UNSUPPORTED", "Method tracing needs Java 25 or newer (this game runs " + Runtime.version() + ")",
                    "Run the game or server on Java 25+.");
        }
        return new Session(filter, minMs, stackDepth, limit, index);
    }

    public static final class Session {
        private final String filter;
        private final long minMs;
        private final int stackDepth;
        private final int limit;
        private final OwnerIndex index;
        private final RecordingStream stream = new RecordingStream();
        private final long started = System.nanoTime();
        private final Map<String, Timing> timings = new LinkedHashMap<>();
        private final PriorityQueue<Call> slowest = new PriorityQueue<>(Comparator.comparingLong(Call::nanos));
        private final Map<String, Integer> callers = new HashMap<>();
        private final CompletableFuture<Void> flooded = new CompletableFuture<>();
        private long calls;

        private record Timing(String method, long invocations, Duration average, Duration maximum) {}

        private record Call(String method, long nanos, String thread, List<Frame> stack) {}

        private Session(String filter, long minMs, int stackDepth, int limit, OwnerIndex index) {
            this.filter = filter;
            this.minMs = minMs;
            this.stackDepth = stackDepth;
            this.limit = limit;
            this.index = index;
            stream.setSettings(Map.of(
                    "jdk.MethodTrace#enabled", "true",
                    "jdk.MethodTrace#filter", filter,
                    "jdk.MethodTrace#threshold", minMs + " ms",
                    "jdk.MethodTrace#stackTrace", "true",
                    "jdk.MethodTiming#enabled", "true",
                    "jdk.MethodTiming#filter", filter,
                    "jdk.MethodTiming#period", "endChunk"));
            stream.onEvent("jdk.MethodTrace", this::call);
            stream.onEvent("jdk.MethodTiming", this::timing);
            stream.startAsync();
        }

        private static String name(RecordedMethod m) {
            return Frame.display(m.getType().getName()) + "." + m.getName();
        }

        private synchronized void call(RecordedEvent e) {
            if (++calls > MAX_CALLS) {
                flooded.complete(null);
                return;
            }
            RecordedMethod m = e.getValue("method");
            if (m == null) return;
            long nanos = e.getDuration().toNanos();
            List<Frame> stack = frames(e.getStackTrace(), stackDepth);
            if (!stack.isEmpty()) callers.merge(stack.get(0).qualified(), 1, Integer::sum);
            if (slowest.size() < limit || nanos > slowest.peek().nanos()) {
                RecordedThread t = e.getThread();
                slowest.add(new Call(name(m), nanos, t == null ? null : t.getJavaName(), stack));
                if (slowest.size() > limit) slowest.poll();
            }
        }

        private synchronized void timing(RecordedEvent e) {
            RecordedMethod m = e.getValue("method");
            if (m == null) return;
            String n = name(m);
            timings.put(n, new Timing(n, e.getLong("invocations"), e.getDuration("average"), e.getDuration("maximum")));
        }

        /** Completes when the trace saw so many calls that it should stop early. */
        public CompletableFuture<Void> flooded() {
            return flooded;
        }

        public JsonObject finish() {
            long elapsed = (System.nanoTime() - started) / 1_000_000L;
            try {
                stream.stop();
            } catch (IllegalStateException ignored) {
                // already stopped
            } finally {
                stream.close();
            }
            synchronized (this) {
                JsonObject o = new JsonObject();
                o.addProperty("filter", filter);
                o.addProperty("durationMs", elapsed);
                if (minMs > 0) o.addProperty("minMs", minMs);

                JsonArray methods = new JsonArray();
                timings.values().stream().sorted(Comparator.comparingLong(Timing::invocations).reversed()).forEach(t -> {
                    JsonObject j = owned(t.method());
                    j.addProperty("invocations", t.invocations());
                    if (t.invocations() > 0) {
                        double avg = ms(t.average());
                        j.addProperty("avgMs", avg);
                        j.addProperty("maxMs", ms(t.maximum()));
                        j.addProperty("totalMs", round(avg * t.invocations()));
                    }
                    methods.add(j);
                });
                o.add("methods", methods);

                JsonArray slow = new JsonArray();
                List<Call> calls = new ArrayList<>(slowest);
                calls.sort(Comparator.comparingLong(Call::nanos).reversed());
                for (Call c : calls) {
                    JsonObject j = owned(c.method());
                    j.addProperty("ms", round(c.nanos() / 1e6));
                    if (c.thread() != null) j.addProperty("thread", c.thread());
                    JsonArray stack = new JsonArray();
                    for (Frame f : c.stack()) {
                        JsonObject fj = owned(f.className(), f.method());
                        if (f.line() > 0) fj.addProperty("line", f.line());
                        stack.add(fj);
                    }
                    j.add("stack", stack);
                    slow.add(j);
                }
                o.add("slowest", slow);

                JsonArray by = new JsonArray();
                callers.entrySet().stream()
                        .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                        .limit(20)
                        .forEach(e -> {
                            JsonObject j = owned(e.getKey());
                            j.addProperty("count", e.getValue());
                            by.add(j);
                        });
                o.add("callers", by);
                o.addProperty("calls", Math.min(this.calls, MAX_CALLS));
                o.addProperty("truncated", this.calls > MAX_CALLS);
                return o;
            }
        }

        public void cancel() {
            stream.close();
        }

        private JsonObject owned(String qualified) {
            int dot = qualified.lastIndexOf('.');
            return owned(qualified.substring(0, dot), qualified.substring(dot + 1));
        }

        private JsonObject owned(String className, String method) {
            Owner owner = index.owner(className, method);
            JsonObject j = new JsonObject();
            j.addProperty("method", Frame.display(className) + "." + method);
            j.addProperty("owner", owner.name());
            return j;
        }
    }

    /** Java frames of a stack trace, innermost first. */
    static List<Frame> frames(jdk.jfr.consumer.RecordedStackTrace trace, int max) {
        List<Frame> out = new ArrayList<>();
        if (trace == null) return out;
        for (jdk.jfr.consumer.RecordedFrame f : trace.getFrames()) {
            if (!f.isJavaFrame() || f.getMethod() == null) continue;
            out.add(new Frame(f.getMethod().getType().getName(), f.getMethod().getName(), f.getMethod().getDescriptor(), f.getLineNumber()));
            if (out.size() == max) break;
        }
        return out;
    }

    private static double ms(Duration d) {
        return d == null ? 0 : round(d.toNanos() / 1e6);
    }

    private static double round(double v) {
        return Math.round(v * 1000) / 1000.0;
    }
}
