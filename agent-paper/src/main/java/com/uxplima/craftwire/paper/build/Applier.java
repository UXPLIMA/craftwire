package com.uxplima.craftwire.paper.build;

import com.uxplima.craftwire.build.Changes;
import com.uxplima.craftwire.build.Pos;
import com.uxplima.craftwire.build.Project;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.mc.BuildStates;
import com.uxplima.craftwire.paper.Sync;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;

/**
 * Writes staged changes chunk by chunk on the thread that owns each chunk, within the tick budget. Records the original
 * state before the first write, skips positions already right, then lets stairs, fences, walls and panes connect.
 */
final class Applier {
    static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;
    private static final int BATCH = 64;

    record Outcome(int changed, int outsideHeight, int unsupported, List<String> unsupportedAt) {}

    private Applier() {}

    static CompletableFuture<Outcome> apply(Sync sync, World world, Project project, Changes changes) {
        ServerLevel level = ((CraftWorld) world).getHandle();
        Map<Long, long[]> byChunk = changes.byChunk();
        List<Long> keys = new ArrayList<>(byChunk.keySet());
        Map<Long, List<Long>> written = new ConcurrentHashMap<>();
        AtomicInteger changed = new AtomicInteger(), outside = new AtomicInteger(), unsupported = new AtomicInteger();
        List<String> unsupportedAt = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int from = 0; from < keys.size(); from += BATCH) {
            List<Long> batch = keys.subList(from, Math.min(keys.size(), from + BATCH));
            chain = chain.thenCompose(v -> CompletableFuture.allOf(batch.stream().map(k -> {
                int cx = (int) (k >> 32), cz = (int) (long) k;
                return world.getChunkAtAsync(cx, cz).thenCompose(ch -> slice(sync, world, cx, cz,
                        new Job(level, project, changes, byChunk.get(k), written.computeIfAbsent(k, x -> Collections.synchronizedList(new ArrayList<>())), changed, outside)));
            }).toArray(CompletableFuture[]::new)));
        }
        CompletableFuture<Outcome> done = chain.thenCompose(v -> CompletableFuture.allOf(written.entrySet().stream().map(e -> {
            int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
            return sync.region(world, cx, cz, () -> {
                connectAndCheck(level, e.getValue(), unsupported, unsupportedAt);
                return null;
            });
        }).toArray(CompletableFuture[]::new))).thenApply(v -> new Outcome(changed.get(), outside.get(), unsupported.get(), List.copyOf(unsupportedAt)));
        return done.exceptionallyCompose(t -> rollback(sync, world, level, project, written).thenCompose(v ->
                CompletableFuture.failedFuture(new AgentError("APPLY_FAILED", "Writing the build failed: " + Sync.explainThread(t).getMessage()
                        + "; the chunks already written were put back", "Check the server log, then run the build again."))));
    }

    private static final class Job {
        final ServerLevel level;
        final Project project;
        final Changes changes;
        final long[] positions;
        final List<Long> written;
        final AtomicInteger changed, outside;
        int next;

        Job(ServerLevel level, Project project, Changes changes, long[] positions, List<Long> written, AtomicInteger changed, AtomicInteger outside) {
            this.level = level; this.project = project; this.changes = changes; this.positions = positions; this.written = written;
            this.changed = changed; this.outside = outside;
        }
    }

    private static CompletableFuture<Void> slice(Sync sync, World world, int cx, int cz, Job j) {
        return sync.region(world, cx, cz, () -> {
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            while (j.next < j.positions.length && !TickBudget.exhausted()) {
                long start = System.nanoTime();
                int end = Math.min(j.positions.length, j.next + 256);
                for (; j.next < end; j.next++) {
                    long p = j.positions[j.next];
                    int y = Pos.y(p);
                    if (y < j.level.getMinY() || y > j.level.getMaxY()) {
                        j.outside.incrementAndGet();
                        continue;
                    }
                    m.set(Pos.x(p), y, Pos.z(p));
                    BlockState current = j.level.getBlockState(m);
                    j.project.recordOriginal(p, Block.getId(current));
                    BlockState target = Block.stateById(j.changes.targets().get(p));
                    if (current == target) continue;
                    j.level.setBlock(m, target, FLAGS);
                    j.written.add(p);
                    j.changed.incrementAndGet();
                }
                TickBudget.spend(System.nanoTime() - start);
            }
            return j.next < j.positions.length;
        }).thenCompose(more -> more ? slice(sync, world, cx, cz, j) : CompletableFuture.completedFuture(null));
    }

    private static boolean connects(BlockState s) {
        Block b = s.getBlock();
        return b instanceof StairBlock || b instanceof CrossCollisionBlock || b instanceof WallBlock;
    }

    private static void connectAndCheck(ServerLevel level, List<Long> written, AtomicInteger unsupported, List<String> at) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        synchronized (written) {
            for (long p : written) {
                m.set(Pos.x(p), Pos.y(p), Pos.z(p));
                BlockState s = level.getBlockState(m);
                if (connects(s)) {
                    BlockState shaped = Block.updateFromNeighbourShapes(s, level, m);
                    if (shaped != s) level.setBlock(m, shaped, FLAGS);
                }
                if (!s.isAir() && !s.canSurvive(level, m)) {
                    unsupported.incrementAndGet();
                    if (at.size() < 5) at.add(m.getX() + "," + m.getY() + "," + m.getZ() + " " + BuildStates.INSTANCE.name(Block.getId(s)));
                }
            }
        }
    }

    private static CompletableFuture<Void> rollback(Sync sync, World world, ServerLevel level, Project project, Map<Long, List<Long>> written) {
        return CompletableFuture.allOf(written.entrySet().stream().map(e -> sync.region(world, (int) (e.getKey() >> 32), (int) (long) e.getKey(), () -> {
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            synchronized (e.getValue()) {
                for (long p : e.getValue()) {
                    int o = project.originalOf(p);
                    if (o >= 0) level.setBlock(m.set(Pos.x(p), Pos.y(p), Pos.z(p)), Block.stateById(o), FLAGS);
                }
            }
            return null;
        }).exceptionally(t -> null)).toArray(CompletableFuture[]::new));
    }
}
