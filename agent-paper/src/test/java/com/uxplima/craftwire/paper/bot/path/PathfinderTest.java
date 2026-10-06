package com.uxplima.craftwire.paper.bot.path;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PathfinderTest {
    /** A flat world: solid ground at y=-1 within ±20 blocks, air above, unloaded beyond ±30. */
    static final class World implements BlockView {
        final Map<Long, Cell> cells = new HashMap<>();

        static long key(int x, int y, int z) {
            return ((long) x & 0xFFFFF) | (((long) y & 0xFFF) << 20) | (((long) z & 0xFFFFF) << 32);
        }

        World set(int x, int y, int z, Cell c) {
            cells.put(key(x, y, z), c);
            return this;
        }

        World fill(int x1, int y1, int z1, int x2, int y2, int z2, Cell c) {
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++)
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++)
                    for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) set(x, y, z, c);
            return this;
        }

        @Override
        public Cell cell(int x, int y, int z) {
            if (Math.abs(x) > 30 || Math.abs(z) > 30) return Cell.UNKNOWN;
            Cell c = cells.get(key(x, y, z));
            if (c != null) return c;
            return y == -1 && Math.abs(x) <= 20 && Math.abs(z) <= 20 ? Cell.SOLID : Cell.AIR;
        }
    }

    static final Pathfinder.Options DEFAULT = new Pathfinder.Options(3, true, 40_000);

    static Pathfinder.Result find(World w, int sx, int sy, int sz, int gx, int gy, int gz) {
        return Pathfinder.find(w, sx, sy, sz, gx, gy, gz, DEFAULT);
    }

    static Pathfinder.Step last(Pathfinder.Result r) {
        return r.steps().get(r.steps().size() - 1);
    }

    static boolean visits(Pathfinder.Result r, int x, int y, int z) {
        return r.steps().stream().anyMatch(s -> s.x() == x && s.y() == y && s.z() == z);
    }

    @Test
    void walksStraightOnFlatGround() {
        Pathfinder.Result r = find(new World(), 0, 0, 0, 6, 0, 0);
        assertTrue(r.complete());
        assertEquals(6, last(r).x());
        assertEquals(0, last(r).z());
        assertEquals(7, r.steps().size(), r.steps().toString());   // start + 6 steps
        assertEquals(Pathfinder.Move.START, r.steps().get(0).move());
    }

    @Test
    void goesAroundAWall() {
        World w = new World().fill(3, 0, -4, 3, 1, 4, Cell.SOLID);
        Pathfinder.Result r = find(w, 0, 0, 0, 6, 0, 0);
        assertTrue(r.complete());
        for (Pathfinder.Step s : r.steps()) assertFalse(s.x() == 3 && Math.abs(s.z()) <= 4, "through the wall at " + s);
    }

    @Test
    void jumpsUpOneBlockButNotTwo() {
        World one = new World().fill(3, 0, -30, 20, 0, 30, Cell.SOLID);
        Pathfinder.Result r = find(one, 0, 0, 0, 6, 1, 0);
        assertTrue(r.complete());
        assertTrue(r.steps().stream().anyMatch(s -> s.move() == Pathfinder.Move.JUMP && s.y() == 1), r.steps().toString());

        World two = new World().fill(3, 0, -30, 20, 1, 30, Cell.SOLID);
        Pathfinder.Result none = find(two, 0, 0, 0, 6, 2, 0);
        assertFalse(none.complete());
        assertEquals(2, none.closest().x(), "the closest it got is in front of the wall: " + none.closest());
        assertEquals(none.closest(), last(none), "and the steps lead there");
    }

    @Test
    void aJumpNeedsHeadroom() {
        World w = new World().fill(3, 0, -30, 20, 0, 30, Cell.SOLID).fill(-20, 2, -30, 2, 2, 30, Cell.SOLID);
        assertFalse(find(w, 0, 0, 0, 6, 1, 0).complete(), "a ceiling right above the head leaves no room to jump");
    }

    @Test
    void dropsDownAtMostMaxFall() {
        World tower = new World().fill(0, 0, 0, 0, 2, 0, Cell.SOLID);   // standing on top: y=3
        Pathfinder.Result r = find(tower, 0, 3, 0, 4, 0, 0);
        assertTrue(r.complete());
        assertTrue(r.steps().stream().anyMatch(s -> s.move() == Pathfinder.Move.DROP), r.steps().toString());

        World high = new World().fill(0, 0, 0, 0, 3, 0, Cell.SOLID);   // y=4: a 4-block drop
        assertFalse(find(high, 0, 4, 0, 4, 0, 0).complete());
        assertTrue(Pathfinder.find(high, 0, 4, 0, 4, 0, 0, new Pathfinder.Options(4, true, 40_000)).complete());
    }

    @Test
    void neverStepsIntoDanger() {
        World around = new World().fill(3, -1, -4, 3, -1, 4, Cell.DANGER).fill(3, 0, -4, 3, 0, 4, Cell.DANGER);
        Pathfinder.Result r = find(around, 0, 0, 0, 6, 0, 0);
        assertTrue(r.complete());
        for (Pathfinder.Step s : r.steps()) assertFalse(s.x() == 3 && Math.abs(s.z()) <= 4, "through lava at " + s);

        World across = new World().fill(3, -1, -30, 3, 0, 30, Cell.DANGER);
        assertFalse(find(across, 0, 0, 0, 6, 0, 0).complete());
    }

    @Test
    void opensDoorsOnlyWhenAllowed() {
        World w = new World().fill(3, 0, -30, 3, 2, 30, Cell.SOLID).set(3, 0, 0, Cell.DOOR).set(3, 1, 0, Cell.DOOR);
        Pathfinder.Result r = find(w, 0, 0, 0, 6, 0, 0);
        assertTrue(r.complete());
        assertTrue(r.steps().stream().anyMatch(s -> s.move() == Pathfinder.Move.DOOR && s.x() == 3), r.steps().toString());
        assertFalse(Pathfinder.find(w, 0, 0, 0, 6, 0, 0, new Pathfinder.Options(3, false, 40_000)).complete());
    }

    @Test
    void swimsThroughWater() {
        World w = new World().fill(3, -2, -30, 5, 0, 30, Cell.WATER).fill(3, -3, -30, 5, -3, 30, Cell.SOLID);
        Pathfinder.Result r = find(w, 0, 0, 0, 8, 0, 0);
        assertTrue(r.complete());
        assertTrue(r.steps().stream().anyMatch(s -> s.move() == Pathfinder.Move.SWIM), r.steps().toString());
    }

    @Test
    void climbsLadders() {
        // A 4-high wall with a ladder on its near face; the top is a platform.
        World w = new World().fill(3, 0, -30, 20, 3, 30, Cell.SOLID).fill(2, 0, 0, 2, 3, 0, Cell.CLIMB);
        Pathfinder.Result r = find(w, 0, 0, 0, 5, 4, 0);
        assertTrue(r.complete(), r.steps().toString());
        assertTrue(r.steps().stream().anyMatch(s -> s.move() == Pathfinder.Move.CLIMB), r.steps().toString());
        assertEquals(4, last(r).y());
    }

    @Test
    void doesNotCutCorners() {
        World w = new World().fill(1, 0, 0, 1, 1, 0, Cell.SOLID).fill(0, 0, 1, 0, 1, 1, Cell.SOLID);
        Pathfinder.Result r = find(w, 0, 0, 0, 1, 0, 1);
        assertTrue(r.complete());
        assertTrue(r.steps().size() > 2, "a diagonal between two blocks that touch at the corner is no way: " + r.steps());
    }

    @Test
    void standsOnSlabsAndCarpets() {
        World w = new World().fill(3, 0, -30, 20, 0, 30, Cell.low(0.5));
        Pathfinder.Result r = find(w, 0, 0, 0, 6, 0, 0);
        assertTrue(r.complete(), r.steps().toString());
        assertEquals(0.5, last(r).standY(), 1e-9);
        assertEquals(0, last(r).y());
    }

    @Test
    void unloadedChunksAreWallsAndTheSearchIsBounded() {
        Pathfinder.Result edge = find(new World(), 0, 0, 0, 29, 0, 0);
        assertFalse(edge.complete(), "the ground ends at 20");
        assertFalse(edge.unloaded(), "a dead end within loaded chunks: no way exists");
        World toTheEdge = new World().fill(-30, -1, -2, 30, -1, 2, Cell.SOLID);
        Pathfinder.Result beyond = find(toTheEdge, 0, 0, 0, 40, 0, 0);
        assertFalse(beyond.complete());
        assertTrue(beyond.unloaded(), "the ground goes on into unloaded chunks: a way may exist");
        assertEquals(30, beyond.closest().x());
        Pathfinder.Result r = Pathfinder.find(new World(), 0, 0, 0, 19, 0, 19, new Pathfinder.Options(3, true, 10));
        assertFalse(r.complete());
        assertTrue(r.explored() <= 10);
    }

    @Test
    void theStartMayBeAnywhere() {
        // A bot standing in a slab cell, or mid-air at a ledge, still gets a path.
        List<Pathfinder.Step> steps = find(new World(), 0, 0, 0, 0, 0, 0).steps();
        assertEquals(1, steps.size());
    }
}
