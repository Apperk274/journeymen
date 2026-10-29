package io.github.apperk274.journeymen.entity.ai;

import io.github.apperk274.journeymen.entity.JourneymanEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Set;

public class BuilderNodeEvaluator extends WalkNodeEvaluator {

    private static final float DEFAULT_STEP = 0.6F;
    private static final float DEFAULT_WIDTH = 0.6F;
    private static final double CLEARANCE = 1.0E-3;
    private static final Set<PathType> STANDING = Set.of(PathType.WALKABLE, PathType.WATER_BORDER, PathType.DANGER_FIRE,
            PathType.DANGER_OTHER, PathType.DAMAGE_CAUTIOUS);

    @Override
    public PathType getPathType(PathfindingContext context, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        BlockState state = context.getBlockState(pos);
        if (mob instanceof JourneymanEntity journeyman && journeyman.isPassageRefused(pos)) {
            return PathType.BLOCKED;
        }
        if (state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)) {
            return PathType.DOOR_WOOD_CLOSED;
        }
        if (partialHeight(context.level(), pos, state) > step()) {
            return PathType.BLOCKED;
        }
        PathType type = super.getPathType(context, x, y, z);
        if (type == PathType.OPEN) {
            BlockPos below = pos.below();
            if (partialHeight(context.level(), below, context.getBlockState(below)) > step()) {
                return checkNeighbourBlocks(context, x, y, z, PathType.WALKABLE);
            }
        }
        return type;
    }

    @Override
    public PathType getPathTypeOfMob(PathfindingContext context, int x, int y, int z, Mob mob) {
        PathType type = super.getPathTypeOfMob(context, x, y, z, mob);
        if (!STANDING.contains(type)) {
            return type;
        }
        double floor = getFloorLevel(new BlockPos(x, y, z));
        double half = mob.getBbWidth() / 2.0;
        AABB body = new AABB(x + 0.5 - half, floor + CLEARANCE, z + 0.5 - half, x + 0.5 + half, floor + mob.getBbHeight(), z + 0.5 + half);
        return context.level().noBlockCollision(null, body) ? type : PathType.BLOCKED;
    }

    @Override
    protected double getFloorLevel(BlockPos pos) {
        double floor = super.getFloorLevel(pos);
        double height = partialHeight(currentContext.level(), pos, currentContext.getBlockState(pos));
        return height > 0.0 && height <= step() ? Math.max(floor, pos.getY() + height) : floor;
    }

    private double partialHeight(BlockGetter level, BlockPos pos, BlockState state) {
        if (state.isAir() || !state.isPathfindable(PathComputationType.LAND)) {
            return 0.0;
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return 0.0;
        }
        double half = (mob != null ? mob.getBbWidth() : DEFAULT_WIDTH) / 2.0;
        double min = 0.5 - half;
        double max = 0.5 + half;
        double top = 0.0;
        for (AABB box : shape.toAabbs()) {
            if (box.maxX > min && box.minX < max && box.maxZ > min && box.minZ < max) {
                top = Math.max(top, box.maxY);
            }
        }
        return top;
    }

    private float step() {
        return mob != null ? mob.maxUpStep() : DEFAULT_STEP;
    }
}
