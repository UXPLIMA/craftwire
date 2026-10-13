package com.uxplima.craftwire.build;

/** Block sources. Random choices hash the position and the seed, so a re-run picks the same blocks. */
public final class Picks {
    private Picks() {}

    public static BlockPick of(int state) {
        return (x, y, z) -> state;
    }

    public static BlockPick mix(int[] states, int[] weights, long seed) {
        if (states.length == 0 || states.length != weights.length) throw new IllegalArgumentException("mix needs one weight per block");
        int[] cumulative = new int[weights.length];
        int total = 0;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] < 0) throw new IllegalArgumentException("mix weights cannot be negative");
            total += weights[i];
            cumulative[i] = total;
        }
        if (total == 0) throw new IllegalArgumentException("mix weights add up to 0");
        int sum = total;
        return (x, y, z) -> {
            int r = (int) Math.floorMod(hash(x, y, z, seed), (long) sum);
            for (int i = 0; i < cumulative.length; i++) if (r < cumulative[i]) return states[i];
            return states[states.length - 1];
        };
    }

    /** Blocks in order from `from` to `to` along axis, with a little dither where bands meet. */
    public static BlockPick gradient(char axis, int from, int to, int[] states, long seed) {
        if (states.length == 0) throw new IllegalArgumentException("gradient needs at least one block");
        int n = states.length;
        double span = Math.max(1, to - from);
        return (x, y, z) -> {
            int c = axis == 'x' ? x : axis == 'z' ? z : y;
            double t = (c - from) / span;
            double jitter = (unit(hash(x, y, z, seed ^ 0x5DEECE66DL)) - 0.5) / n;
            int i = (int) Math.floor((t + jitter) * n);
            return states[Math.max(0, Math.min(n - 1, i))];
        };
    }

    public static long hash(int x, int y, int z, long seed) {
        long h = seed ^ (x * 0x9E3779B97F4A7C15L) ^ (y * 0xC2B2AE3D27D4EB4FL) ^ (z * 0x165667B19E3779F9L);
        h ^= h >>> 31;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 29;
        h *= 0x94D049BB133111EBL;
        return h ^ (h >>> 32);
    }

    static double unit(long h) {
        return (h >>> 11) * 0x1.0p-53;
    }
}
