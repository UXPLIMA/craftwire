package com.uxplima.craftwire.paper.world;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** Runs work over a list in fixed-size batches, one batch at a time, and collects the results in order. */
final class Batches {
    private Batches() {}

    static <T, R> CompletableFuture<List<R>> run(List<T> items, int size, Function<List<T>, CompletableFuture<List<R>>> batch) {
        List<R> out = new ArrayList<>(items.size());
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int from = 0; from < items.size(); from += size) {
            List<T> part = items.subList(from, Math.min(items.size(), from + size));
            chain = chain.thenCompose(v -> batch.apply(part)).thenAccept(out::addAll);
        }
        return chain.thenApply(v -> out);
    }
}
