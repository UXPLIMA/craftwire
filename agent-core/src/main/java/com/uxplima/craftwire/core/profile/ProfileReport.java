package com.uxplima.craftwire.core.profile;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Sums stack samples of one thread into "who uses the time": per owner, per plugin/mod entry point, per method,
 * and (when tick times are given) per slow tick. Not thread-safe; feed it from one thread.
 */
public final class ProfileReport {
    private static final int SLOWEST_TICKS = 5;

    private final OwnerIndex index;
    private final Function<Frame, String> eventOf;
    private final Map<Owner, Integer> owners = new HashMap<>();
    private final Map<String, Count> self = new HashMap<>();
    private final Map<String, Entry> entries = new HashMap<>();
    private final List<Sample> samples = new ArrayList<>();
    private final List<Tick> ticks = new ArrayList<>();
    private int truncated;
    private int idle;

    public ProfileReport(OwnerIndex index) {
        this(index, Frame::eventParameter);
    }

    /** `eventOf` names the event a listener method handles (null when it is not a listener). */
    public ProfileReport(OwnerIndex index, Function<Frame, String> eventOf) {
        this.index = index;
        this.eventOf = eventOf;
    }

    /** A sample that found the thread waiting (between ticks or frames). */
    public void idle() {
        idle++;
    }

    private record Sample(long time, Owner owner) {}

    private record Tick(long number, long end, long duration) {}

    private static final class Count {
        final Owner owner;
        int n;

        Count(Owner owner) {
            this.owner = owner;
        }
    }

    private static final class Entry {
        final Owner owner;
        final String method;
        final String event;
        final String calledFrom;
        final boolean task;
        int n;

        Entry(Owner owner, String method, String event, String calledFrom, boolean task) {
            this.owner = owner;
            this.method = method;
            this.event = event;
            this.calledFrom = calledFrom;
            this.task = task;
        }
    }

    public void add(List<Frame> stack, long timeNanos) {
        add(stack, timeNanos, false);
    }

    /** One sample: frames from the innermost (top) to the outermost. */
    public void add(List<Frame> stack, long timeNanos, boolean truncatedStack) {
        if (stack.isEmpty()) return;
        if (truncatedStack) truncated++;
        Owner[] frameOwners = new Owner[stack.size()];
        for (int i = 0; i < stack.size(); i++) frameOwners[i] = index.owner(stack.get(i).className(), stack.get(i).method());

        Owner owner = frameOwners[0];
        for (Owner o : frameOwners) {
            if (o.isAddon()) {
                owner = o;
                break;
            }
        }
        owners.merge(owner, 1, Integer::sum);
        samples.add(new Sample(timeNanos, owner));

        Frame top = stack.get(0);
        self.computeIfAbsent(top.qualified(), k -> new Count(frameOwners[0])).n++;

        // Each plugin/mod on the stack: its outermost frame is where it was called from the game.
        Set<Owner> seen = new LinkedHashSet<>();
        for (int i = stack.size() - 1; i >= 0; i--) {
            Owner o = frameOwners[i];
            if (!o.isAddon() || !seen.add(o)) continue;
            Frame f = stack.get(i);
            Frame caller = i + 1 < stack.size() ? stack.get(i + 1) : null;
            String key = o.name() + "\u0000" + f.qualified();
            entries.computeIfAbsent(key, k -> new Entry(o, f.qualified(), eventOf.apply(f),
                    caller == null ? null : caller.qualified(), caller != null && isScheduler(caller))).n++;
        }
    }

    private static boolean isScheduler(Frame f) {
        String c = f.className();
        return c.contains("CraftTask") || c.contains("CraftAsyncTask") || c.contains(".scheduler.");
    }

    /** A finished tick: its number, when it ended and how long it took (same clock as the samples). */
    public void tick(long number, long endNanos, long durationNanos) {
        ticks.add(new Tick(number, endNanos, durationNanos));
    }

