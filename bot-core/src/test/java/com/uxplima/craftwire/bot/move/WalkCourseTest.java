package com.uxplima.craftwire.bot.move;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.uxplima.craftwire.bot.model.BlockPoint;
import com.uxplima.craftwire.bot.model.Position;
import com.uxplima.craftwire.bot.path.BlockView;
import com.uxplima.craftwire.bot.path.Cell;
import com.uxplima.craftwire.bot.path.PathBudget;
import com.uxplima.craftwire.bot.path.PathCache;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * move_to end to end as a protocol bot runs it: the pathfinder plans, the walker steers, and the client physics
 * moves the body through a corridor with a one-block step, a closed door and a ladder.
 */
class WalkCourseTest {
    /** A corridor 7 blocks wide (|x| <= 3); everything outside it is unloaded, so the only way is through. */
    private static final class Course implements BlockView {
        final Map<Long, Cell> cells = new HashMap<>();
        int doorsOpened;

        static long key(int x, int y, int z) {
            return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
        }

        void set(int x, int y, int z, Cell c) {
            cells.put(key(x, y, z), c);
        }

        void box(int x1, int y1, int z1, int x2, int y2, int z2, Cell c) {
            for (int x = x1; x <= x2; x++) for (int y = y1; y <= y2; y++) for (int z = z1; z <= z2; z++) set(x, y, z, c);
        }

        @Override
        public Cell cell(int x, int y, int z) {
            if (Math.abs(x) > 3 || z < -2 || z > 40) return Cell.UNKNOWN;
            Cell c = cells.get(key(x, y, z));
            if (c != null) return c;
            return y < 0 ? Cell.SOLID : Cell.AIR;
        }

        /** A player's right click: the door (both halves) opens. */
        void use(BlockPoint b) {
            if (cell(b.x(), b.y(), b.z()).kind() != Cell.Kind.DOOR) return;
            doorsOpened++;
            set(b.x(), b.y(), b.z(), Cell.AIR);
            set(b.x(), b.y() + 1, b.z(), Cell.AIR);
        }
    }

    private static JsonObject walk(Course course, Position target, boolean sprint) {
        CellPhysics body = new CellPhysics(() -> course, () -> "world", on -> { }, course::use);
        body.teleport(0.5, 0, 0.5, 0, 0);
        body.step();
        WalkOptions o = WalkOptions.to(target, sprint, 60_000);
        long now = 0;
        Walker w = Walker.start(body, o, PathBudget.standard(), PathCache.standard(), now);
        for (int tick = 0; tick < 1200; tick++, now += 50) {
            String why = w.tick(body, now);
            if (why != null) {
                w.finish(body, why);
                return w.done().join();
            }
            body.step();
        }
        fail("the walk never ended; the bot is at " + body.position());
        return null;
    }

    private static Course course() {
        Course c = new Course();
        // a one-block step up onto a raised floor from z=5 on
        c.box(-3, 0, 5, 3, 0, 40, Cell.SOLID);
        // a wall across the corridor with a closed door in the middle
        c.box(-3, 1, 12, 3, 3, 12, Cell.SOLID);
        c.set(0, 1, 12, Cell.DOOR);
        c.set(0, 2, 12, Cell.DOOR);
        // a four-block cliff with a ladder up its face
        c.box(-3, 1, 20, 3, 4, 40, Cell.SOLID);
        c.box(0, 1, 19, 0, 4, 19, Cell.CLIMB);
        return c;
    }

    @Test
    void walksUpAStepThroughADoorAndUpALadder() {
        Course c = course();
        JsonObject r = walk(c, new Position(0.5, 5, 25.5), false);
        assertEquals("arrived", r.get("reason").getAsString(), r.toString());
        assertEquals(5, r.get("y").getAsDouble(), 0.01);
        assertEquals(1, c.doorsOpened);
    }

    @Test
    void sprintingTakesTheSameCourse() {
        JsonObject r = walk(course(), new Position(0.5, 5, 25.5), true);
        assertEquals("arrived", r.get("reason").getAsString(), r.toString());
    }
}
