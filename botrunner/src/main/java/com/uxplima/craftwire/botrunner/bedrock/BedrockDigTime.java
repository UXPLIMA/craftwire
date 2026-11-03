package com.uxplima.craftwire.botrunner.bedrock;

import com.uxplima.craftwire.bedrock.codec.BlockPalette;
import com.uxplima.craftwire.bot.path.Cell;
import java.util.Map;

/**
 * How long a Bedrock block takes to break, by the vanilla formula: each tick deals speed / hardness / 30 when the
 * tool can harvest the block, / 100 when it cannot, and the block breaks when the damage reaches 1. Hardness,
 * material and harvest tools come from {@link BedrockHardness}; a block it lacks (a custom block) is estimated by
 * family. The server has the final word, and the dig waits for its block update. Pure functions.
 */
final class BedrockDigTime {
    /** Hardness below zero: the block never breaks in survival. */
    static final double UNBREAKABLE = -1;

    private BedrockDigTime() {}

    static int ticks(String block, String tool) {
        String name = strip(block);
        // The table gives air -1 (it cannot be mined); digging air has nothing to wait for.
        if (name.equals("air")) return 0;
        BedrockHardness.Block known = BedrockHardness.block(name);
        return known != null ? ticks(known, tool == null ? null : strip(tool)) : estimate(name, tool);
    }

    /** A block from the table: unbreakable below zero hardness, instant at zero or when one tick's damage is enough. */
    static int ticks(BedrockHardness.Block b, String tool) {
        if (b.hardness() < 0) return -1;
        if (b.hardness() == 0) return 0;
        boolean harvests = b.tools().isEmpty() || (tool != null && b.tools().contains(tool));
        // 1 / damage, worked out without the division chain so whole tick counts stay whole (15, not 15.000000000000002).
        double ticks = b.hardness() * (harvests ? 30 : 100) / BedrockHardness.speed(b.material(), tool);
        return ticks <= 1 ? 0 : (int) Math.ceil(ticks - 1e-9);
    }

    /** The family estimate for a block the table lacks. */
    private static int estimate(String name, String tool) {
        double h = hardness(name);
        if (h < 0) return -1;
        if (h == 0) return 0;
        String family = family(name);
        boolean right = family != null && tool != null && tool.endsWith("_" + family);
        boolean needsTool = family != null && family.equals("pickaxe");
        double speed = right ? speed(tool) : 1;
        double seconds = h * (needsTool && !right ? 5 : 1.5) / speed;
        return (int) Math.ceil(seconds * 20);
    }

    private static String strip(String id) {
        return id.startsWith("minecraft:") ? id.substring(10) : id;
    }

    static double hardness(String b) {
        if (b.equals("bedrock") || b.equals("barrier") || b.equals("end_portal_frame") || b.contains("command_block")
                || b.equals("structure_block") || b.startsWith("light_block") || b.equals("end_gateway") || b.equals("portal")) {
            return UNBREAKABLE;
        }
        if (b.contains("obsidian") || b.equals("netherite_block") || b.equals("respawn_anchor")) return 50;
        if (b.equals("ancient_debris")) return 30;
        if (b.startsWith("deepslate_") && b.endsWith("_ore")) return 4.5;
        if (b.endsWith("_ore")) return 3;
        if (b.contains("deepslate")) return 3.5;
        if (b.endsWith("_leaves") || b.equals("leaves") || b.equals("leaves2")) return 0.2;
        if (b.contains("glass")) return 0.3;
        if (b.endsWith("wool")) return 0.8;
        if (b.contains("snow")) return 0.2;
        if (b.equals("iron_block") || b.equals("diamond_block") || b.equals("emerald_block") || b.equals("gold_block")) return 5;
        if (b.equals("furnace") || b.equals("lit_furnace") || b.equals("dispenser") || b.equals("dropper")) return 3.5;
        if (b.equals("chest") || b.equals("trapped_chest") || b.equals("crafting_table") || b.equals("barrel")) return 2.5;
        if (b.endsWith("_log") || b.endsWith("_wood") || b.endsWith("_planks") || b.endsWith("_stem") || b.endsWith("hyphae")) return 2;
        if (b.equals("dirt") || b.equals("grass_block") || b.equals("sand") || b.equals("gravel") || b.equals("farmland")
                || b.equals("mud") || b.equals("clay") || b.equals("soul_sand") || b.equals("soul_soil") || b.equals("podzol")) {
            return 0.6;
        }
        if (BedrockCells.classify(new BlockPalette.State("minecraft:" + b, Map.of())).kind() == Cell.Kind.AIR) return 0;
        return 1.5;
    }

    /** The tool that mines a block family fast: pickaxe, axe or shovel; null for anything else. */
    private static String family(String b) {
        if (b.endsWith("_log") || b.endsWith("_wood") || b.endsWith("_planks") || b.endsWith("_stem") || b.contains("chest")
                || b.equals("crafting_table") || b.equals("barrel")) {
            return "axe";
        }
        if (b.equals("dirt") || b.equals("grass_block") || b.equals("sand") || b.equals("gravel") || b.equals("farmland")
                || b.equals("mud") || b.equals("clay") || b.contains("snow") || b.startsWith("soul_") || b.equals("podzol")) {
            return "shovel";
        }
        if (b.endsWith("_leaves") || b.contains("glass") || b.endsWith("wool")) return null;
        return "pickaxe";
    }

    private static double speed(String tool) {
        if (tool.startsWith("minecraft:wooden_")) return 2;
        if (tool.startsWith("minecraft:stone_")) return 4;
        if (tool.startsWith("minecraft:iron_")) return 6;
        if (tool.startsWith("minecraft:diamond_")) return 8;
        if (tool.startsWith("minecraft:netherite_")) return 9;
        if (tool.startsWith("minecraft:golden_")) return 12;
        return 1;
    }
}
