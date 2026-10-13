package com.uxplima.craftwire.build;

/** World reads for scripts, in world coordinates. Implementations copy chunks from their owning thread. */
public interface WorldReader {
    int read(int x, int y, int z);

    /** y of the highest motion-blocking block that is not leaves. */
    int ground(int x, int z);
}
