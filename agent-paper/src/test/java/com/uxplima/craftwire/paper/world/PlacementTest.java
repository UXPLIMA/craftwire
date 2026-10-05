package com.uxplima.craftwire.paper.world;

import static org.junit.jupiter.api.Assertions.*;

import com.uxplima.craftwire.core.AgentError;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.junit.jupiter.api.Test;

class PlacementTest {
    @Test
    void noRotationKeepsTheBoxAtTheOrigin() {
        assertEquals(new Box(10, 5, 10, 12, 6, 13),
                Placement.footprint(10, 5, 10, 3, 2, 4, StructureRotation.NONE, Mirror.NONE));
    }

    @Test
    void clockwise90TurnsAroundTheOrigin() {
        // vanilla: (x, z) -> (-z, x); the far corner (2, 3) lands at (-3, 2)
        assertEquals(new Box(7, 5, 10, 10, 6, 12),
                Placement.footprint(10, 5, 10, 3, 2, 4, StructureRotation.CLOCKWISE_90, Mirror.NONE));
    }

    @Test
    void counterclockwise90AndHalfTurn() {
        assertEquals(new Box(10, 0, 8, 13, 0, 10), Placement.footprint(10, 0, 10, 3, 1, 4, StructureRotation.COUNTERCLOCKWISE_90, Mirror.NONE));
        assertEquals(new Box(8, 0, 7, 10, 0, 10), Placement.footprint(10, 0, 10, 3, 1, 4, StructureRotation.CLOCKWISE_180, Mirror.NONE));
    }

    @Test
    void mirrorFrontBackFlipsX() {
        assertEquals(new Box(-2, 0, 0, 0, 0, 0), Placement.footprint(0, 0, 0, 3, 1, 1, StructureRotation.NONE, Mirror.FRONT_BACK));
    }

    @Test
    void namesRejectTraversal() {
        assertEquals("plains_house-2", Placement.checkName("plains_house-2"));
        for (String bad : new String[] {"../x", "a/b", "a\\b", "", "x".repeat(65), "c:evil"}) {
            AgentError e = assertThrows(AgentError.class, () -> Placement.checkName(bad), bad);
            assertEquals("INVALID_PARAMS", e.code());
        }
    }

    @Test
    void parsesToolRotationAndMirrorNames() {
        assertEquals(StructureRotation.CLOCKWISE_90, Placement.rotation("clockwise_90"));
        assertEquals(StructureRotation.NONE, Placement.rotation("none"));
        assertEquals(Mirror.LEFT_RIGHT, Placement.mirror("left_right"));
        assertThrows(AgentError.class, () -> Placement.rotation("sideways"));
    }
}