    public JsonObject json(int top) {
        int total = samples.size();
        JsonObject o = new JsonObject();
        o.addProperty("samples", total);
        o.addProperty("idleSamples", idle);
        o.addProperty("busyPercent", total + idle == 0 ? 0 : round(100.0 * total / (total + idle)));
        o.addProperty("truncatedStacks", truncated);

        JsonArray byOwner = new JsonArray();
        owners.entrySet().stream()
                .sorted(Map.Entry.<Owner, Integer>comparingByValue().reversed().thenComparing(e -> e.getKey().name()))
                .limit(top)
                .forEach(e -> byOwner.add(ownerJson(e.getKey(), e.getValue(), total)));
        o.add("owners", byOwner);

        JsonArray entryPoints = new JsonArray();
        entries.values().stream()
                .sorted(Comparator.<Entry>comparingInt(e -> e.n).reversed().thenComparing(e -> e.method))
                .limit(top)
                .forEach(e -> {
                    JsonObject j = new JsonObject();
                    j.addProperty("owner", e.owner.name());
                    j.addProperty("kind", e.owner.kind());
                    j.addProperty("method", e.method);
                    if (e.event != null) j.addProperty("event", e.event);
                    if (e.task) j.addProperty("task", true);
                    if (e.calledFrom != null) j.addProperty("calledFrom", e.calledFrom);
                    j.addProperty("percent", percent(e.n, total));
                    entryPoints.add(j);
                });
        o.add("entryPoints", entryPoints);

        JsonArray hot = new JsonArray();
        self.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Count>>comparingInt(e -> e.getValue().n).reversed().thenComparing(Map.Entry::getKey))
                .limit(top)
                .forEach(e -> {
                    JsonObject j = new JsonObject();
                    j.addProperty("method", e.getKey());
                    j.addProperty("owner", e.getValue().owner.name());
                    j.addProperty("kind", e.getValue().owner.kind());
                    j.addProperty("percent", percent(e.getValue().n, total));
                    hot.add(j);
                });
        o.add("hotMethods", hot);

        if (!ticks.isEmpty()) o.add("ticks", ticksJson());
        return o;
    }

    private JsonObject ticksJson() {
        long[] ms = ticks.stream().mapToLong(Tick::duration).sorted().toArray();
        JsonObject t = new JsonObject();
        t.addProperty("count", ms.length);
        t.addProperty("msptAvg", round(java.util.Arrays.stream(ms).average().orElse(0) / 1e6));
        t.addProperty("p50", round(percentile(ms, 50) / 1e6));
        t.addProperty("p95", round(percentile(ms, 95) / 1e6));
        t.addProperty("max", round(ms[ms.length - 1] / 1e6));
        t.addProperty("over50ms", java.util.Arrays.stream(ms).filter(d -> d > 50_000_000L).count());
        JsonArray slowest = new JsonArray();
        ticks.stream().sorted(Comparator.comparingLong(Tick::duration).reversed()).limit(SLOWEST_TICKS).forEach(tick -> {
            JsonObject j = new JsonObject();
            j.addProperty("tick", tick.number());
            j.addProperty("ms", round(tick.duration() / 1e6));
            Map<Owner, Integer> during = new LinkedHashMap<>();
            int n = 0;
            for (Sample s : samples) {
                if (s.time() >= tick.end() - tick.duration() && s.time() <= tick.end()) {
                    during.merge(s.owner(), 1, Integer::sum);
                    n++;
                }
            }
            JsonArray who = new JsonArray();
            int inTick = n;
            during.entrySet().stream().sorted(Map.Entry.<Owner, Integer>comparingByValue().reversed()).limit(3)
                    .forEach(e -> who.add(ownerJson(e.getKey(), e.getValue(), inTick)));
            j.add("owners", who);
            slowest.add(j);
        });
        t.add("slowest", slowest);
        return t;
    }

    private static long percentile(long[] sorted, int p) {
        int rank = (int) Math.ceil(p / 100.0 * sorted.length);
        return sorted[Math.clamp(rank - 1, 0, sorted.length - 1)];
    }

    private static JsonObject ownerJson(Owner owner, int n, int total) {
        JsonObject j = new JsonObject();
        j.addProperty("owner", owner.name());
        j.addProperty("kind", owner.kind());
        j.addProperty("samples", n);
        j.addProperty("percent", percent(n, total));
        return j;
    }

    private static double percent(int n, int total) {
        return total == 0 ? 0 : round(100.0 * n / total);
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
