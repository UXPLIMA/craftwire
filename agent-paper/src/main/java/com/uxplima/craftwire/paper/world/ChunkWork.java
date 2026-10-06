package com.uxplima.craftwire.paper.world;

import com.uxplima.craftwire.paper.Sync;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.bukkit.Chunk;
import org.bukkit.World;

/**
 * Splits block work by chunk: loads each chunk asynchronously, then runs its part on the thread that owns it.
 * At most {@link #BATCH} chunks are in flight, so a 1000x1000 fill does not ask the server for ~4000 chunks at once.
 */
public final class ChunkWork {
    static final int BATCH = 64;

    private ChunkWork() {}

    public static <R> CompletableFuture<List<R>> forChunks(Sync sync, World world, List<int[]> chunks, BiFunction<Integer, Integer, R> work) {
        return Batches.run(chunks, BATCH, batch -> forBatch(sync, world, batch, work));
    }

    private static <R> CompletableFuture<List<R>> forBatch(Sync sync, World world, List<int[]> chunks, BiFunction<Integer, Integer, R> work) {
        // Start every load from the server thread, then run each part where its chunk lives.
        CompletableFuture<List<CompletableFuture<Chunk>>> loads =
                sync.global(() -> chunks.stream().map(c -> world.getChunkAtAsync(c[0], c[1])).toList());
        return loads.thenCompose(started -> {
            List<CompletableFuture<R>> parts = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size(); i++) {
                int cx = chunks.get(i)[0], cz = chunks.get(i)[1];
                parts.add(started.get(i).thenCompose(chunk -> sync.region(world, cx, cz, () -> work.apply(cx, cz))));
            }
            return CompletableFuture.allOf(parts.toArray(CompletableFuture[]::new))
                    .thenApply(v -> parts.stream().map(CompletableFuture::join).toList());
        });
    }

    public static <R> CompletableFuture<List<R>> forEachChunk(Sync sync, World world, Box box, Function<Box, R> work) {
        return forChunks(sync, world, box.chunks(), (cx, cz) -> work.apply(box.clipToChunk(cx, cz)));
    }
}
