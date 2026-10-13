package com.uxplima.craftwire.build;

import java.util.List;

/** Block states as ints: the server's own state ids in the game, a made-up table in tests. */
public interface StatePalette {
    /** The id of "stone", "minecraft:stone" or "oak_stairs[facing=east]"; IllegalArgumentException when unknown. */
    int id(String spec);

    /** The full state, e.g. "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]". */
    String name(int id);

    int air();

    boolean isAir(int id);

    /** The state turned clockwise (seen from above) by quarterTurns (0-3). */
    int rotate(int id, int quarterTurns);

    int mirror(int id, Axis axis);

    /** Up to max block ids closest to the block named in spec, for error messages. */
    List<String> suggest(String spec, int max);
}
