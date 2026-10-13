package com.uxplima.craftwire.build;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class RoofsTest {
    final FakePalette pal = new FakePalette();
    final RecordingPlacer p = new RecordingPlacer();

    String at(int x, int y, int z) {
        int s = p.at(x, y, z);
        return s < 0 ? null : pal.name(s);
    }

    Roofs.Spec spec(String type, String axis, int overhang) {
        return new Roofs.Spec(0, 0, 4, 4, 10, type, axis, overhang, "spruce_stairs", "", null, "north");
    }

    @Test
    void gableAlongXRisesFromNorthAndSouthToARidgeSlab() {
        Roofs.roof(p, pal, spec("gable", "x", 0));
        assertEquals("minecraft:spruce_stairs[facing=south,half=bottom]", at(0, 10, 0));
        assertEquals("minecraft:spruce_stairs[facing=north,half=bottom]", at(0, 10, 4));
        assertEquals("minecraft:spruce_stairs[facing=south,half=bottom]", at(3, 11, 1));
        assertEquals("minecraft:spruce_slab[type=bottom]", at(2, 12, 2));
        assertEquals(25, p.count());
    }

    @Test
    void gableAlongZRisesFromWestAndEast() {
        Roofs.roof(p, pal, spec("gable", "z", 0));
        assertEquals("minecraft:spruce_stairs[facing=east,half=bottom]", at(0, 10, 2));
        assertEquals("minecraft:spruce_stairs[facing=west,half=bottom]", at(4, 10, 2));
    }

    @Test
    void overhangWidensTheRoof() {
        Roofs.roof(p, pal, spec("gable", "x", 1));
        assertNotNull(at(-1, 10, -1));
        assertNotNull(at(5, 10, 5));
    }

    @Test
    void hipRoofHasFourSlopes() {
        Roofs.roof(p, pal, spec("hip", "", 0));
        assertEquals("minecraft:spruce_stairs[facing=south,half=bottom]", at(2, 10, 0));
        assertEquals("minecraft:spruce_stairs[facing=east,half=bottom]", at(0, 10, 2));
        assertEquals("minecraft:spruce_stairs[facing=west,half=bottom]", at(4, 10, 2));
        assertEquals("minecraft:spruce_slab[type=bottom]", at(2, 12, 2));
        assertEquals(25, p.count());
    }

    @Test
    void gableFillClosesTheEnds() {
        Roofs.roof(p, pal, new Roofs.Spec(0, 0, 4, 4, 10, "gable", "x", 0, "spruce_stairs", "", Picks.of(pal.id("spruce_planks")), "north"));
        assertEquals("minecraft:spruce_planks", at(0, 11, 2));
        assertEquals("minecraft:spruce_planks", at(4, 11, 2));
        assertNull(at(2, 11, 2));
    }

    @Test
    void shedRisesTowardItsFacing() {
        Roofs.roof(p, pal, new Roofs.Spec(0, 0, 2, 3, 10, "shed", "", 0, "spruce_stairs", "", null, "north"));
        assertEquals("minecraft:spruce_stairs[facing=north,half=bottom]", at(0, 10, 3));
        assertEquals("minecraft:spruce_stairs[facing=north,half=bottom]", at(0, 13, 0));
    }

    @Test
    void flatRoofIsSlabs() {
        Roofs.roof(p, pal, spec("flat", "", 0));
        assertEquals(25, p.count());
        assertEquals("minecraft:spruce_slab[type=bottom]", at(4, 10, 4));
    }

    @Test
    void staircaseClimbsTowardItsDirection() {
        Roofs.stairs(p, pal, 0, 0, 0, "north", 3, "oak_stairs", 2);
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]", at(0, 0, 0));
        assertEquals("minecraft:oak_stairs[facing=north,half=bottom]", at(1, 2, -2));
        assertEquals(6, p.count());
    }
}
