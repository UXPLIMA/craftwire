package com.uxplima.craftwire.paper.bot.path;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.shapes.VoxelShape;
import java.util.Set;

/** A loaded world as {@link Cell}s. Reads loaded chunks only; never loads or generates one. Server thread only. */
public final class PaperBlockView implements BlockView {
    private static final Set<Block> DANGER = Set.of(Blocks.MAGMA_BLOCK, Blocks.CACTUS, Blocks.SWEET_BERRY_BUSH,
            Blocks.POWDER_SNOW, Blocks.WITHER_ROSE, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.LAVA_CAULDRON);

    private final ServerLevel level;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    public PaperBlockView(ServerLevel level) {
        this.level = level;
    }

    @Override
    public Cell cell(int x, int y, int z) {
        if (y < level.getMinY()) return Cell.UNKNOWN;
        if (y > level.getMaxY()) return Cell.AIR;
        LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
        if (chunk == null) return Cell.UNKNOWN;
        pos.set(x, y, z);
        return classify(chunk.getBlockState(pos));
    }

    private Cell classify(BlockState s) {
        FluidState fluid = s.getFluidState();
        if (fluid.is(FluidTags.LAVA) || s.is(BlockTags.FIRE) || DANGER.contains(s.getBlock())) return Cell.DANGER;
        Block b = s.getBlock();
        if (b instanceof DoorBlock door) {
            if (s.getValue(BlockStateProperties.OPEN)) return Cell.AIR;
            return door.type().canOpenByHand() ? Cell.DOOR : Cell.SOLID;
        }
        if (b instanceof FenceGateBlock) return s.getValue(BlockStateProperties.OPEN) ? Cell.AIR : Cell.DOOR;
        if (s.is(BlockTags.CLIMBABLE)) return Cell.CLIMB;
        VoxelShape shape = s.getCollisionShape(level, pos);
        if (shape.isEmpty()) return fluid.is(FluidTags.WATER) ? Cell.WATER : Cell.AIR;
        double top = shape.max(Direction.Axis.Y);
        if (top > 1.0) return Cell.TALL;
        if (top <= 0.6) return Cell.low(top);
        return Cell.SOLID;
    }
}
