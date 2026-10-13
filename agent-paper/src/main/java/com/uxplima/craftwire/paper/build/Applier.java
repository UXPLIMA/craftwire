package com.uxplima.craftwire.paper.build;

import com.uxplima.craftwire.build.Changes;
import com.uxplima.craftwire.build.Pos;
import com.uxplima.craftwire.build.Project;
import com.uxplima.craftwire.core.AgentError;
import com.uxplima.craftwire.mc.BuildStates;
import com.uxplima.craftwire.paper.Sync;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
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
        Map<Long, Job> jobs = new ConcurrentHashMap<>();
        AtomicInteger changed = new AtomicInteger(), outside = new AtomicInteger(), unsupported = new AtomicInteger();
        List<String> unsupportedAt = Collections.synchronizedList(new ArrayList<>());
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int from = 0; from < keys.size(); from += BATCH) {
            List<Long> batch = keys.subList(from, Math.min(keys.size(), from + BATCH));
            chain = chain.thenCompose(v -> CompletableFuture.allOf(batch.stream().map(k -> {
                int cx = (int) (k >> 32), cz = (int) (long) k;
                Job job = jobs.computeIfAbsent(k, x -> new Job(level, project, changes, byChunk.get(k), changed, outside));
                return world.getChunkAtAsync(cx, cz).thenCompose(ch -> slice(sync, world, cx, cz, job));
            }).toArray(CompletableFuture[]::new)));
        }
        CompletableFuture<Outcome> done = chain.thenCompose(v -> CompletableFuture.allOf(jobs.entrySet().stream().map(e -> {
            int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
            return sync.region(world, cx, cz, () -> {
                connectAndCheck(e.getValue(), unsupported, unsupportedAt);
                return null;
            });
        }).toArray(CompletableFuture[]::new))).thenApply(v -> new Outcome(changed.get(), outside.get(), unsupported.get(), List.copyOf(unsupportedAt)));
        return done.exceptionallyCompose(t -> rollback(sync, world, jobs).thenCompose(v ->
                CompletableFuture.failedFuture(new AgentError("APPLY_FAILED", "Writing the build failed: " + Sync.explainThread(t).getMessage()
                        + "; the chunks already written were put back", "Check the server log, then run the build again."))));
    }

    /** One chunk's share of the changes. Only the chunk's owning thread touches it, one slice at a time. */
    private static final class Job {
        final ServerLevel level;
        final Project project;
        final Changes changes;
        final long[] positions;
        final BitSet wrote;   // indexes into positions that this run changed
        final AtomicInteger changed, outside;
        int next;

        Job(ServerLevel level, Project project, Changes changes, long[] positions, AtomicInteger changed, AtomicInteger outside) {
            this.level = level; this.project = project; this.changes = changes; this.positions = positions;
            this.wrote = new BitSet(positions.length);
            this.changed = changed; this.outside = outside;
        }
    }

    private static CompletableFuture<Void> slice(Sync sync, World world, int cx, int cz, Job j) {
        return sync.region(world, cx, cz, () -> {
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            int minY = j.level.getMinY(), maxY = j.level.getMaxY();
            while (j.next < j.positions.length && !TickBudget.exhausted()) {
                long start = System.nanoTime();
                int end = Math.min(j.positions.length, j.next + 256), n = 0;
                for (; j.next < end; j.next++) {
                    long p = j.positions[j.next];
                    int y = Pos.y(p);
                    if (y < minY || y > maxY) {
                        j.outside.incrementAndGet();
                        continue;
                    }
                    m.set(Pos.x(p), y, Pos.z(p));
                    BlockState current = j.level.getBlockState(m);
                    j.project.recordOriginal(p, Block.getId(current));
                    BlockState target = Block.stateById(j.changes.targets().get(p));
                    if (current == target) continue;
                    j.level.setBlock(m, target, FLAGS);
                    j.wrote.set(j.next);
                    n++;
                }
                j.changed.addAndGet(n);
                TickBudget.spend(System.nanoTime() - start);
            }
            return j.next < j.positions.length;
        }).thenCompose(more -> more ? slice(sync, world, cx, cz, j) : CompletableFuture.completedFuture(null));
    }

    private static boolean connects(BlockState s) {
        Block b = s.getBlock();
        return b instanceof StairBlock || b instanceof CrossCollisionBlock || b instanceof WallBlock;
    }

    /** Per block state: 0 not looked at yet, 1 nothing to do after writing, 2 connects or may need support. */
    private static final byte[] CHECK = new byte[Block.BLOCK_STATE_REGISTRY.size()];

    private static boolean needsCheck(int id) {
        byte c = CHECK[id];
        if (c == 0) {
            BlockState s = Block.stateById(id);
            CHECK[id] = c = (byte) (!s.isAir() && (connects(s) || overridesCanSurvive(s.getBlock())) ? 2 : 1);
        }
        return c == 2;
    }

    /** Plain blocks (stone, planks, glass...) always survive; only blocks that override canSurvive can lack support. */
    private static boolean overridesCanSurvive(Block b) {
        for (Class<?> c = b.getClass(); c != null && c != BlockBehaviour.class; c = c.getSuperclass()) {
            try {
                c.getDeclaredMethod("canSurvive", BlockState.class, LevelReader.class, BlockPos.class);
                return true;
            } catch (NoSuchMethodException e) {
                // not declared here: look further up
            }
        }
        return false;
    }

    private static void connectAndCheck(Job j, AtomicInteger unsupported, List<String> at) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int i = j.wrote.nextSetBit(0); i >= 0; i = j.wrote.nextSetBit(i + 1)) {
            long p = j.positions[i];
            if (!needsCheck(j.changes.targets().get(p))) continue;
            m.set(Pos.x(p), Pos.y(p), Pos.z(p));
            BlockState s = j.level.getBlockState(m);
            if (connects(s)) {
                BlockState shaped = Block.updateFromNeighbourShapes(s, j.level, m);
                if (shaped != s) j.level.setBlock(m, shaped, FLAGS);
            }
            if (!s.isAir() && !s.canSurvive(j.level, m)) {
                unsupported.incrementAndGet();
                if (at.size() < 5) at.add(m.getX() + "," + m.getY() + "," + m.getZ() + " " + BuildStates.INSTANCE.name(Block.getId(s)));
            }
        }
    }

    private static CompletableFuture<Void> rollback(Sync sync, World world, Map<Long, Job> jobs) {
        return CompletableFuture.allOf(jobs.entrySet().stream().map(e -> sync.region(world, (int) (e.getKey() >> 32), (int) (long) e.getKey(), () -> {
            Job j = e.getValue();
            BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
            for (int i = j.wrote.nextSetBit(0); i >= 0; i = j.wrote.nextSetBit(i + 1)) {
                long p = j.positions[i];
                int o = j.project.originalOf(p);
                if (o >= 0) j.level.setBlock(m.set(Pos.x(p), Pos.y(p), Pos.z(p)), Block.stateById(o), FLAGS);
            }
            return null;
        }).exceptionally(t -> null)).toArray(CompletableFuture[]::new));
    }
}
