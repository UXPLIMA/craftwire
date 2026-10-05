package com.uxplima.craftwire.paper.world;

import com.uxplima.craftwire.core.AgentError;
import org.bukkit.Bukkit;
import org.bukkit.block.data.BlockData;

public final class Blocks {
    private Blocks() {}

    /** Parses "stone", "minecraft:stone" or "oak_stairs[facing=east]". */
    public static BlockData parse(String spec) {
        try {
            return Bukkit.createBlockData(spec.strip());
        } catch (IllegalArgumentException e) {
            throw unknown(spec);
        }
    }

    static AgentError unknown(String spec) {
        return new AgentError("INVALID_PARAMS", "Unknown block: " + spec,
                "Use a block id such as minecraft:stone or a state such as oak_stairs[facing=east].");
    }
}
