package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
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
import java.util.function.Predicate;

public final class ClearingTask {

    private static final int BREAK_DELAY = 5;
    private static final int MIN_BATCH = 4;
    private static final int MAX_BATCH = 64;
    private static final int TOOL_ASK_TICKS = 1200;

    public enum Status {
        WORKING,
        NEEDS_DUMP,
        NEEDS_TOOL,
        NEEDS_SPEED_TOOL,
        BLOCKED,
        IDLE,
        DONE
    }

    private final BuilderEntity builder;
    private final BuildJob job;
    private final ServerLevel level;
    private final ClearingWork work;
    private final List<BlockPos> batch = new ArrayList<>();
    private final Map<BlockState, Long> nextToolAsk = new HashMap<>();
    private int cleared;

    @Nullable
    private BlockPos target;
    private float progress;
    private int lastStage = -1;
    private float cooldown;
    private final Reach reach;

    @Nullable
    private String blockedReason;
    @Nullable
    private Predicate<ItemStack> neededTool;
    @Nullable
    private String neededToolDescription;

    public ClearingTask(BuilderEntity builder, BuildJob job, ClearingWork work) {
        this.builder = builder;
        this.job = job;
        this.level = (ServerLevel) builder.level();
        this.work = work;
        this.reach = new Reach(job.getArea());
    }

    public ClearingWork getWork() {
        return work;
    }

    public Status tick() {
        if (cooldown > 0) {
            cooldown -= speed();
            return Status.WORKING;
        }
        if (target == null) {
            neededTool = null;
            target = pickNext();
            if (target == null) {
                builder.showInHand(ItemStack.EMPTY);
                if (work.isEmpty()) {
                    return Status.DONE;
                }
                return neededTool != null ? Status.NEEDS_TOOL : Status.IDLE;
            }
            job.getCrewWork().claim(target, builder.getUUID());
        }
        BlockState state = level.getBlockState(target);
        BlockState wanted = work.targets.get(target);
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
            work.done(state);
            work.skipped.add(target);
            finishTarget();
            return Status.WORKING;
        }
        if (!reach.approach(builder, target)) {
            return Status.WORKING;
        }
        builder.getNavigation().stop();

