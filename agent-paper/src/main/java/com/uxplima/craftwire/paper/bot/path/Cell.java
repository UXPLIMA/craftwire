package com.uxplima.craftwire.paper.bot.path;

/** What one block space means to a walking player. */
public record Cell(Kind kind, double height) {
    public enum Kind {
        /** Nothing to collide with: air, grass, flowers, torches, signs, pressure plates. */
        AIR,
        /** A full-height obstacle; something to stand on. */
        SOLID,
        /** Collision no higher than a step (0.6): slabs, carpets, snow layers. Walked onto, stood on top of. */
        LOW,
        /** Taller than a block: fences, walls. Neither passable nor something to stand on. */
        TALL,
        WATER,
        /** Lava, fire, magma, cactus, berry bushes, powder snow, wither roses, campfires: never entered. */
        DANGER,
        /** A closed door or fence gate a hand can open. */
        DOOR,
        /** Ladders, vines, scaffolding. */
        CLIMB,
        /** In a chunk that is not loaded: a wall. */
        UNKNOWN
    }

    public static final Cell AIR = new Cell(Kind.AIR, 0);
    public static final Cell SOLID = new Cell(Kind.SOLID, 1);
    public static final Cell TALL = new Cell(Kind.TALL, 1.5);
    public static final Cell WATER = new Cell(Kind.WATER, 0);
    public static final Cell DANGER = new Cell(Kind.DANGER, 1);
    public static final Cell DOOR = new Cell(Kind.DOOR, 1);
    public static final Cell CLIMB = new Cell(Kind.CLIMB, 0);
    public static final Cell UNKNOWN = new Cell(Kind.UNKNOWN, 1);

    public static Cell low(double height) {
        return new Cell(Kind.LOW, height);
    }
}
