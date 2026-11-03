package com.uxplima.craftwire.botrunner.bedrock;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Break times against the Minecraft Wiki (seconds x 20, rounded up to whole ticks): its breaking-time tables, and
 * for obsidian the Bedrock hardness of its infobox (35, where Java has 50) through the same formula.
 */
class BedrockDigTimeTest {
    @ParameterizedTest(name = "{0} with {1}: {2} ticks")
    @CsvSource({
            "minecraft:stone, , 150",                              // 7.5 s by hand: needs a pickaxe
            "minecraft:stone, minecraft:wooden_pickaxe, 23",       // 1.15 s
            "minecraft:stone, minecraft:copper_pickaxe, 9",        // 0.45 s
            "minecraft:stone, minecraft:diamond_pickaxe, 6",       // 0.3 s
            "minecraft:stone, minecraft:golden_pickaxe, 4",        // 0.2 s
            "minecraft:dirt, , 15",                                // 0.75 s: no tool needed
            "minecraft:dirt, minecraft:iron_shovel, 3",            // 0.15 s
            "minecraft:oak_log, , 60",                             // 3 s
            "minecraft:oak_log, minecraft:stone_axe, 15",          // 0.75 s
            "minecraft:obsidian, minecraft:diamond_pickaxe, 132",  // Bedrock hardness 35: 6.56 s
            "minecraft:iron_ore, minecraft:stone_pickaxe, 23",     // 1.15 s
            "minecraft:diamond_ore, , 300",                        // 15 s
            "minecraft:oak_log, minecraft:diamond_pickaxe, 60",    // the wrong tool is no faster than a hand
            "minecraft:web, , 400",                                // cobweb, 20 s by hand
            "minecraft:web, minecraft:shears, 8",                  // 0.4 s
            "minecraft:web, minecraft:iron_sword, 8",              // 0.4 s
            "minecraft:oak_leaves, , 6",                           // 0.3 s
            "minecraft:oak_leaves, minecraft:shears, 0",           // instant
    })
    void matchesTheWiki(String block, String tool, int ticks) {
        assertEquals(ticks, BedrockDigTime.ticks(block, tool));
    }

    @ParameterizedTest
    @CsvSource({"minecraft:bedrock", "minecraft:barrier", "minecraft:command_block"})
    void unbreakableBlocksNeverBreak(String block) {
        assertEquals(-1, BedrockDigTime.ticks(block, "minecraft:netherite_pickaxe"));
    }
}
