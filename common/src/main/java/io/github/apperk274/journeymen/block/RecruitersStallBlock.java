package io.github.apperk274.journeymen.block;

import com.mojang.serialization.MapCodec;
import io.github.apperk274.journeymen.entity.ModEntities;
import io.github.apperk274.journeymen.entity.RecruiterEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public class RecruitersStallBlock extends HorizontalDirectionalBlock {

    public static final MapCodec<RecruitersStallBlock> CODEC = simpleCodec(RecruitersStallBlock::new);
    public static final EnumProperty<StallPart> PART = EnumProperty.create("part", StallPart.class);

    private static final VoxelShape POST = Block.box(6.0, 0.0, 6.0, 10.0, 16.0, 10.0);
    private static final VoxelShape AWNING = Block.box(0.0, 12.0, 0.0, 16.0, 16.0, 16.0);

    public RecruitersStallBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, StallPart.COUNTER_MIDDLE));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, PART);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        StallPart part = state.getValue(PART);
        return part.isCounter() ? Shapes.block() : part.isAwning() ? AWNING : POST;
    }

    @Override
    protected boolean isPathfindable(BlockState state, PathComputationType type) {
        return false;
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getHorizontalDirection().getOpposite();
        if (!hasRoom(context.getLevel(), context.getClickedPos(), facing)) {
            return null;
        }
        return defaultBlockState().setValue(FACING, facing).setValue(PART, StallPart.COUNTER_MIDDLE);
    }

    public static boolean hasRoom(Level level, BlockPos origin, Direction facing) {
        for (StallPart part : StallPart.values()) {
            if (part != StallPart.COUNTER_MIDDLE && !isFree(level, part.positionFrom(origin, facing))) {
                return false;
            }
        }
        for (int[] free : StallPart.FREE_SPACE) {
            if (!isFree(level, StallPart.offset(origin, facing, free[0], free[1], free[2]))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isFree(Level level, BlockPos pos) {
        return level.getWorldBorder().isWithinBounds(pos) && !level.isOutsideBuildHeight(pos) && level.getBlockState(pos).canBeReplaced();
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel serverLevel) {
            buildRest(serverLevel, pos, state.getValue(FACING));
        }
    }

    public boolean place(ServerLevel level, BlockPos origin, Direction facing) {
        if (!isFree(level, origin) || !hasRoom(level, origin, facing)) {
            return false;
        }
        level.setBlock(origin, defaultBlockState().setValue(FACING, facing).setValue(PART, StallPart.COUNTER_MIDDLE), Block.UPDATE_ALL);
        buildRest(level, origin, facing);
        return true;
    }

    private void buildRest(ServerLevel level, BlockPos origin, Direction facing) {
        for (StallPart part : StallPart.values()) {
            if (part != StallPart.COUNTER_MIDDLE) {
                level.setBlock(part.positionFrom(origin, facing), defaultBlockState().setValue(FACING, facing).setValue(PART, part), Block.UPDATE_ALL);
            }
        }
        RecruiterEntity recruiter = ModEntities.RECRUITER.create(level);
        if (recruiter != null) {
            BlockPos spot = StallPart.recruiterSpot(origin, facing);
            recruiter.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, facing.toYRot(), 0.0F);
            recruiter.setYHeadRot(facing.toYRot());
            recruiter.setStall(origin);
            level.addFreshEntity(recruiter);
        }
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative() && state.getValue(PART) != StallPart.COUNTER_MIDDLE) {
            BlockPos origin = state.getValue(PART).originFrom(pos, state.getValue(FACING));
            if (isIntactCore(level, origin, this)) {
                level.setBlock(origin, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState, LevelAccessor level, BlockPos pos,
                                     BlockPos neighborPos) {
        Direction facing = state.getValue(FACING);
        BlockPos origin = state.getValue(PART).originFrom(pos, facing);
        for (StallPart part : StallPart.values()) {
            if (part.positionFrom(origin, facing).equals(neighborPos)) {
                boolean intact = neighborState.is(this) && neighborState.getValue(PART) == part && neighborState.getValue(FACING) == facing;
                if (intact) {
                    return state;
                }
                level.levelEvent(2001, pos, Block.getId(state));
                return Blocks.AIR.defaultBlockState();
            }
        }
        return state;
    }

    public static boolean isIntactCore(BlockGetter level, BlockPos pos, Block block) {
        BlockState state = level.getBlockState(pos);
        return state.is(block) && state.getValue(PART) == StallPart.COUNTER_MIDDLE;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }
}
