package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

public final class ClearingTask {

    private static final int BREAK_DELAY = 5;

    public enum Status {
        WORKING,
        NEEDS_DUMP,
        NEEDS_TOOL,
        BLOCKED,
        DONE
    }

    private final BuilderEntity builder;
    private final BuildJob job;
    private final ServerLevel level;
    private final TreeMap<Integer, List<BlockPos>> remaining = new TreeMap<>(Comparator.reverseOrder());
    private final Map<BlockPos, BlockState> targets;
    private final Map<BlockState, Integer> requiredRemaining = new HashMap<>();
    private final Set<BlockState> statesToClear = new HashSet<>();
    private final List<BlockPos> skipped = new ArrayList<>();
    private final int total;
    private int cleared;

    @Nullable
    private BlockPos target;
    private float progress;
    private int lastStage = -1;
    private float cooldown;
    private final Reach reach = new Reach();

    @Nullable
    private String blockedReason;
    @Nullable
    private Predicate<ItemStack> neededTool;
    @Nullable
    private String neededToolDescription;

    public ClearingTask(BuilderEntity builder, BuildJob job, List<BlockPos> toClear, List<BlockPos> fluids, Map<BlockPos, BlockState> targets) {
        this.builder = builder;
        this.job = job;
        this.level = (ServerLevel) builder.level();
        this.targets = targets;
        for (BlockPos pos : fluids) {
            remaining.computeIfAbsent(pos.getY(), y -> new ArrayList<>()).add(pos);
        }
        for (BlockPos pos : toClear) {
            remaining.computeIfAbsent(pos.getY(), y -> new ArrayList<>()).add(pos);
            BlockState state = level.getBlockState(pos);
            statesToClear.add(state);
            if (ToolChoice.needsTool(state, level, pos)) {
                requiredRemaining.merge(state, 1, Integer::sum);
            }
        }
        this.total = toClear.size();
    }

    public Status tick() {
        if (cooldown > 0) {
            cooldown -= speed();
            return Status.WORKING;
        }
        if (target == null) {
            target = pickNext();
            if (target == null) {
                builder.showInHand(ItemStack.EMPTY);
                return Status.DONE;
            }
        }
        BlockState state = level.getBlockState(target);
        BlockState wanted = targets.get(target);
        if (state.isAir() || wanted != null && StateRules.matches(wanted, state) && !(state.getBlock() instanceof LiquidBlock)) {
            finishTarget();
            return Status.WORKING;
        }
        if (state.getBlock() instanceof LiquidBlock) {
            blockedReason = "Water or lava is in the way at " + target.toShortString() + ". Please remove it.";
            resetProgress();
            return Status.BLOCKED;
        }
        if (state.getDestroySpeed(level, target) < 0.0F) {
            skipped.add(target);
            finishTarget();
            return Status.WORKING;
        }
        if (!reach.approach(builder, target)) {
            return Status.WORKING;
        }
        builder.getNavigation().stop();

        Container inventory = builder.getInventory();
        int slot = ToolChoice.choose(inventory, state, level, target, job.getOptions().allowBreakingEnchanted(), requiredRemaining);
        if (slot == ToolChoice.NONE) {
            BlockState needing = state;
            BlockPos pos = target;
            neededTool = stack -> stack.isCorrectToolForDrops(needing)
                    && ToolCheck.usableDurability(stack, job.getOptions().allowBreakingEnchanted()) >= ToolCheck.damagePerBlock(stack, needing, level, pos);
            neededToolDescription = "a tool that can mine " + state.getBlock().getName().getString() + " at " + target.toShortString();
            resetProgress();
            return Status.NEEDS_TOOL;
        }
        if (progress == 0.0F && !hasFreeSlot(inventory)) {
            return Status.NEEDS_DUMP;
        }
        ItemStack tool = slot == ToolChoice.HAND ? ItemStack.EMPTY : inventory.getItem(slot);
        builder.showInHand(tool);
        builder.getLookControl().setLookAt(Vec3.atCenterOf(target));

        if (builder.tickCount % 5 == 0) {
            builder.swing(InteractionHand.MAIN_HAND);
        }
        if (builder.tickCount % 4 == 0) {
            SoundType sound = state.getSoundType();
            level.playSound(null, target, sound.getHitSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 8.0F, sound.getPitch() * 0.5F);
        }
        progress += BreakSpeed.progressPerTick(builder, tool, state, level, target) * speed();
        int stage = Math.min(9, (int) (progress * 10.0F));
        if (stage != lastStage) {
            level.destroyBlockProgress(builder.getId(), target, stage);
            lastStage = stage;
        }
        if (progress >= 1.0F) {
            breakTarget(state, slot);
        }
        return Status.WORKING;
    }

