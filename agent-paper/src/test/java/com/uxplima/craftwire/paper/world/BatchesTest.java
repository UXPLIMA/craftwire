package com.uxplima.craftwire.paper.world;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BatchesTest {
    @Test
    void runsBatchesOneAfterAnotherAndKeepsTheOrder() {
        List<Integer> items = IntStream.range(0, 10).boxed().toList();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();
        List<CompletableFuture<List<Integer>>> pending = new ArrayList<>();
        CompletableFuture<List<Integer>> all = Batches.run(items, 4, batch -> {
            maxInFlight.accumulateAndGet(inFlight.addAndGet(batch.size()), Math::max);
            CompletableFuture<List<Integer>> f = new CompletableFuture<>();
            pending.add(f);
            return f.thenApply(r -> { inFlight.addAndGet(-batch.size()); return r; });
        });
        // Only the first batch has started; finishing each batch starts the next.
        assertEquals(1, pending.size());
        pending.get(0).complete(List.of(0, 1, 2, 3));
        assertEquals(2, pending.size());
        pending.get(1).complete(List.of(4, 5, 6, 7));
        pending.get(2).complete(List.of(8, 9));
        assertEquals(items, all.join());
        assertEquals(4, maxInFlight.get());
    }

    @Test
    void anEmptyListCompletesAtOnce() {
        assertEquals(List.of(), Batches.<Integer, Integer>run(List.of(), 4, b -> { throw new AssertionError(); }).join());
    }
}
