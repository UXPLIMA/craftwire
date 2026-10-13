package com.uxplima.craftwire.build;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class BuildContextTest {
    final FakePalette pal = new FakePalette();
    final WorldReader flat = new WorldReader() {
        @Override public int read(int x, int y, int z) { return y <= 64 ? pal.id("grass_block") : pal.air(); }
        @Override public int ground(int x, int z) { return 64; }
    };

    BuildContext ctx(Transform root) {
        BuildContext c = new BuildContext(pal, flat, root, 1_000, 7);
        c.startPart();
        return c;
    }

    @Test
    void placesThroughTheProjectFrame() {
        BuildContext c = ctx(new Transform(100, 64, 100, 1, false));
        new BuildApi(c).set(0, 1, -1, new BuildApi(c).block("oak_stairs[facing=north]"));
        assertEquals("minecraft:oak_stairs[facing=east]", pal.name(c.buffer().get(101, 65, 100)));
    }

    @Test
    void unknownBlocksAreCollectedNotThrown() {
        BuildContext c = ctx(Transform.IDENTITY);
        BuildApi api = new BuildApi(c);
        api.set(0, 0, 0, api.block("stone_brick"));
        api.set(1, 0, 0, api.block("stone_brick"));
        assertEquals(1, c.bad().size());
        assertTrue(c.bad().message().contains("stone_brick") && c.bad().message().contains("minecraft:stone_bricks"), c.bad().message());
    }

    @Test
    void readsSeeThisRunsPlacementsFirst() {
        BuildContext c = ctx(new Transform(0, 64, 0, 0, false));
        BuildApi api = new BuildApi(c);
        assertEquals("minecraft:grass_block", api.get(0, 0, 0));
        api.set(0, 0, 0, api.block("gold_block"));
        assertEquals("minecraft:gold_block", api.get(0, 0, 0));
        assertEquals(0, api.ground(5, 5));   // ground is relative to the frame (y 64)
    }

    @Test
    void replaceMatchesByIdOrExactState() {
        BuildContext c = ctx(Transform.IDENTITY);
        BuildApi api = new BuildApi(c);
        api.fill(0, 0, 0, 2, 0, 0, api.block("oak_stairs[facing=north]"));
        api.set(1, 0, 0, api.block("oak_stairs[facing=south]"));
        api.replace(0, 0, 0, 2, 0, 0, "oak_stairs[facing=south]", api.block("gold_block"));
        assertEquals("minecraft:gold_block", pal.name(c.buffer().get(1, 0, 0)));
        assertEquals("minecraft:oak_stairs[facing=north]", pal.name(c.buffer().get(0, 0, 0)));
        api.replace(0, 0, 0, 2, 0, 0, "oak_stairs", api.block("stone"));
        assertEquals("minecraft:stone", pal.name(c.buffer().get(2, 0, 0)));
    }

    @Test
    void framesNest() {
        BuildContext c = ctx(Transform.IDENTITY);
        BuildApi api = new BuildApi(c);
        api.push(10, 0, 0, 0, "");
        api.push(0, 0, 0, 1, "");
        api.set(0, 0, -1, api.block("stone"));
        api.pop();
        api.pop();
        assertEquals(pal.id("stone"), c.buffer().get(11, 0, 0));
        assertThrows(IllegalStateException.class, api::pop);
    }
}
