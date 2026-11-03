package com.uxplima.craftwire.botrunner.javabot.world;

import com.uxplima.craftwire.bot.path.Cell;
import com.uxplima.craftwire.botrunner.world.BlockClassifier;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Every block state of the version as a pathfinding {@link Cell}, worked out once from the vanilla blocks: collision
 * shape heights, fluids, doors and gates, climbables and what hurts. The same rules as the plugin bot's block view,
 * by block class and constant instead of tags, because the runner's registries carry no data-pack tags. Read-only
 * after construction, shared by all bots of the version.
 */
public final class JavaBlocks implements BlockClassifier {
    private static final Set<Block> DANGER = Set.of(Blocks.MAGMA_BLOCK, Blocks.CACTUS, Blocks.SWEET_BERRY_BUSH, Blocks.POWDER_SNOW,
            Blocks.WITHER_ROSE, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.LAVA_CAULDRON);
    private static final Set<Block> CLIMB = Set.of(Blocks.LADDER, Blocks.VINE, Blocks.SCAFFOLDING, Blocks.TWISTING_VINES,
            Blocks.TWISTING_VINES_PLANT, Blocks.WEEPING_VINES, Blocks.WEEPING_VINES_PLANT, Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT);

    private final Cell[] cells;

    public JavaBlocks() {
        cells = new Cell[Block.BLOCK_STATE_REGISTRY.size()];
        for (BlockState s : Block.BLOCK_STATE_REGISTRY) cells[Block.BLOCK_STATE_REGISTRY.getId(s)] = classifyState(s);
    }

    @Override
    public Cell classify(int state) {
        return state >= 0 && state < cells.length && cells[state] != null ? cells[state] : Cell.UNKNOWN;
    }

    @Override
    public String blockId(int state) {
        BlockState s = Block.BLOCK_STATE_REGISTRY.byId(state);
        return s == null ? "minecraft:air" : BuiltInRegistries.BLOCK.getKey(s.getBlock()).toString();
    }

    public static int id(BlockState s) {
        return Block.BLOCK_STATE_REGISTRY.getId(s);
    }

    private static Cell classifyState(BlockState s) {
        FluidState fluid = s.getFluidState();
        Block b = s.getBlock();
        if (fluid.getType().isSame(Fluids.LAVA) || b instanceof BaseFireBlock || DANGER.contains(b)) return Cell.DANGER;
        if (b instanceof DoorBlock door) {
            if (s.getValue(BlockStateProperties.OPEN)) return Cell.AIR;
            return door.type().canOpenByHand() ? Cell.DOOR : Cell.SOLID;
        }
        if (b instanceof FenceGateBlock) return s.getValue(BlockStateProperties.OPEN) ? Cell.AIR : Cell.DOOR;
        if (CLIMB.contains(b)) return Cell.CLIMB;
        VoxelShape shape;
        try {
            shape = s.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            // A shape that reads its neighbours has none to read here; treat it as a full block.
            return Cell.SOLID;
        }
        if (shape.isEmpty()) return fluid.getType().isSame(Fluids.WATER) ? Cell.WATER : Cell.AIR;
        double top = shape.max(Direction.Axis.Y);
        if (top > 1.0) return Cell.TALL;
        if (top <= 0.6) return Cell.low(top);
        return Cell.SOLID;
    }
}
