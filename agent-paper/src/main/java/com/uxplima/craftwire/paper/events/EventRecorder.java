package com.uxplima.craftwire.paper.events;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.LongSupplier;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;

/**
 * What events fired: a count per type and second (kept for an hour), and the details of recent events in a ring
 * buffer. Details are rate-limited per type (`detailsPerSecond`) so a busy event cannot push everything else out;
 * the counts still include every event. Thread-safe: async events are recorded from their own threads.
 */
final class EventRecorder {
    record Query(String type, String player, long since, int limit, boolean cancelledOnly) {}

    private static final int BUCKETS_KEPT = 3600;

    private final int capacity;
    private final int detailsPerSecond;
    private final LongSupplier clock;
    private final Deque<JsonObject> details = new ArrayDeque<>();
    private final Map<String, Deque<long[]>> buckets = new HashMap<>();   // type -> [second, count, cancelled]
    private final Map<String, long[]> detailWindow = new HashMap<>();     // type -> [second, details taken]
    private long detailsDropped;

    EventRecorder(int capacity, int detailsPerSecond, LongSupplier clock) {
        this.capacity = capacity;
        this.detailsPerSecond = detailsPerSecond;
        this.clock = clock;
    }

    void record(Event e) {
        long now = clock.getAsLong();
        long second = now / 1000;
        String type = e.getClass().getSimpleName();
        boolean cancelled = e instanceof Cancellable c && c.isCancelled();
        boolean keepDetails;
        synchronized (this) {
            Deque<long[]> b = buckets.computeIfAbsent(type, t -> new ArrayDeque<>());
            long[] last = b.peekLast();
            if (last == null || last[0] != second) {
                last = new long[] {second, 0, 0};
                b.addLast(last);
                if (b.size() > BUCKETS_KEPT) b.removeFirst();
            }
            last[1]++;
            if (cancelled) last[2]++;
            long[] w = detailWindow.computeIfAbsent(type, t -> new long[] {second, 0});
            if (w[0] != second) {
                w[0] = second;
                w[1] = 0;
            }
            keepDetails = w[1] < detailsPerSecond;
            if (keepDetails) w[1]++;
            else detailsDropped++;
        }
        if (!keepDetails) return;
        JsonObject snapshot = EventSnapshot.of(e, now);   // getters run outside the lock
        synchronized (this) {
            details.addLast(snapshot);
            if (details.size() > capacity) details.removeFirst();
        }
    }

    /** Counts per event type since `since` (epoch ms), busiest first. */
    synchronized JsonObject summary(long since) {
        long fromSecond = since / 1000;
        List<JsonObject> rows = new ArrayList<>();
        for (Map.Entry<String, Deque<long[]>> e : buckets.entrySet()) {
            long count = 0;
            long cancelled = 0;
            for (long[] b : e.getValue()) {
                if (b[0] < fromSecond) continue;
                count += b[1];
                cancelled += b[2];
            }
            if (count == 0) continue;
            JsonObject row = new JsonObject();
            row.addProperty("type", e.getKey());
            row.addProperty("count", count);
            if (cancelled > 0) row.addProperty("cancelled", cancelled);
            rows.add(row);
        }
        rows.sort(Comparator.comparingLong((JsonObject r) -> -r.get("count").getAsLong()).thenComparing(r -> r.get("type").getAsString()));
        JsonObject o = new JsonObject();
        JsonArray counts = new JsonArray();
        rows.forEach(counts::add);
        o.add("counts", counts);
        o.addProperty("detailsDropped", detailsDropped);
        return o;
    }

    /** Recorded events, oldest first, the newest `limit` that match. `type` matches the simple or full class name, any case. */
    synchronized JsonObject query(Query q) {
        String type = q.type() == null ? null : q.type().toLowerCase(Locale.ROOT);
        List<JsonObject> hits = new ArrayList<>();
        for (JsonObject e : details) {
            if (e.get("time").getAsLong() < q.since()) continue;
            if (type != null && !e.get("type").getAsString().toLowerCase(Locale.ROOT).contains(type)
                    && !e.get("class").getAsString().toLowerCase(Locale.ROOT).equals(type)) continue;
            if (q.player() != null && !q.player().equalsIgnoreCase(EventSnapshot.playerOf(e))) continue;
            if (q.cancelledOnly() && !(e.has("cancelled") && e.get("cancelled").getAsBoolean())) continue;
            hits.add(e);
        }
        JsonObject o = new JsonObject();
        JsonArray events = new JsonArray();
        hits.subList(Math.max(0, hits.size() - q.limit()), hits.size()).forEach(events::add);
        o.add("events", events);
        o.addProperty("matched", hits.size());
        return o;
    }
}
