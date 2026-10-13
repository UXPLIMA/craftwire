package com.uxplima.craftwire.build;

/** Where shapes put their blocks. */
public interface Placer {
    void place(int x, int y, int z, BlockPick b);
}
