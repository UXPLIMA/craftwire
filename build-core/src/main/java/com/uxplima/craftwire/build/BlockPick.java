package com.uxplima.craftwire.build;

/** Which state goes at a position: a single block, a mix, a gradient or a script function. NONE leaves it untouched. */
@FunctionalInterface
public interface BlockPick {
    int pick(int x, int y, int z);
}
