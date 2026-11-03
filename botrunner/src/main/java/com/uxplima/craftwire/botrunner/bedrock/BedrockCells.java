package com.uxplima.craftwire.botrunner.bedrock;

import com.uxplima.craftwire.bedrock.codec.BlockPalette;
import com.uxplima.craftwire.bot.path.Cell;
import java.util.Map;
import java.util.Set;

/**
 * What a Bedrock block state means to a walking player, from its name and properties. The Bedrock client's collision
 * shapes are not in the protocol, so this goes by name the way the plugin bot goes by block class: plants, torches,
 * signs and rails have no collision; slabs, carpets, snow layers and the like are low; fences and walls are tall;
 * everything else is a full block. Pure functions.
 */
final class BedrockCells {
    private static final Set<String> DANGER = Set.of("lava", "flowing_lava", "fire", "soul_fire", "magma", "cactus", "sweet_berry_bush",
            "powder_snow", "wither_rose", "campfire", "soul_campfire");
    private static final Set<String> WATER = Set.of("water", "flowing_water", "bubble_column");
    private static final Set<String> CLIMB = Set.of("ladder", "vine", "scaffolding", "twisting_vines", "weeping_vines", "cave_vines",
            "cave_vines_body_with_berries", "cave_vines_head_with_berries");
    private static final Set<String> AIR = Set.of("air", "structure_void", "light_block", "short_grass", "tall_grass", "fern", "large_fern",
            "deadbush", "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip", "pink_tulip",
            "oxeye_daisy", "cornflower", "lily_of_the_valley", "torchflower", "sunflower", "lilac", "rose_bush", "peony", "pitcher_plant",
            "brown_mushroom", "red_mushroom", "crimson_fungus", "warped_fungus", "crimson_roots", "warped_roots", "nether_sprouts",
            "wheat", "carrots", "potatoes", "beetroot", "melon_stem", "pumpkin_stem", "nether_wart", "reeds", "seagrass", "kelp",
            "torch", "redstone_torch", "unlit_redstone_torch", "soul_torch", "redstone_wire", "tripwire", "tripwire_hook", "lever", "rail",
            "golden_rail", "detector_rail", "activator_rail", "web", "portal", "end_portal", "end_gateway", "glow_lichen", "sculk_vein",
            "hanging_roots", "spore_blossom", "pink_petals", "wildflowers", "leaf_litter", "bush", "firefly_bush", "short_dry_grass",
            "tall_dry_grass", "resin_clump", "small_dripleaf_block", "frog_spawn", "torchflower_crop", "pitcher_crop");
    private static final String[] AIR_SUFFIXES = {"_sapling", "_torch", "_sign", "_hanging_sign", "_pressure_plate", "_button", "_banner",
            "_coral", "_coral_fan", "_coral_wall_fan", "_propagule", "_vines"};
    private static final Map<String, Double> LOW = Map.ofEntries(
            Map.entry("daylight_detector", 0.375), Map.entry("daylight_detector_inverted", 0.375), Map.entry("bed", 0.5625),
            Map.entry("waterlily", 0.09375), Map.entry("stonecutter_block", 0.5625), Map.entry("cake", 0.5),
            Map.entry("unpowered_repeater", 0.125), Map.entry("powered_repeater", 0.125), Map.entry("unpowered_comparator", 0.125),
            Map.entry("powered_comparator", 0.125), Map.entry("flower_pot", 0.375), Map.entry("skull", 0.5),
            Map.entry("sea_pickle", 0.375), Map.entry("calibrated_sculk_sensor", 0.5), Map.entry("sculk_sensor", 0.5),
            Map.entry("sculk_shrieker", 0.5), Map.entry("candle_cake", 0.5), Map.entry("lectern", 0.875));

    private BedrockCells() {}

    static Cell classify(BlockPalette.State s) {
        String name = s.name().startsWith("minecraft:") ? s.name().substring(10) : s.name();
        Map<String, Object> p = s.properties();
        if (DANGER.contains(name)) return Cell.DANGER;
        if (WATER.contains(name)) return Cell.WATER;
        if (CLIMB.contains(name)) return Cell.CLIMB;
        if (name.endsWith("_door") || name.equals("wooden_door")) {
            if (flag(p, "open_bit")) return Cell.AIR;
            return name.equals("iron_door") ? Cell.SOLID : Cell.DOOR;
        }
        if (name.endsWith("fence_gate")) return flag(p, "open_bit") ? Cell.AIR : Cell.DOOR;
        if (name.endsWith("_trapdoor") || name.equals("trapdoor")) {
            if (flag(p, "open_bit")) return Cell.AIR;
            return flag(p, "upside_down_bit") ? Cell.SOLID : Cell.low(0.1875);
        }
        if (AIR.contains(name) || hasAirSuffix(name)) return Cell.AIR;
        if (name.endsWith("_fence") || name.endsWith("_wall") || name.equals("border_block")) return Cell.TALL;
        if (name.endsWith("_slab")) return top(p) ? Cell.SOLID : Cell.low(0.5);
        if (name.endsWith("carpet")) return Cell.low(0.0625);
        if (name.equals("snow_layer")) return Cell.low((number(p, "height") + 1) / 8.0);
        if (name.endsWith("candle")) return Cell.AIR;
        Double low = LOW.get(name);
        return low != null ? Cell.low(low) : Cell.SOLID;
    }

    private static boolean hasAirSuffix(String name) {
        for (String s : AIR_SUFFIXES) if (name.endsWith(s)) return true;
        return false;
    }

    /** A slab in the top half reaches the top of its block, so it is stood on like a full block. */
    private static boolean top(Map<String, Object> p) {
        return "top".equals(p.get("minecraft:vertical_half")) || flag(p, "top_slot_bit");
    }

    private static boolean flag(Map<String, Object> p, String key) {
        return number(p, key) != 0;
    }

    private static int number(Map<String, Object> p, String key) {
        Object v = p.get(key);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof Boolean b) return b ? 1 : 0;
        return 0;
    }
}
