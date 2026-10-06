package com.uxplima.craftwire.paper.wait;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * One wait_for on the server: collects observations until one meets the condition, the time runs out, or the check
 * fails. The first outcome wins; later observations only learn that the wait is over. Thread-safe.
 */
final class Wait {
    private final String condition;
    private final long timeoutMs;
    private final LongSupplier clock;
    private final long started;
    private final CompletableFuture<JsonElement> result = new CompletableFuture<>();
    private volatile JsonElement last = JsonNull.INSTANCE;

    Wait(String condition, long timeoutMs, LongSupplier clock) {
        this.condition = condition;
        this.timeoutMs = timeoutMs;
        this.clock = clock;
        this.started = clock.getAsLong();
    }

    long timeoutMs() {
        return timeoutMs;
    }

    /** Records what the condition looks like now. Returns true when the wait is over (met now or earlier). */
    boolean observe(boolean met, JsonElement value) {
        if (result.isDone()) return true;
        last = value == null ? JsonNull.INSTANCE : value;
        if (!met) return false;
        JsonObject r = outcome(true);
        r.add("value", last);
        result.complete(r);
        return true;
    }

    void timeOut() {
        JsonObject r = outcome(false);
        r.add("last", last);
        result.complete(r);
    }

    void fail(Throwable t) {
        result.completeExceptionally(t);
    }

    boolean done() {
        return result.isDone();
    }

    CompletableFuture<JsonElement> result() {
        return result;
    }

    private JsonObject outcome(boolean matched) {
        JsonObject r = new JsonObject();
        r.addProperty("matched", matched);
        r.addProperty("condition", condition);
        r.addProperty("elapsedMs", clock.getAsLong() - started);
        return r;
    }
}
