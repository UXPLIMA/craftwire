package com.uxplima.craftwire.build;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A palette for tests: a fixed set of block ids, states kept as written (properties in the order given),
 * facing and axis turn and mirror like the game's.
 */
public final class FakePalette implements StatePalette {
    public static final Set<String> BLOCKS = Set.of("air", "stone", "stone_bricks", "cracked_stone_bricks", "mossy_stone_bricks",
            "oak_planks", "oak_stairs", "oak_slab", "spruce_planks", "spruce_stairs", "spruce_slab", "oak_log", "glass", "gold_block",
            "dirt", "grass_block", "oak_fence", "cobblestone", "bricks", "brick_stairs", "brick_slab", "white_wool", "torch");
    private static final String[] TURN = {"north", "east", "south", "west"};

    private final List<String> names = new ArrayList<>();
    private final Map<String, Integer> ids = new HashMap<>();

    public FakePalette() {
        id("air");
    }

    @Override public int id(String spec) {
        String s = spec.strip();
        if (!s.startsWith("minecraft:")) s = "minecraft:" + s;
        String base = s.substring("minecraft:".length(), s.contains("[") ? s.indexOf('[') : s.length());
        if (!BLOCKS.contains(base)) throw new IllegalArgumentException("Unknown block '" + base + "'");
        return ids.computeIfAbsent(s, k -> {
            names.add(k);
            return names.size() - 1;
        });
    }

    @Override public String name(int id) {
        return names.get(id);
    }

    @Override public int air() {
        return 0;
    }

    @Override public boolean isAir(int id) {
        return id == 0;
    }

    @Override public int rotate(int id, int quarterTurns) {
        String n = name(id);
        for (int i = 0; i < 4; i++) {
            if (n.contains("facing=" + TURN[i] + "")) {
                n = n.replace("facing=" + TURN[i], "facing=" + TURN[(i + quarterTurns) & 3]);
                break;
            }
        }
        if ((quarterTurns & 1) == 1) {
            if (n.contains("axis=x")) n = n.replace("axis=x", "axis=z");
            else if (n.contains("axis=z")) n = n.replace("axis=z", "axis=x");
        }
        return id(n);
    }

    @Override public int mirror(int id, Axis axis) {
        String n = name(id);
        String a = axis == Axis.X ? "east" : "north", b = axis == Axis.X ? "west" : "south";
        if (n.contains("facing=" + a)) n = n.replace("facing=" + a, "facing=" + b);
        else if (n.contains("facing=" + b)) n = n.replace("facing=" + b, "facing=" + a);
        return id(n);
    }

    @Override public List<String> suggest(String spec, int max) {
        String base = spec.replace("minecraft:", "").replaceAll("\\[.*", "");
        return BLOCKS.stream().filter(b -> b.startsWith(base) || base.startsWith(b)).sorted().limit(max).map(b -> "minecraft:" + b).toList();
    }
}
