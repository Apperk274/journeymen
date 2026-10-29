package io.github.apperk274.journeymen.entity.ai;

import io.github.apperk274.journeymen.entity.JourneymanEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class OpenPassageGoal extends Goal {

    private static final double REACH_SQR = 2.25;
    private static final int GIVE_UP_TICKS = 100;

    private final JourneymanEntity builder;
    @Nullable
    private BlockPos passage;
    private boolean opened;
    private double startX;
    private double startZ;
    private boolean passed;
    private int ticks;

    public OpenPassageGoal(JourneymanEntity builder) {
        this.builder = builder;
    }

    @Override
    public boolean canUse() {
        Path path = builder.getNavigation().getPath();
        if (path == null || path.isDone()) {
            return false;
        }
        for (int i = path.getNextNodeIndex(); i < Math.min(path.getNextNodeIndex() + 2, path.getNodeCount()); i++) {
            Node node = path.getNode(i);
            for (BlockPos pos : new BlockPos[]{new BlockPos(node.x, node.y, node.z), new BlockPos(node.x, node.y + 1, node.z)}) {
                BlockPos closed = closedPassage(pos);
                if (closed != null && builder.distanceToSqr(closed.getX() + 0.5, builder.getY(), closed.getZ() + 0.5) <= REACH_SQR) {
                    passage = closed;
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        return passage != null && ticks < GIVE_UP_TICKS && !(passed && farEnough());
    }

    @Override
    public void start() {
        passed = false;
        opened = false;
        ticks = 0;
        startX = passage.getX() + 0.5 - builder.getX();
        startZ = passage.getZ() + 0.5 - builder.getZ();
        if (!builder.mayUsePassage(passage)) {
            passage = null;
            builder.getNavigation().stop();
            return;
        }
        opened = setOpen(builder, passage, true);
    }

    @Override
    public void tick() {
        if (passage == null) {
            return;
        }
        ticks++;
        double dx = passage.getX() + 0.5 - builder.getX();
        double dz = passage.getZ() + 0.5 - builder.getZ();
        if (startX * dx + startZ * dz < 0.0) {
            passed = true;
        }
    }

    @Override
    public void stop() {
        abandon();
    }

    @Nullable
    public BlockPos openedPassage() {
        return opened ? passage : null;
    }

    public void abandon() {
        if (passage != null && opened) {
            builder.closeLater(passage);
        }
        passage = null;
        opened = false;
    }

    private boolean farEnough() {
        return builder.distanceToSqr(passage.getX() + 0.5, builder.getY(), passage.getZ() + 0.5) > REACH_SQR;
    }

    @Nullable
    private BlockPos closedPassage(BlockPos pos) {
        BlockState state = builder.level().getBlockState(pos);
        BlockPos found = null;
        if (state.getBlock() instanceof DoorBlock door && door.type().canOpenByHand() && !state.getValue(DoorBlock.OPEN)) {
            found = state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
        } else if (state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)) {
            found = pos;
        }
        return found == null || builder.isPassageRefused(found) ? null : found;
    }

    public static List<BlockPos> parts(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock) {
            return List.of(pos, state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos.above());
        }
        return List.of(pos);
    }

    public static boolean isOpenPassage(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.getBlock() instanceof DoorBlock && state.getValue(DoorBlock.OPEN)
                || state.getBlock() instanceof FenceGateBlock && state.getValue(FenceGateBlock.OPEN);
    }

    public static boolean doorwayFree(JourneymanEntity builder, BlockPos pos, boolean leaving) {
        AABB doorway = new AABB(pos).expandTowards(0.0, 1.0, 0.0);
        return builder.level().getEntities((Entity) null, doorway,
                entity -> entity.isAlive() && !entity.isSpectator() && entity.blocksBuilding && !(leaving && entity == builder)).isEmpty();
    }

    public static boolean setOpen(JourneymanEntity builder, BlockPos pos, boolean open) {
        Level level = builder.level();
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock door && state.getValue(DoorBlock.OPEN) != open) {
            door.setOpen(builder, level, state, pos, open);
            return true;
        }
        if (state.getBlock() instanceof FenceGateBlock && state.getValue(FenceGateBlock.OPEN) != open) {
            level.setBlock(pos, state.setValue(FenceGateBlock.OPEN, open), 10);
            level.playSound(null, pos, open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 1.0F,
                    level.getRandom().nextFloat() * 0.1F + 0.9F);
            level.gameEvent(builder, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
            return true;
        }
        return false;
    }
}
