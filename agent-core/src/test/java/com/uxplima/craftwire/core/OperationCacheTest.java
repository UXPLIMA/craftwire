package com.uxplima.craftwire.core;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class OperationCacheTest {
    private final AtomicLong now = new AtomicLong();
    private final OperationCache cache = new OperationCache(1000, now::get);

    @Test
    void returnsCachedSuccess() {
        AtomicInteger runs = new AtomicInteger();
        var a = cache.run("op", () -> CompletableFuture.completedFuture(new JsonPrimitive(runs.incrementAndGet())));
        var b = cache.run("op", () -> CompletableFuture.completedFuture(new JsonPrimitive(runs.incrementAndGet())));
        assertEquals(1, a.join().getAsInt());
        assertEquals(1, b.join().getAsInt());
        assertEquals(1, runs.get());
    }

    @Test
    void doesNotCacheFailures() {
        AtomicInteger runs = new AtomicInteger();
        var failed = cache.run("op", () -> { runs.incrementAndGet(); return CompletableFuture.<JsonElement>failedFuture(new AgentError("X", "x", null)); });
        assertTrue(failed.isCompletedExceptionally());
        var ok = cache.run("op", () -> { runs.incrementAndGet(); return CompletableFuture.completedFuture(new JsonPrimitive(7)); });
        assertEquals(7, ok.join().getAsInt());
        assertEquals(2, runs.get());
    }

    @Test
    void expiresAfterTtl() {
        AtomicInteger runs = new AtomicInteger();
        cache.run("op", () -> CompletableFuture.completedFuture(new JsonPrimitive(runs.incrementAndGet())));
        now.set(1001);
        cache.run("op", () -> CompletableFuture.completedFuture(new JsonPrimitive(runs.incrementAndGet())));
        assertEquals(2, runs.get());
    }
}