    private void breakTarget(BlockState state, int slot) {
        Container inventory = builder.getInventory();
        ItemStack tool = slot == ToolChoice.HAND ? ItemStack.EMPTY : inventory.getItem(slot);
        ServerPlayer actor = builder.actor();
        actor.setItemInHand(InteractionHand.MAIN_HAND, tool);
        boolean broken;
        try {
            broken = actor.gameMode.destroyBlock(target);
        } finally {
            actor.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        }
        inventory.setChanged();
        level.destroyBlockProgress(builder.getId(), target, -1);
        builder.captureDrops(target);
        if (broken) {
            cleared++;
            requiredRemaining.computeIfPresent(state, (key, count) -> count > 1 ? count - 1 : null);
            builder.showInHand(slot == ToolChoice.HAND ? ItemStack.EMPTY : inventory.getItem(slot));
        } else {
            skipped.add(target);
        }
        finishTarget();
        cooldown = BREAK_DELAY;
    }

    @Nullable
    private BlockPos pickNext() {
        while (!remaining.isEmpty()) {
            List<BlockPos> layer = remaining.firstEntry().getValue();
            if (layer.isEmpty()) {
                remaining.pollFirstEntry();
                continue;
            }
            BlockPos nearest = null;
            double nearestDistance = Double.MAX_VALUE;
            for (BlockPos pos : layer) {
                double distance = Reach.horizontalDistanceSqr(builder, pos);
                if (distance < nearestDistance) {
                    nearest = pos;
                    nearestDistance = distance;
                }
            }
            return nearest;
        }
        return null;
    }

    private void finishTarget() {
        if (target != null) {
            List<BlockPos> layer = remaining.get(target.getY());
            if (layer != null) {
                layer.remove(target);
            }
        }
        target = null;
        resetProgress();
        reach.reset();
    }

    private void resetProgress() {
        if (target != null && lastStage >= 0) {
            level.destroyBlockProgress(builder.getId(), target, -1);
        }
        progress = 0.0F;
        lastStage = -1;
    }

    public void stop() {
        resetProgress();
        builder.showInHand(ItemStack.EMPTY);
    }

    private static boolean hasFreeSlot(Container inventory) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static float speed() {
        return JourneymenConfig.SPEED_MULTIPLIER.get().floatValue();
    }

    public int getTotal() {
        return total;
    }

    public int getCleared() {
        return cleared;
    }

    public int getRemaining() {
        int count = 0;
        for (List<BlockPos> layer : remaining.values()) {
            count += layer.size();
        }
        return count;
    }

    public List<BlockPos> getSkipped() {
        return skipped;
    }

    @Nullable
    public String getBlockedReason() {
        return blockedReason;
    }

    @Nullable
    public Predicate<ItemStack> getNeededTool() {
        return neededTool;
    }

    @Nullable
    public String getNeededToolDescription() {
        return neededToolDescription;
    }

    public boolean isUsefulTool(ItemStack stack) {
        if (stack.get(DataComponents.TOOL) == null) {
            return false;
        }
        for (BlockState state : statesToClear) {
            if (stack.isCorrectToolForDrops(state) || stack.getDestroySpeed(state) > 1.0F) {
                return true;
            }
        }
        return false;
    }
}
