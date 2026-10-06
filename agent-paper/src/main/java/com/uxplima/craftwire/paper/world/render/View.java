package com.uxplima.craftwire.paper.world.render;

/** What a render shows: the surface from above, one height from above, or a front view. */
public sealed interface View {
    /** The surface as a map shows it: north up, x to the right, z downward. */
    record Top() implements View {}

    /** The blocks at height {@code y}, oriented like {@link Top}. */
    record Slice(int y) implements View {}

    /** Looking toward {@code facing} through the region: the nearest block of each column and height, from y1 to y2. */
    record Side(Facing facing, int y1, int y2) implements View {}

    enum Facing { NORTH, SOUTH, EAST, WEST }
}
