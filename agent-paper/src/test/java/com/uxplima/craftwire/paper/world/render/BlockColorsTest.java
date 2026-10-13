package com.uxplima.craftwire.paper.world.render;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class BlockColorsTest {
    @Test
    void mostlyTransparentTexturesHaveNoColourSoTheMapColourIsUsed() {
        // The hub writes 0 for flowers and other cut-out textures: drawing that as black left dots all over the grass.
        BlockColors.set(JsonParser.parseString("{\"minecraft:test_flower\":[0,0],\"minecraft:test_stone\":[7895160,7895161]}").getAsJsonObject());
        assertEquals(-1, BlockColors.top("minecraft:test_flower"));
        assertEquals(-1, BlockColors.side("minecraft:test_flower"));
        assertEquals(7895160, BlockColors.top("minecraft:test_stone"));
        assertEquals(7895161, BlockColors.side("minecraft:test_stone"));
        assertEquals(-1, BlockColors.top("minecraft:unknown"));
    }
}
