package io.github.apperk274.journeymen.entity.ai;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

public class BuilderNodeEvaluator extends WalkNodeEvaluator {

    @Override
    public PathType getPathType(PathfindingContext context, int x, int y, int z) {
        BlockState state = context.getBlockState(new BlockPos(x, y, z));
        if (state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)) {
            return PathType.DOOR_WOOD_CLOSED;
        }
        return super.getPathType(context, x, y, z);
    }
}
