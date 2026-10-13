package com.uxplima.craftwire.build;

/** Collects placements in local coordinates. */
public final class RecordingPlacer implements Placer {
    public final LongIntMap placed = new LongIntMap();

    @Override public void place(int x, int y, int z, BlockPick b) {
        int s = b.pick(x, y, z);
        if (s >= 0) placed.put(Pos.pack(x, y, z), s);
    }

    public int count() {
        return placed.size();
    }

    public int at(int x, int y, int z) {
        return placed.get(Pos.pack(x, y, z));
    }
}
