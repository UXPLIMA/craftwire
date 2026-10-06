package com.uxplima.craftwire.core;

import java.util.List;

/** Ordering of Minecraft release versions ("26.2", "26.3.1", "26.4-alpha.3"). */
public final class VersionOrder {
    private VersionOrder() {}

    /** The newest of `supported` (oldest first) not newer than `running`; the oldest when the game is older than all. */
    public static String select(String running, List<String> supported) {
        String best = supported.get(0);
        for (String v : supported) if (compare(v, running) <= 0) best = v;
        return best;
    }

    /** Compares dotted versions numerically, ignoring a pre-release suffix ("26.4-alpha.3" counts as 26.4). */
    public static int compare(String a, String b) {
        int[] x = numbers(a);
        int[] y = numbers(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int d = Integer.compare(i < x.length ? x[i] : 0, i < y.length ? y[i] : 0);
            if (d != 0) return d;
        }
        return 0;
    }

    private static int[] numbers(String v) {
        String[] parts = v.split("[-+ ]", 2)[0].split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }
}
