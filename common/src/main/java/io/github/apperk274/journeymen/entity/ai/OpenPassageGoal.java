package io.github.apperk274.journeymen.entity.ai;

import io.github.apperk274.journeymen.entity.JourneymanEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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

public class OpenPassageGoal extends Goal {

    private static final double REACH_SQR = 2.25;
    private static final int GIVE_UP_TICKS = 100;

    private final JourneymanEntity builder;
    @Nullable
    private BlockPos passage;
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
        ticks = 0;
        startX = passage.getX() + 0.5 - builder.getX();
        startZ = passage.getZ() + 0.5 - builder.getZ();
        setOpen(true);
    }

    @Override
    public void tick() {
        ticks++;
        double dx = passage.getX() + 0.5 - builder.getX();
        double dz = passage.getZ() + 0.5 - builder.getZ();
        if (startX * dx + startZ * dz < 0.0) {
            passed = true;
        }
    }

    @Override
    public void stop() {
        if (passage != null && !builder.getBoundingBox().intersects(new AABB(passage).expandTowards(0.0, 1.0, 0.0))) {
            setOpen(false);
        }
        passage = null;
    }

    private boolean farEnough() {
        return builder.distanceToSqr(passage.getX() + 0.5, builder.getY(), passage.getZ() + 0.5) > REACH_SQR;
    }

    @Nullable
    private BlockPos closedPassage(BlockPos pos) {
        BlockState state = builder.level().getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock door && door.type().canOpenByHand() && !state.getValue(DoorBlock.OPEN)) {
            return state.getValue(DoorBlock.HALF) == DoubleBlockHalf.UPPER ? pos.below() : pos;
        }
        if (state.getBlock() instanceof FenceGateBlock && !state.getValue(FenceGateBlock.OPEN)) {
            return pos;
        }
        return null;
    }

    private void setOpen(boolean open) {
        Level level = builder.level();
        BlockState state = level.getBlockState(passage);
        if (state.getBlock() instanceof DoorBlock door) {
            door.setOpen(builder, level, state, passage, open);
        } else if (state.getBlock() instanceof FenceGateBlock && state.getValue(FenceGateBlock.OPEN) != open) {
            level.setBlock(passage, state.setValue(FenceGateBlock.OPEN, open), 10);
            level.playSound(null, passage, open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 1.0F,
                    level.getRandom().nextFloat() * 0.1F + 0.9F);
            level.gameEvent(builder, open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, passage);
        }
    }
}
