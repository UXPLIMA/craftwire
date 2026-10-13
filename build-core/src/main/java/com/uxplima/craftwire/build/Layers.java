package com.uxplima.craftwire.build;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * One letter per block, one string per height (bottom first), rows north to south separated by '/' or newlines,
 * letters west to east. '.' is air, '~' leaves a position untouched (input), '?' marks an unloaded chunk (output).
 * world_query encoding:'layers' writes it and the layers() script function reads it, so a read region pastes back.
 */
public final class Layers {
    public static final int NOT_LOADED = -2;
    private static final String LETTERS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!#$%&*+-:;<=>@^_|";

    public record Encoded(Map<String, String> legend, List<String> layers) {}

    @FunctionalInterface
    public interface StateAt {
        int at(int dx, int dy, int dz);
    }

    private Layers() {}

    public static void place(Placer p, int x, int y, int z, Map<Character, BlockPick> legend, String[] layers, int air) {
        BlockPick empty = Picks.of(air);
        for (int li = 0; li < layers.length; li++) {
            int row = 0;
            for (String raw : layers[li].split("[/\\n]", -1)) {
                String r = raw.strip();
                if (r.isEmpty()) continue;
                for (int col = 0; col < r.length(); col++) {
                    char c = r.charAt(col);
                    if (c == '~') continue;
                    BlockPick b = c == '.' ? empty : legend.get(c);
                    if (b == null) {
                        throw new IllegalArgumentException("Unknown letter '" + c + "' in layer " + li + ", row " + row + ", column " + col
                                + " (legend: " + legend.keySet() + ", '.' air, '~' untouched)");
                    }
                    p.place(x + col, y + li, z + row, b);
                }
                row++;
            }
        }
    }

    public static Encoded encode(int sx, int sy, int sz, StateAt at, IntFunction<String> nameOf, int air) {
        Map<Integer, Character> letters = new LinkedHashMap<>();
        Map<String, String> legend = new LinkedHashMap<>();
        List<String> out = new ArrayList<>(sy);
        StringBuilder b = new StringBuilder(sx * sz + sz);
        for (int dy = 0; dy < sy; dy++) {
            b.setLength(0);
            for (int dz = 0; dz < sz; dz++) {
                if (dz > 0) b.append('/');
                for (int dx = 0; dx < sx; dx++) {
                    int s = at.at(dx, dy, dz);
                    if (s == NOT_LOADED) b.append('?');
                    else if (s == air) b.append('.');
                    else b.append(letters.computeIfAbsent(s, id -> {
                        if (letters.size() >= LETTERS.length()) {
                            throw new BuildError("TOO_MANY_STATES", "The region holds more than " + LETTERS.length() + " different block states",
                                    "Query a smaller region, or use encoding:'runs'.");
                        }
                        char c = LETTERS.charAt(letters.size());
                        legend.put(String.valueOf(c), nameOf.apply(id));
                        return c;
                    }));
                }
            }
            out.add(b.toString());
        }
        return new Encoded(legend, out);
    }
}
