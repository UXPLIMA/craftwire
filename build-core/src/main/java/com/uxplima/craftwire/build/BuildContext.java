package com.uxplima.craftwire.build;

import java.util.ArrayDeque;
import java.util.Deque;

/** One run of a build script: the frame stack, the current part's buffer, world reads and printed output. */
public final class BuildContext implements Placer {
    private final StatePalette palette;
    private final WorldReader reader;
    private final long limit;
    private final long seed;
    private final Deque<Transform> frames = new ArrayDeque<>();
    private final BadBlocks bad = new BadBlocks();
    private final StringBuilder out = new StringBuilder();
    private EditBuffer buffer;

    public BuildContext(StatePalette palette, WorldReader reader, Transform root, long limit, long seed) {
        this.palette = palette;
        this.reader = reader;
        this.limit = limit;
        this.seed = seed;
        frames.push(root);
        startPart();
    }

    public void startPart() {
        buffer = new EditBuffer(limit);
        while (frames.size() > 1) frames.pop();
    }

    public EditBuffer buffer() { return buffer; }
    public StatePalette palette() { return palette; }
    public long seed() { return seed; }
    public BadBlocks bad() { return bad; }
    public String output() { return out.toString(); }

    public int state(String spec) {
        try {
            return palette.id(spec);
        } catch (IllegalArgumentException e) {
            bad.add(spec, palette.suggest(spec, 3));
            return palette.air();
        }
    }

    @Override public void place(int x, int y, int z, BlockPick b) {
        int s = b.pick(x, y, z);
        if (s < 0) return;
        Transform t = frames.peek();
        buffer.put(t.worldX(x, z), t.worldY(y), t.worldZ(x, z), t.state(s, palette));
    }

    public void push(Transform child) {
        frames.push(frames.peek().then(child));
    }

    public void pop() {
        if (frames.size() == 1) throw new IllegalStateException("pop without push");
        frames.pop();
    }

    /** The state at a local position: this run's placement if any, else the world. */
    public int read(int x, int y, int z) {
        Transform t = frames.peek();
        int wx = t.worldX(x, z), wy = t.worldY(y), wz = t.worldZ(x, z);
        int s = buffer.get(wx, wy, wz);
        return s != LongIntMap.NONE ? s : reader.read(wx, wy, wz);
    }

    /** Ground height at a local column, relative to the current frame's y. */
    public int ground(int x, int z) {
        Transform t = frames.peek();
        return reader.ground(t.worldX(x, z), t.worldZ(x, z)) - t.oy();
    }

    public void print(String s) {
        if (out.length() < 20_000) out.append(s).append('\n');
    }
}
