package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

public final class ToolPlan {

    public static final class Kind {

        private final BitSet signature;
        private final ItemStack example;
        private final float[] speeds;
        private final List<BlockState> assigned = new ArrayList<>();
        private long blocks;
        private long uses;
        private long durability;
        private int damageable;
        private int pool;
        private int carried;
        private int target;

        private Kind(BitSet signature, ItemStack example, int states) {
            this.signature = signature;
            this.example = example;
            this.speeds = new float[states];
        }

        public ItemStack example() {
            return example;
        }

        public long blocks() {
            return blocks;
        }

        public int carried() {
            return carried;
        }

        public int target() {
            return target;
        }

        private double averageDurability() {
            return damageable == 0 ? Double.POSITIVE_INFINITY : (double) durability / damageable;
        }
    }

    private final ServerLevel level;
    private final ClearingWork work;
    private final boolean allowBreakingEnchanted;
    private final List<BlockState> states = new ArrayList<>();
    private final BitSet required = new BitSet();
    private final Map<BitSet, Kind> kinds = new LinkedHashMap<>();
    private final Map<Item, BitSet> signatures = new HashMap<>();
    private final int members;
    private long totalBlocks;
    private int capacity;

    private ToolPlan(ServerLevel level, ClearingWork work, boolean allowBreakingEnchanted, int members) {
        this.level = level;
        this.work = work;
        this.allowBreakingEnchanted = allowBreakingEnchanted;
        this.members = Math.max(1, members);
    }

    public static ToolPlan compute(ServerLevel level, ClearingWork work, Container inventory, Predicate<ItemStack> stays, List<ItemStack> supply,
                                   List<ItemStack> crewTools, int members, boolean allowBreakingEnchanted) {
        ToolPlan plan = new ToolPlan(level, work, allowBreakingEnchanted, members);
        plan.build(inventory, stays, supply, crewTools);
        return plan;
    }

    private void build(Container inventory, Predicate<ItemStack> stays, List<ItemStack> supply, List<ItemStack> crewTools) {
        Map<BlockState, Integer> counts = new LinkedHashMap<>(work.remainingByState());
        for (Map.Entry<BlockState, Integer> entry : counts.entrySet()) {
            totalBlocks += entry.getValue();
            BlockPos pos = work.example(entry.getKey());
            if (pos != null && entry.getKey().getDestroySpeed(level, pos) > 0.0F) {
                if (ToolChoice.needsTool(entry.getKey(), level, pos)) {
                    required.set(states.size());
                }
                states.add(entry.getKey());
            }
        }
        int otherSlots = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            Kind kind = addToPool(stack);
            if (kind != null) {
                kind.carried++;
            } else if (stays.test(stack)) {
                otherSlots++;
            }
        }
        supply.forEach(this::addToPool);
        crewTools.forEach(this::addToPool);
        for (Map.Entry<BlockRequirements.ToolUse, Integer> entry : work.toolUses().entrySet()) {
            long left = entry.getValue();
            for (Kind kind : kinds.values()) {
                if (left <= 0) {
                    break;
                }
                if (kind.damageable > 0 && Services.COMPAT.canDo(kind.example, entry.getKey())) {
                    long reserved = Math.min(left, kind.durability);
                    kind.durability -= reserved;
                    left -= reserved;
                }
            }
        }

