package com.uxplima.craftwire.botrunner.world;

import com.uxplima.craftwire.bot.path.BlockView;
import com.uxplima.craftwire.bot.path.Cell;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The chunks and entities of one dimension of one server, as all of the runner's bots there see them together. A
 * chunk or entity stays while at least one bot holds it (has it loaded or in view) and goes when the last one lets go,
 * so a hundred bots standing together keep one copy of the area instead of a hundred. Runner loop thread.
 */
public final class SharedWorld implements BlockView {
    private final WorldKey key;
    private final BlockClassifier classifier;
    private final Map<Long, ChunkColumn> chunks = new HashMap<>();
    private final Map<Long, Set<Object>> chunkHolders = new HashMap<>();
    private final Map<Object, Set<Long>> heldChunks = new HashMap<>();
    private final Map<Long, TrackedEntity> entities = new HashMap<>();
    private final Map<Long, Set<Object>> entityHolders = new HashMap<>();
    private int bots;

    SharedWorld(WorldKey key, BlockClassifier classifier) {
        this.key = key;
        this.classifier = classifier;
    }

    public WorldKey key() {
        return key;
    }

    public BlockClassifier classifier() {
        return classifier;
    }

    void attach() {
        bots++;
    }

    /** A bot left this world: everything it held is let go. Returns how many bots are still here. */
    int detach(Object holder) {
        Set<Long> held = heldChunks.remove(holder);
        if (held != null) for (long k : held) release(chunkHolders, chunks, k, holder);
        List<Long> seen = new ArrayList<>();
        entityHolders.forEach((id, h) -> {
            if (h.contains(holder)) seen.add(id);
        });
        for (long id : seen) release(entityHolders, entities, id, holder);
        return --bots;
    }

    public static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xffffffffL);
    }

    /** A bot received a chunk: the newest copy replaces the cached one (the server sends the current state). */
    public void loadChunk(int cx, int cz, ChunkColumn column, Object holder) {
        long k = chunkKey(cx, cz);
        chunks.put(k, column);
        chunkHolders.computeIfAbsent(k, x -> new HashSet<>()).add(holder);
        heldChunks.computeIfAbsent(holder, x -> new HashSet<>()).add(k);
    }

    public void unloadChunk(int cx, int cz, Object holder) {
        long k = chunkKey(cx, cz);
        Set<Long> held = heldChunks.get(holder);
        if (held != null) held.remove(k);
        release(chunkHolders, chunks, k, holder);
    }

    public ChunkColumn chunk(int cx, int cz) {
        return chunks.get(chunkKey(cx, cz));
    }

    /** The state at a block, or -1 when no bot has that chunk. */
    public int blockState(int x, int y, int z) {
        ChunkColumn c = chunks.get(chunkKey(x >> 4, z >> 4));
        return c == null ? -1 : c.get(x, y, z);
    }

    /** A single block changed (block update packets); ignored for chunks nobody has. */
    public void setBlock(int x, int y, int z, int state) {
        ChunkColumn c = chunks.get(chunkKey(x >> 4, z >> 4));
        if (c != null) c.set(x, y, z, state);
    }

    @Override
    public Cell cell(int x, int y, int z) {
        int state = blockState(x, y, z);
        return state < 0 ? Cell.UNKNOWN : classifier.classify(state);
    }

    /** A bot sees an entity; the shared entry is created by the first bot, the others only hold it. */
    public TrackedEntity seeEntity(TrackedEntity e, Object holder) {
        TrackedEntity known = entities.putIfAbsent(e.id(), e);
        // Insertion order: the first holder is the one that applies relative moves (see mover).
        entityHolders.computeIfAbsent(e.id(), x -> new LinkedHashSet<>()).add(holder);
        return known == null ? e : known;
    }

    /**
     * The bot that applies relative moves to a shared entity: every bot that sees it gets the same delta, so only one
     * of them may apply it. The longest holder; when it lets go, the next one takes over.
     */
    public Object mover(long id) {
        Set<Object> h = entityHolders.get(id);
        return h == null || h.isEmpty() ? null : h.iterator().next();
    }

    public void forgetEntity(long id, Object holder) {
        release(entityHolders, entities, id, holder);
    }

    public TrackedEntity entity(long id) {
        return entities.get(id);
    }

    public Collection<TrackedEntity> entities() {
        return entities.values();
    }

    /** A player entity by its player-list name, case-insensitive. */
    public TrackedEntity player(String name) {
        for (TrackedEntity e : entities.values()) if (e.name() != null && e.name().equalsIgnoreCase(name)) return e;
        return null;
    }

    public int chunkCount() {
        return chunks.size();
    }

    public long bytes() {
        long n = 0;
        for (ChunkColumn c : chunks.values()) n += c.bytes();
        return n;
    }

    private static <V> void release(Map<Long, Set<Object>> holders, Map<Long, V> values, long k, Object holder) {
        Set<Object> h = holders.get(k);
        if (h == null) return;
        h.remove(holder);
        if (h.isEmpty()) {
            holders.remove(k);
            values.remove(k);
        }
    }
}
