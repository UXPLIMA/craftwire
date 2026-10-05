package com.uxplima.craftwire.paper.world;

import java.util.function.Predicate;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

/** Matches by id ("chest") or by state ("oak_stairs[facing=east]": only the listed properties must match). */
public final class BlockMatcher implements Predicate<BlockData> {
    private final Material material;
    private final BlockData state;

    private BlockMatcher(Material material, BlockData state) {
        this.material = material;
        this.state = state;
    }

    public static BlockMatcher parse(String spec) {
        if (spec.contains("[")) return new BlockMatcher(null, Blocks.parse(spec));
        Material m = Material.matchMaterial(spec.strip());
        if (m == null || !m.isBlock()) throw Blocks.unknown(spec);
        return new BlockMatcher(m, null);
    }

    @Override
    public boolean test(BlockData data) {
        return material != null ? data.getMaterial() == material : data.matches(state);
    }
}
