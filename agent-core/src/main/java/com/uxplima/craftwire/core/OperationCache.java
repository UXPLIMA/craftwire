package com.uxplima.craftwire.core;

import com.google.gson.JsonElement;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Remembers successful results by operationId so hub retries never execute twice. */
public final class OperationCache {
    private record Entry(CompletableFuture<JsonElement> future, long createdAt) {}

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final long ttlMillis;
    private final LongSupplier clock;

    public OperationCache(long ttlMillis, LongSupplier clock) {
        this.ttlMillis = ttlMillis;
        this.clock = clock;
    }

    public CompletableFuture<JsonElement> run(String operationId, Supplier<CompletableFuture<JsonElement>> action) {
        long now = clock.getAsLong();
        entries.values().removeIf(e -> now - e.createdAt() > ttlMillis);
        Entry entry = entries.computeIfAbsent(operationId, id -> new Entry(action.get(), now));
        entry.future().whenComplete((r, err) -> {
            if (err != null) entries.remove(operationId, entry);
        });
        return entry.future();
    }
}
