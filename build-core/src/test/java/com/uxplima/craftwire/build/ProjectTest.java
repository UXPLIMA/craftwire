package com.uxplima.craftwire.build;

import static org.junit.jupiter.api.Assertions.*;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectTest {
    static final int AIR = 0, STONE = 1, GOLD = 2, DIRT = 3;
    final Project project = new Project("p", "world", Transform.IDENTITY);
    /** The world: positions not in the map are air. */
    final Map<Long, Integer> world = new HashMap<>();

    static LongIntMap blocks(int state, int... xs) {
        LongIntMap m = new LongIntMap();
        for (int x : xs) m.put(Pos.pack(x, 0, 0), state);
        return m;
    }

    /** What the server does with a stage: record originals, write what differs, count it. */
    int run(String part, LongIntMap next) {
        Changes c = project.stage(part, next);
        int[] written = {0};
        c.targets().forEach((pos, target) -> {
            int current = world.getOrDefault(pos, AIR);
            project.recordOriginal(pos, current);
            if (current != target) {
                world.put(pos, target);
                written[0]++;
            }
        });
        project.commit(part, next, new Project.Source("code", null), true);
        return written[0];
    }

    int at(int x) {
        return world.getOrDefault(Pos.pack(x, 0, 0), AIR);
    }

    @Test
    void firstRunWritesEverything() {
        assertEquals(3, run("walls", blocks(STONE, 0, 1, 2)));
    }

    @Test
    void rerunSameBufferWritesNothing() {
        run("walls", blocks(STONE, 0, 1, 2));
        assertEquals(0, project.stage("walls", blocks(STONE, 0, 1, 2)).size());
    }

    @Test
    void rerunWithOneChangedBlockWritesOne() {
        run("walls", blocks(STONE, 0, 1, 2));
        LongIntMap next = blocks(STONE, 0, 1, 2);
        next.put(Pos.pack(1, 0, 0), GOLD);
        assertEquals(1, project.stage("walls", next).size());
    }

    @Test
    void shrinkingAPartRestoresTheOriginal() {
        world.put(Pos.pack(2, 0, 0), DIRT);
        run("walls", blocks(STONE, 0, 1, 2));
        Changes c = project.stage("walls", blocks(STONE, 0, 1));
        assertEquals(1, c.size());
        assertEquals(1, c.removed());
        assertEquals(DIRT, c.targets().get(Pos.pack(2, 0, 0)));
    }

    @Test
    void removingUpperPartRevealsLowerPart() {
        run("lower", blocks(STONE, 0, 1));
        run("upper", blocks(GOLD, 1, 2));
        assertEquals(GOLD, at(1));
        run("upper", null);
        assertEquals(STONE, at(1));
        assertEquals(AIR, at(2));
        assertEquals(java.util.List.of("lower"), project.parts());
    }

    @Test
    void removingLowerPartUnderUpperWritesNothingThere() {
        run("lower", blocks(STONE, 0, 1));
        run("upper", blocks(GOLD, 1));
        Changes c = project.stage("lower", null);
        assertFalse(c.targets().containsKey(Pos.pack(1, 0, 0)));
        assertEquals(AIR, c.targets().get(Pos.pack(0, 0, 0)));
    }

    @Test
    void undoPutsThePreviousRunBack() {
        run("walls", blocks(STONE, 0, 1));
        run("walls", blocks(GOLD, 0, 1, 2));
        Project.History h = project.popHistory();
        assertEquals("walls", h.part());
        Changes c = project.stage(h.part(), h.previous());
        c.targets().forEach((pos, t) -> world.put(pos, t));
        project.commit(h.part(), h.previous(), h.previousSource(), false);
        assertEquals(STONE, at(0));
        assertEquals(AIR, at(2));
        assertEquals(1, project.history().size());
    }

    @Test
    void undoOfAFirstRunRemovesThePart() {
        run("walls", blocks(STONE, 0));
        Project.History h = project.popHistory();
        assertNull(h.previous());
        Changes c = project.stage(h.part(), null);
        assertEquals(AIR, c.targets().get(Pos.pack(0, 0, 0)));
    }

    @Test
    void clearRestoresTheOriginalEverywhere() {
        world.put(Pos.pack(0, 0, 0), DIRT);
        run("a", blocks(STONE, 0, 1));
        run("b", blocks(GOLD, 1, 2));
        Changes c = project.clearAll();
        c.targets().forEach((pos, t) -> world.put(pos, t));
        assertEquals(DIRT, at(0));
        assertEquals(AIR, at(1));
        assertEquals(AIR, at(2));
    }

    @Test
    void changesGroupByChunk() {
        LongIntMap m = new LongIntMap();
        m.put(Pos.pack(1, 0, 1), STONE);
        m.put(Pos.pack(17, 0, 1), STONE);
        m.put(Pos.pack(18, 5, 2), STONE);
        Map<Long, long[]> by = project.stage("x", m).byChunk();
        assertEquals(2, by.size());
        assertEquals(2, by.get(Changes.chunkKey(1, 0)).length);
    }
}
