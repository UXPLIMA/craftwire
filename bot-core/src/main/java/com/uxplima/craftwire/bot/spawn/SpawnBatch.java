package com.uxplima.craftwire.bot.spawn;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.uxplima.craftwire.core.AgentError;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * The bots of one bot.spawn, joining over several ticks. Completes when every name has joined or failed, with a
 * compact result: {spawned, names: "Load1..Load150", failed?, ms, bots?}. Thread-safe: joins report from the
 * threads that ran them.
 */
public final class SpawnBatch {
    private final List<String> names;
    private final boolean detail;
    private final long started;
    private final List<String> joined = new ArrayList<>();
    private final List<JsonObject> summaries = new ArrayList<>();
    private final Map<String, String> failed = new LinkedHashMap<>();
    private final CompletableFuture<JsonObject> done = new CompletableFuture<>();

    public SpawnBatch(List<String> names, boolean detail, long now) {
        this.names = List.copyOf(names);
        this.detail = detail;
        this.started = now;
        if (names.isEmpty()) finish(now);
    }

    public List<String> names() {
        return names;
    }

    public CompletableFuture<JsonObject> done() {
        return done;
    }

    public synchronized void joined(String name, JsonObject summary) {
        joined.add(name);
        if (detail) summaries.add(summary);
        maybeFinish();
    }

    public synchronized void failed(String name, Throwable why) {
        failed.put(name, why instanceof AgentError e ? e.code() + ": " + e.getMessage() : String.valueOf(why.getMessage()));
        maybeFinish();
    }

    private void maybeFinish() {
        if (joined.size() + failed.size() == names.size()) finish(System.currentTimeMillis());
    }

    private void finish(long now) {
        JsonObject r = new JsonObject();
        r.addProperty("spawned", joined.size());
        r.addProperty("names", NameRanges.compact(joined));
        if (!failed.isEmpty()) {
            JsonArray f = new JsonArray();
            failed.forEach((n, e) -> {
                JsonObject o = new JsonObject();
                o.addProperty("name", n);
                o.addProperty("error", e);
                f.add(o);
            });
            r.add("failed", f);
        }
        r.addProperty("ms", now - started);
        if (detail) {
            JsonArray a = new JsonArray();
            summaries.forEach(a::add);
            r.add("bots", a);
        }
        done.complete(r);
    }
}