        Container inventory = builder.getInventory();
        int slot = ToolChoice.choose(inventory, state, level, target, job.getOptions().allowBreakingEnchanted(), work.requiredRemaining);
        if (slot == ToolChoice.NONE) {
            needToolFor(state, target);
            resetProgress();
            if (builder.inCrewMode()) {
                job.getCrewWork().unclaim(target, builder.getUUID());
                batch.remove(target);
                target = null;
                reach.reset();
            }
            return Status.NEEDS_TOOL;
        }
        if (progress == 0.0F && !hasFreeSlot(inventory)) {
            return Status.NEEDS_DUMP;
        }
        if (progress == 0.0F && slot == ToolChoice.HAND && state.getDestroySpeed(level, target) > 0.0F
                && level.getGameTime() >= nextToolAsk.getOrDefault(state, 0L)) {
            nextToolAsk.put(state, level.getGameTime() + TOOL_ASK_TICKS);
            if (builder.wantsSpeedTool(state, target)) {
                return Status.NEEDS_SPEED_TOOL;
            }
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

    private void needToolFor(BlockState state, BlockPos pos) {
        neededTool = stack -> stack.isCorrectToolForDrops(state)
                && ToolCheck.usableDurability(stack, job.getOptions().allowBreakingEnchanted()) >= ToolCheck.damagePerBlock(stack, state, level, pos);
        neededToolDescription = "a tool that can mine " + state.getBlock().getName().getString() + " at " + pos.toShortString();
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
        work.done(state);
        if (broken) {
            cleared++;
            work.cleared++;
            work.requiredRemaining.computeIfPresent(state, (key, count) -> count > 1 ? count - 1 : null);
            builder.showInHand(slot == ToolChoice.HAND ? ItemStack.EMPTY : inventory.getItem(slot));
        } else {
            work.skipped.add(target);
        }
        finishTarget();
        cooldown = BREAK_DELAY;
    }

    @Nullable
    private BlockPos pickNext() {
        if (builder.inCrewMode()) {
            return crewPick();
        }
        job.getCrewWork().keepOnly(builder.activeCrew());
        batch.clear();
        while (!work.remaining.isEmpty()) {
            List<BlockPos> layer = work.remaining.firstEntry().getValue();
            if (layer.isEmpty()) {
                work.remaining.pollFirstEntry();
                continue;
            }
            return reach.choose(builder, layer, pos -> pos);
        }
        return null;
    }

    @Nullable
    private BlockPos crewPick() {
        batch.removeIf(pos -> !work.contains(pos));
        if (!batch.isEmpty()) {
            return reach.choose(builder, batch, pos -> pos);
        }
        CrewWork crew = job.getCrewWork();
        crew.keepOnly(builder.activeCrew());
        crew.release(builder.getUUID());
        Map<Long, Integer> top = new HashMap<>();
        for (List<BlockPos> layer : work.remaining.values()) {
            for (BlockPos pos : layer) {
                top.putIfAbsent(ChunkPos.asLong(pos.getX(), pos.getZ()), pos.getY());
            }
        }
        Map<BlockState, Boolean> breakable = new HashMap<>();
        Set<Long> blocked = new HashSet<>();
        for (List<BlockPos> layer : work.remaining.values()) {
            List<BlockPos> candidates = new ArrayList<>();
            for (BlockPos pos : layer) {
                long column = ChunkPos.asLong(pos.getX(), pos.getZ());
                if (crew.claimedByOther(pos, builder.getUUID()) || top.get(column) != pos.getY() || blocked.contains(column)) {
                    continue;
                }
                if (canBreak(pos, breakable)) {
                    candidates.add(pos);
                } else if (neededTool == null) {
                    needToolFor(level.getBlockState(pos), pos);
                }
            }
            if (!candidates.isEmpty()) {
                neededTool = null;
                BlockPos first = reach.choose(builder, candidates, pos -> pos);
                if (first == null) {
                    return null;
                }
                candidates.remove(first);
                candidates.sort(Comparator.comparingDouble(pos -> pos.distSqr(first)));
                batch.add(first);
                int members = builder.activeCrew().size();
                int cap = Math.min(MAX_BATCH, Math.max(MIN_BATCH, (layer.size() + members - 1) / members));
                for (BlockPos pos : candidates) {
                    if (batch.size() >= cap) {
                        break;
                    }
                    batch.add(pos);
                }
                batch.forEach(pos -> crew.claim(pos, builder.getUUID()));
                if (Services.PLATFORM.isDevelopmentEnvironment()) {
                    Constants.LOG.info("[crew] {}.{} claims {} blocks to clear", builder.getOwnerName(), builder.getJourneymanName(), batch.size());
                }
                return first;
            }
        }
        return null;
    }

    private boolean canBreak(BlockPos pos, Map<BlockState, Boolean> cache) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.getBlock() instanceof LiquidBlock || state.getDestroySpeed(level, pos) < 0.0F) {
            return true;
        }
        return cache.computeIfAbsent(state, key -> ToolChoice.choose(builder.getInventory(), key, level, pos,
                job.getOptions().allowBreakingEnchanted(), work.requiredRemaining) != ToolChoice.NONE);
    }

    private void finishTarget() {
        if (target != null) {
            work.remove(target);
            batch.remove(target);
            job.getCrewWork().unclaim(target, builder.getUUID());
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

    public boolean isBreaking() {
        return target != null && progress > 0.0F;
    }

    public void stop() {
        resetProgress();
        builder.showInHand(ItemStack.EMPTY);
    }

    public void releaseClaims() {
        resetProgress();
        target = null;
        batch.clear();
        reach.reset();
        job.getCrewWork().release(builder.getUUID());
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

    public Reach getReach() {
        return reach;
    }

    public int getTotal() {
        return work.getTotal();
    }

    public int getCleared() {
        return work.getCleared();
    }

    public int getOwnCleared() {
        return cleared;
    }

    public int getRemaining() {
        return work.getRemaining();
    }

    public List<BlockPos> getSkipped() {
        return work.getSkipped();
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

    public static boolean isUsefulTool(ItemStack stack, ClearingWork work) {
        return isUsefulTool(stack, work.remainingByState().keySet());
    }

    private static boolean isUsefulTool(ItemStack stack, Set<BlockState> states) {
        if (stack.get(DataComponents.TOOL) == null) {
            return false;
        }
        for (BlockState state : states) {
            if (stack.isCorrectToolForDrops(state) || stack.getDestroySpeed(state) > 1.0F) {
                return true;
            }
        }
        return false;
    }
}
