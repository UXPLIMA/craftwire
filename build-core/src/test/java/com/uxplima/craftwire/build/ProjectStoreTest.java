package com.uxplima.craftwire.build;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectStoreTest {
    @TempDir Path dir;
    final FakePalette pal = new FakePalette();

    Project sample() {
        Project p = new Project("tower", "world", new Transform(100, 64, -20, 1, true));
        LongIntMap walls = new LongIntMap();
        walls.put(Pos.pack(100, 64, -20), pal.id("stone_bricks"));
        walls.put(Pos.pack(101, 64, -20), pal.id("oak_stairs[facing=east]"));
        p.recordOriginal(Pos.pack(100, 64, -20), pal.id("grass_block"));
        p.recordOriginal(Pos.pack(101, 64, -20), pal.air());
        p.commit("walls", walls, new Project.Source("fill(0,0,0,1,0,0,'stone')", "tower.build.js"), true);
        LongIntMap walls2 = walls.copy();
        walls2.put(Pos.pack(102, 64, -20), pal.id("glass"));
        p.recordOriginal(Pos.pack(102, 64, -20), pal.air());
        p.commit("walls", walls2, new Project.Source("v2", "tower.build.js"), true);
        return p;
    }

    @Test
    void roundTrip() throws Exception {
        ProjectStore store = new ProjectStore(dir, pal);
        store.save(sample());
        List<Project> loaded = new ProjectStore(dir, pal).loadAll();
        assertEquals(1, loaded.size());
        Project p = loaded.get(0);
        assertEquals("tower", p.name());
        assertEquals(new Transform(100, 64, -20, 1, true), p.frame());
        assertEquals(List.of("walls"), p.parts());
        assertEquals("minecraft:glass", pal.name(p.part("walls").get(Pos.pack(102, 64, -20))));
        assertEquals("v2", p.source("walls").code());
        assertEquals("minecraft:grass_block", pal.name(p.originalOf(Pos.pack(100, 64, -20))));
        assertEquals(2, p.history().size());
        Project.History newest = p.popHistory();
        assertEquals(2, newest.seq());
        assertEquals(2, newest.previous().size());
        assertEquals("fill(0,0,0,1,0,0,'stone')", newest.previousSource().code());
        assertNull(p.popHistory().previous());
    }

    @Test
    void unknownStatesLoadAsAirWithAWarning() throws Exception {
        new ProjectStore(dir, pal).save(sample());
        FakePalette other = new FakePalette() {
            @Override public int id(String spec) {
                if (spec.contains("glass")) throw new IllegalArgumentException("gone");
                return super.id(spec);
            }
        };
        ProjectStore store = new ProjectStore(dir, other);
        Project p = store.loadAll().get(0);
        assertEquals(other.air(), p.part("walls").get(Pos.pack(102, 64, -20)));
        assertTrue(store.warnings().get(0).contains("minecraft:glass"));
    }

    @Test
    void limitDropsOldestHistoryFirstAndKeepsParts() throws Exception {
        ProjectStore store = new ProjectStore(dir, pal);
        Project p = sample();
        store.save(p);
        int dropped = store.enforceLimit(1, Map.of("tower", p));
        assertEquals(2, dropped);
        assertTrue(p.history().isEmpty());
        assertTrue(Files.exists(dir.resolve("tower/parts/walls.bin")));
        assertTrue(Files.exists(dir.resolve("tower/original.bin")));
        assertEquals(0, new ProjectStore(dir, pal).loadAll().get(0).history().size());
    }

    @Test
    void deleteRemovesTheFolder() throws Exception {
        ProjectStore store = new ProjectStore(dir, pal);
        store.save(sample());
        store.delete("tower");
        assertFalse(Files.exists(dir.resolve("tower")));
    }
}