        for (int index = 0; index < states.size(); index++) {
            Kind best = null;
            for (Kind kind : kinds.values()) {
                if (kind.signature.get(index) && (best == null || better(kind, best, index))) {
                    best = kind;
                }
            }
            if (best != null) {
                BlockState state = states.get(index);
                int count = counts.get(state);
                best.assigned.add(state);
                best.blocks += count;
                best.uses += (long) count * ToolCheck.damagePerBlock(best.example, state, level, work.example(state));
            }
        }
        for (Kind kind : kinds.values()) {
            if (kind.blocks == 0) {
                otherSlots += kind.carried;
            }
        }
        capacity = Math.max(0, inventory.getContainerSize() - otherSlots);
        chooseTargets(counts);
    }

    @Nullable
    private Kind addToPool(ItemStack stack) {
        BitSet signature = signature(stack);
        if (signature == null) {
            return null;
        }
        Kind kind = kinds.computeIfAbsent(signature, key -> new Kind(key, stack.copyWithCount(1), states.size()));
        kind.pool++;
        if (stack.isDamageableItem()) {
            kind.durability += ToolCheck.usableDurability(stack, allowBreakingEnchanted);
            kind.damageable++;
        }
        for (int index = kind.signature.nextSetBit(0); index >= 0; index = kind.signature.nextSetBit(index + 1)) {
            kind.speeds[index] = Math.max(kind.speeds[index], stack.getDestroySpeed(states.get(index)));
        }
        return kind;
    }

    @Nullable
    private BitSet signature(ItemStack stack) {
        if (stack.isEmpty() || stack.get(DataComponents.TOOL) == null || ToolCheck.usableDurability(stack, allowBreakingEnchanted) < 1) {
            return null;
        }
        BitSet signature = signatures.computeIfAbsent(stack.getItem(), item -> {
            BitSet bits = new BitSet();
            for (int index = 0; index < states.size(); index++) {
                BlockState state = states.get(index);
                if (required.get(index) ? stack.isCorrectToolForDrops(state) : stack.getDestroySpeed(state) > 1.0F) {
                    bits.set(index);
                }
            }
            return bits;
        });
        return signature.isEmpty() ? null : signature;
    }

    private boolean better(Kind candidate, Kind best, int index) {
        if (required.get(index)) {
            int candidateVersatility = versatility(candidate);
            int bestVersatility = versatility(best);
            if (candidateVersatility != bestVersatility) {
                return candidateVersatility < bestVersatility;
            }
        }
        return candidate.speeds[index] > best.speeds[index];
    }

    private int versatility(Kind kind) {
        BitSet both = (BitSet) kind.signature.clone();
        both.and(required);
        return both.cardinality();
    }

    private void chooseTargets(Map<BlockState, Integer> counts) {
        if (totalBlocks == 0) {
            return;
        }
        double dropSlots = 0.0;
        for (Map.Entry<BlockState, Integer> entry : counts.entrySet()) {
            dropSlots += entry.getValue() * slotsPerBlock(entry.getKey(), toolFor(entry.getKey()));
        }
        double perBlock = dropSlots / totalBlocks;
        double cycle = perBlock <= 0.0 ? Double.POSITIVE_INFINITY : capacity / perBlock;
        int sum = 0;
        for (Kind kind : kinds.values()) {
            if (kind.blocks == 0) {
                continue;
            }
            double durability = kind.averageDurability();
            double share = (double) kind.uses / members;
            int needed = Double.isInfinite(durability) || kind.uses == 0 ? 1 : (int) Math.max(1, Math.ceil(share / durability));
            int balanced = Double.isInfinite(durability) ? 1 : Double.isInfinite(cycle) ? needed
                    : (int) Math.min(Integer.MAX_VALUE, Math.ceil(cycle * kind.uses / totalBlocks / durability));
            int fairShare = (kind.pool + members - 1) / members;
            kind.target = Math.max(0, Math.min(Math.min(Math.max(1, balanced), needed), fairShare));
            sum += kind.target;
        }
        int limit = Math.max(1, capacity / 2);
        while (sum > limit) {
            Kind largest = kinds.values().stream().max(Comparator.comparingInt(kind -> kind.target)).orElse(null);
            if (largest == null || largest.target <= 1) {
                break;
            }
            largest.target--;
            sum--;
        }
    }

    private ItemStack toolFor(BlockState state) {
        for (Kind kind : kinds.values()) {
            if (kind.assigned.contains(state)) {
                return kind.example;
            }
        }
        return ItemStack.EMPTY;
    }

    private double slotsPerBlock(BlockState state, ItemStack tool) {
        BlockPos pos = work.example(state);
        if (pos == null) {
            return 0.0;
        }
        List<ItemStack> drops;
        try {
            drops = Block.getDrops(state, level, pos, null, null, tool);
        } catch (RuntimeException e) {
            return 1.0 / 64.0;
        }
        double slots = 0.0;
        for (ItemStack drop : drops) {
            if (!drop.isEmpty()) {
                slots += (double) drop.getCount() / Math.max(1, drop.getMaxStackSize());
            }
        }
        return slots;
    }

    @Nullable
    public Kind kindOf(ItemStack stack) {
        BitSet signature = signature(stack);
        return signature == null ? null : kinds.get(signature);
    }

    public boolean wants(ItemStack stack) {
        Kind kind = kindOf(stack);
        return kind != null && kind.carried < kind.target;
    }

    public boolean hasDeficit() {
        return kinds.values().stream().anyMatch(kind -> kind.carried < kind.target);
    }

    public boolean isExcess(ItemStack stack) {
        if (stack.isEmpty() || stack.get(DataComponents.TOOL) == null) {
            return false;
        }
        Kind kind = kindOf(stack);
        return kind == null || kind.blocks == 0 || kind.carried > kind.target;
    }

    public List<Integer> excessSlots(Container inventory) {
        List<Integer> result = new ArrayList<>();
        Map<Kind, List<Integer>> byKind = new HashMap<>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.get(DataComponents.TOOL) == null) {
                continue;
            }
            Kind kind = kindOf(stack);
            if (kind == null || kind.blocks == 0) {
                result.add(slot);
            } else {
                byKind.computeIfAbsent(kind, key -> new ArrayList<>()).add(slot);
            }
        }
        byKind.forEach((kind, slots) -> {
            int extra = kind.carried - kind.target;
            if (extra <= 0) {
                return;
            }
            slots.sort(Comparator.comparingInt(slot -> ToolCheck.usableDurability(inventory.getItem(slot), allowBreakingEnchanted)));
            result.addAll(slots.subList(0, Math.min(extra, slots.size())));
        });
        return result;
    }

    public List<Integer> slotsToTake(ItemStore store) {
        Map<Kind, List<Integer>> byKind = new HashMap<>();
        for (int slot = 0; slot < store.size(); slot++) {
            ItemStack stack = store.get(slot);
            Kind kind = kindOf(stack);
            if (kind != null && kind.carried < kind.target) {
                byKind.computeIfAbsent(kind, key -> new ArrayList<>()).add(slot);
            }
        }
        List<Integer> result = new ArrayList<>();
        byKind.forEach((kind, slots) -> {
            slots.sort(Comparator.comparingDouble((Integer slot) -> speedOn(store.get(slot), kind)).reversed()
                    .thenComparing(Comparator.comparingInt((Integer slot) -> ToolCheck.usableDurability(store.get(slot), allowBreakingEnchanted)).reversed()));
            result.addAll(slots.subList(0, Math.min(kind.target - kind.carried, slots.size())));
        });
        return result;
    }

    private static double speedOn(ItemStack stack, Kind kind) {
        double speed = 0.0;
        for (BlockState state : kind.assigned) {
            speed = Math.max(speed, stack.getDestroySpeed(state));
        }
        return speed;
    }

    public void took(ItemStack stack) {
        Kind kind = kindOf(stack);
        if (kind != null) {
            kind.carried++;
        }
    }

    public void returned(ItemStack stack) {
        Kind kind = kindOf(stack);
        if (kind != null) {
            kind.carried--;
        }
    }

    public List<Kind> kinds() {
        return kinds.values().stream().filter(kind -> kind.blocks > 0).toList();
    }

    public int members() {
        return members;
    }

    public int capacity() {
        return capacity;
    }

    public static double savedTicks(LivingEntity miner, ItemStack tool, ClearingWork work, ServerLevel level, int members) {
        double saved = 0.0;
        for (Map.Entry<BlockState, Integer> entry : work.remainingByState().entrySet()) {
            BlockState state = entry.getKey();
            BlockPos pos = work.example(state);
            if (pos == null || tool.getDestroySpeed(state) <= 1.0F || ToolChoice.needsTool(state, level, pos)) {
                continue;
            }
            float byHand = BreakSpeed.progressPerTick(miner, ItemStack.EMPTY, state, level, pos);
            float withTool = BreakSpeed.progressPerTick(miner, tool, state, level, pos);
            if (byHand <= 0.0F || withTool <= 0.0F) {
                continue;
            }
            saved += (double) entry.getValue() / Math.max(1, members) * (Math.ceil(1.0 / byHand) - Math.ceil(1.0 / withTool));
        }
        return saved / JourneymenConfig.SPEED_MULTIPLIER.get();
    }
}
