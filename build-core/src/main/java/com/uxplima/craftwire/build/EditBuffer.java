package com.uxplima.craftwire.build;

/** The blocks one part places, in world coordinates; a later placement at the same position wins. */
public final class EditBuffer {
    private final LongIntMap blocks = new LongIntMap(1024);
    private final long limit;
    private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
    private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
    private int outOfRange;

    public EditBuffer(long limit) {
        this.limit = limit;
    }

    public void put(int x, int y, int z, int state) {
        if (!Pos.inRange(x, y, z)) {
            outOfRange++;
            return;
        }
        if (blocks.put(Pos.pack(x, y, z), state) != LongIntMap.NONE) return;
        if (blocks.size() > limit) {
            throw new BuildError("TOO_LARGE", "The part places more than " + limit + " blocks",
                    "Split it into smaller parts, or raise max-build-volume in plugins/Craftwire/config.yml.");
        }
        minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
        maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
    }

    public int get(int x, int y, int z) {
        return Pos.inRange(x, y, z) ? blocks.get(Pos.pack(x, y, z)) : LongIntMap.NONE;
    }

    public LongIntMap blocks() {
        return blocks;
    }

    public int size() {
        return blocks.size();
    }

    /** {minX, minY, minZ, maxX, maxY, maxZ}, or null when nothing was placed. */
    public int[] bbox() {
        return blocks.size() == 0 ? null : new int[] {minX, minY, minZ, maxX, maxY, maxZ};
    }

    /** Placements dropped because they were outside the packable range. */
    public int outOfRange() {
        return outOfRange;
    }
}
