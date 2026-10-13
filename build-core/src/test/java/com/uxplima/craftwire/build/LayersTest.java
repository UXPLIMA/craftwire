package com.uxplima.craftwire.build;

import static org.junit.jupiter.api.Assertions.*;
import java.util.Map;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class LayersTest {
    final FakePalette pal = new FakePalette();

    @Test
    void placesLettersRowsAndLevels() {
        RecordingPlacer p = new RecordingPlacer();
        int stone = pal.id("stone"), glass = pal.id("glass");
        Layers.place(p, 10, 0, 20, Map.of('s', Picks.of(stone), 'g', Picks.of(glass)), new String[] {"ss/s~", "g./.g"}, pal.air());
        assertEquals(stone, p.at(10, 0, 20));
        assertEquals(stone, p.at(11, 0, 20));
        assertEquals(stone, p.at(10, 0, 21));
        assertEquals(LongIntMap.NONE, p.at(11, 0, 21));
        assertEquals(glass, p.at(10, 1, 20));
        assertEquals(pal.air(), p.at(11, 1, 20));
    }

    @Test
    void acceptsMultiLineLayers() {
        RecordingPlacer p = new RecordingPlacer();
        int stone = pal.id("stone");
        Layers.place(p, 0, 0, 0, Map.of('s', Picks.of(stone)), new String[] {"\n      ss\n      s.\n    "}, pal.air());
        assertEquals(stone, p.at(0, 0, 1));
        assertEquals(pal.air(), p.at(1, 0, 1));
    }

    @Test
    void unknownLetterNamesItsPlace() {
        RecordingPlacer p = new RecordingPlacer();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Layers.place(p, 0, 0, 0, Map.of(), new String[] {"../.x"}, pal.air()));
        assertTrue(e.getMessage().contains("'x'") && e.getMessage().contains("layer 0") && e.getMessage().contains("row 1"), e.getMessage());
    }

    @Test
    void encodeThenPlaceRebuildsTheRegion() {
        String[] blocks = {"stone", "glass", "oak_planks", "oak_stairs[facing=east]", "air"};
        int[] ids = new int[blocks.length];
        for (int i = 0; i < blocks.length; i++) ids[i] = pal.id(blocks[i]);
        SplittableRandom r = new SplittableRandom(9);
        int sx = 4, sy = 3, sz = 5;
        int[][][] region = new int[sy][sz][sx];
        for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) region[y][z][x] = ids[r.nextInt(ids.length)];
        Layers.Encoded e = Layers.encode(sx, sy, sz, (dx, dy, dz) -> region[dy][dz][dx], pal::name, pal.air());
        Map<Character, BlockPick> legend = new java.util.HashMap<>();
        e.legend().forEach((letter, name) -> legend.put(letter.charAt(0), Picks.of(pal.id(name))));
        RecordingPlacer p = new RecordingPlacer();
        Layers.place(p, 0, 0, 0, legend, e.layers().toArray(String[]::new), pal.air());
        for (int y = 0; y < sy; y++) for (int z = 0; z < sz; z++) for (int x = 0; x < sx; x++) assertEquals(region[y][z][x], p.at(x, y, z));
    }

    @Test
    void notLoadedIsAQuestionMark() {
        Layers.Encoded e = Layers.encode(2, 1, 1, (dx, dy, dz) -> dx == 0 ? -2 : 0, pal::name, pal.air());
        assertEquals("?.", e.layers().get(0));
    }
}
