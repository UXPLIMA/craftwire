package com.uxplima.craftwire.bot.move;

import static org.junit.jupiter.api.Assertions.*;

import com.uxplima.craftwire.bot.path.BlockView;
import com.uxplima.craftwire.bot.path.Cell;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The protocol bots' client physics against numbers the vanilla client produces (LivingEntity.travel with the
 * default attributes): servers and anti-cheats compare a player's movement with exactly these, so a bot that moves
 * faster, jumps lower or climbs slower than this is either flagged or stuck.
 */
class CellPhysicsTest {
    /** Stone at y=-1 everywhere within 50 blocks, air above, unloaded beyond; single cells can be overridden. */
    private static final class World implements BlockView {
        final Map<String, Cell> cells = new HashMap<>();

        World set(int x, int y, int z, Cell c) {
            cells.put(x + "," + y + "," + z, c);
            return this;
        }

        @Override
        public Cell cell(int x, int y, int z) {
            if (Math.abs(x) > 50 || Math.abs(z) > 50) return Cell.UNKNOWN;
            Cell c = cells.get(x + "," + y + "," + z);
            if (c != null) return c;
            return y < 0 ? Cell.SOLID : Cell.AIR;
        }
    }

    private static CellPhysics body(World w, double x, double y, double z) {
        CellPhysics p = new CellPhysics(() -> w, () -> "world", on -> { }, b -> { });
        p.teleport(x, y, z, 0, 0);
        settle(p);
        return p;
    }

    private static void settle(CellPhysics p) {
        for (int i = 0; i < 40 && !p.onGround(); i++) p.step();
        assertTrue(p.onGround(), "stands on the ground before the test");
    }

    /** Walking south (yaw 0 is +z) for long enough to reach top speed; returns blocks per tick. */
    private static double topSpeed(boolean sprint) {
        CellPhysics p = body(new World(), 0.5, 0, -40.5);
        p.sprinting(sprint);
        p.input(new MoveInput(0f, 1f, false, sprint));
        for (int i = 0; i < 60; i++) p.step();
        double z0 = p.position().z();
        p.step();
        return p.position().z() - z0;
    }

    @Test
    void walksAtVanillaSpeed() {
        // 0.1 * 0.98 / (1 - 0.6 * 0.91) = 0.21586 blocks a tick = 4.317 m/s
        assertEquals(0.21586, topSpeed(false), 0.0005);
    }

    @Test
    void sprintsAtVanillaSpeed() {
        // 0.13 * 0.98 / (1 - 0.546) = 0.28062 blocks a tick = 5.612 m/s
        assertEquals(0.28062, topSpeed(true), 0.0005);
    }

    @Test
    void aStandingJumpPeaksAtVanillaHeight() {
        CellPhysics p = body(new World(), 0.5, 0, 0.5);
        assertTrue(p.jumpNow());
        double peak = 0;
        for (int i = 0; i < 20; i++) {
            p.step();
            peak = Math.max(peak, p.position().y());
        }
        // vy starts at 0.42 and loses (vy - 0.08) * 0.98 each tick after moving: the apex is 1.2522
        assertEquals(1.2522, peak, 0.001);
        assertEquals(0, p.position().y(), 1e-9, "lands back on the ground");
        assertTrue(p.onGround());
    }

    @Test
    void jumpsOntoAOneBlockLedge() {
        World w = new World();
        for (int x = -2; x <= 2; x++) for (int z = 3; z <= 40; z++) w.set(x, 0, z, Cell.SOLID);
        CellPhysics p = body(w, 0.5, 0, 0.5);
        for (int i = 0; i < 30; i++) {
            p.input(new MoveInput(0f, 1f, p.horizontalCollision(), false));
            p.step();
        }
        assertEquals(1, p.position().y(), 1e-9, "on top of the ledge");
        assertTrue(p.position().z() > 3.3);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stepsOntoASlabWithoutJumping(boolean sprint) {
        World w = new World();
        for (int x = -2; x <= 2; x++) for (int z = 3; z <= 40; z++) w.set(x, 0, z, Cell.low(0.5));
        CellPhysics p = body(w, 0.5, 0, 0.5);
        p.sprinting(sprint);
        p.input(new MoveInput(0f, 1f, false, sprint));
        for (int i = 0; i < 30; i++) p.step();
        assertEquals(0.5, p.position().y(), 1e-9);
        assertTrue(p.position().z() > 4);
    }

    @Test
    void aWallStopsTheBodyAtItsFace() {
        World w = new World();
        for (int x = -2; x <= 2; x++) for (int y = 0; y <= 2; y++) w.set(x, y, 3, Cell.SOLID);
        CellPhysics p = body(w, 0.5, 0, 0.5);
        p.input(new MoveInput(0f, 1f, false, false));
        for (int i = 0; i < 30; i++) p.step();
        assertEquals(3 - 0.3, p.position().z(), 1e-6, "the body's half width short of the wall");
        assertTrue(p.horizontalCollision());
    }

    @Test
    void fallsAtVanillaSpeedAndLandsOnTheBlock() {
        World w = new World();
        CellPhysics p = new CellPhysics(() -> w, () -> "world", on -> { }, b -> { });
        p.teleport(0.5, 20, 0.5, 0, 0);
        p.step();
        // the first tick of a fall moves by the starting velocity 0, the second by -0.0784
        assertEquals(20, p.position().y(), 1e-9);
        p.step();
        assertEquals(20 - 0.0784, p.position().y(), 1e-9);
        for (int i = 0; i < 60; i++) p.step();
        assertEquals(0, p.position().y(), 1e-9);
        assertTrue(p.onGround());
    }

    @Test
    void climbsALadderAtVanillaSpeed() {
        World w = new World();
        for (int y = 0; y < 10; y++) {
            w.set(0, y, 0, Cell.CLIMB);
            w.set(0, y, 1, Cell.SOLID);
        }
        CellPhysics p = body(w, 0.5, 0, 0.5);
        p.input(new MoveInput(0f, 1f, false, false));
        for (int i = 0; i < 20; i++) p.step();
        double y0 = p.position().y();
        p.step();
        // pressing into the ladder sets vy to 0.2 after the move; gravity and drag make it 0.1176 a tick (2.35 m/s)
        assertEquals(0.1176, p.position().y() - y0, 0.0005);
    }

    @Test
    void standsStillInAChunkItHasNotReceived() {
        CellPhysics p = new CellPhysics(World::new, () -> "world", on -> { }, b -> { });
        p.teleport(80.5, 30, 0.5, 0, 0);
        p.input(new MoveInput(0f, 1f, false, false));
        assertFalse(p.step());
        assertEquals(30, p.position().y());
    }
}
