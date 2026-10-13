package com.uxplima.craftwire.build;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ShapesTest {
    static final int AIR = 0, STONE = 1;
    final BlockPick stone = Picks.of(STONE);
    final RecordingPlacer p = new RecordingPlacer();

    @Test
    void fillTakesCornersInAnyOrder() {
        Shapes.fill(p, 2, 2, 2, 0, 0, 0, stone);
        assertEquals(27, p.count());
    }

    @Test
    void hollowClearsTheInside() {
        Shapes.hollow(p, 0, 0, 0, 4, 4, 4, stone, AIR, true, true);
        assertEquals(125, p.count());
        assertEquals(AIR, p.at(2, 2, 2));
        assertEquals(STONE, p.at(0, 2, 2));
        assertEquals(STONE, p.at(2, 0, 2));
    }

    @Test
    void hollowWithoutCeilingLeavesTheTopOpen() {
        Shapes.hollow(p, 0, 0, 0, 4, 4, 4, stone, AIR, true, false);
        assertEquals(AIR, p.at(2, 4, 2));
    }

    @Test
    void wallsAreOnlyTheFourSides() {
        Shapes.walls(p, 0, 0, 0, 4, 2, 4, stone);
        assertEquals(16 * 3, p.count());
        assertEquals(LongIntMap.NONE, p.at(2, 1, 2));
    }

    @Test
    void outlineIsTheTwelveEdges() {
        Shapes.outline(p, 0, 0, 0, 2, 2, 2, stone);
        assertEquals(20, p.count());
    }

    @Test
    void sphereOfRadiusOne() {
        Shapes.sphere(p, 0, 0, 0, 1, stone, false, 0);
        assertEquals(19, p.count());
    }

    @Test
    void hollowSphereHasNoCentre() {
        Shapes.sphere(p, 0, 0, 0, 3, stone, true, 0);
        assertEquals(LongIntMap.NONE, p.at(0, 0, 0));
        assertEquals(STONE, p.at(3, 0, 0));
    }

    @Test
    void topHalfSphereStaysAboveCentre() {
        Shapes.sphere(p, 0, 0, 0, 3, stone, false, 1);
        p.placed.forEach((k, v) -> assertTrue(Pos.y(k) >= 0));
    }

    @Test
    void ellipsoidIsStretched() {
        Shapes.ellipsoid(p, 0, 0, 0, 4, 1, 1, stone, false);
        assertEquals(STONE, p.at(4, 0, 0));
        assertEquals(LongIntMap.NONE, p.at(0, 2, 0));
    }

    @Test
    void thinCylinderIsAColumn() {
        Shapes.cylinder(p, 0, 0, 0, 0, 3, stone, false, 'y');
        assertEquals(3, p.count());
    }

    @Test
    void cylinderAlongXRunsEast() {
        Shapes.cylinder(p, 0, 5, 0, 1, 4, stone, false, 'x');
        assertEquals(STONE, p.at(3, 5, 0));
        assertEquals(STONE, p.at(0, 6, 0));
        assertEquals(LongIntMap.NONE, p.at(-1, 5, 0));
    }

    @Test
    void coneNarrowsUpward() {
        Shapes.cone(p, 0, 0, 0, 3, 4, stone, false);
        assertEquals(STONE, p.at(3, 0, 0));
        assertEquals(LongIntMap.NONE, p.at(3, 3, 0));
        assertEquals(STONE, p.at(0, 3, 0));
    }

    @Test
    void diagonalLine() {
        Shapes.line(p, 0, 0, 0, 3, 3, 0, stone, 1);
        assertEquals(4, p.count());
        assertEquals(STONE, p.at(2, 2, 0));
    }

    @Test
    void wideLineIsThicker() {
        Shapes.line(p, 0, 0, 0, 4, 0, 0, stone, 3);
        assertEquals(7 * 9, p.count());   // a 3x3x3 brush also reaches one block past both ends
    }

    @Test
    void archPeaksInTheMiddle() {
        Shapes.arch(p, 0, 10, 0, 6, 0, 3, stone, 1);
        assertEquals(STONE, p.at(3, 13, 0));
        assertEquals(LongIntMap.NONE, p.at(3, 10, 0));
    }

    @Test
    void archMustRunAlongOneAxis() {
        assertThrows(IllegalArgumentException.class, () -> Shapes.arch(p, 0, 0, 0, 4, 4, 3, stone, 1));
    }

    @Test
    void polygonFillsItsCells() {
        Shapes.polygon(p, new int[] {0, 3, 3, 0}, new int[] {0, 0, 3, 3}, 0, 2, stone, false);
        assertEquals(9 * 2, p.count());
    }
}
