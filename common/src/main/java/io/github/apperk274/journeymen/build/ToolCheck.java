package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

public final class ToolCheck {

    private static final List<Item> EXAMPLE_TOOLS = List.of(
            Items.WOODEN_PICKAXE, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE,
            Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE,
            Items.WOODEN_SHOVEL, Items.STONE_SHOVEL, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL, Items.NETHERITE_SHOVEL,
            Items.WOODEN_HOE, Items.STONE_HOE, Items.IRON_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE,
            Items.SHEARS, Items.WOODEN_SWORD, Items.STONE_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD);

    private ToolCheck() {
    }

    public static int usableDurability(ItemStack stack, boolean allowBreakingEnchanted) {
        if (!stack.isDamageableItem()) {
            return Integer.MAX_VALUE;
        }
        int remaining = stack.getMaxDamage() - stack.getDamageValue();
        if (stack.isEnchanted() && !allowBreakingEnchanted) {
            remaining--;
        }
        return Math.max(0, remaining);
    }

    public static int damagePerBlock(ItemStack stack, BlockState state, ServerLevel level, BlockPos pos) {
        Tool tool = stack.get(DataComponents.TOOL);
        if (tool == null || state.getDestroySpeed(level, pos) == 0.0F) {
            return 0;
        }
        return tool.damagePerBlock();
    }

    public record BlockShortfall(BlockState state, int missing, int total, BlockPos example, @Nullable Item exampleTool, boolean noTool) {
    }

    public record UseShortfall(BlockRequirements.ToolUse use, int missing) {
    }

    public record Shortfalls(List<BlockShortfall> blocks, List<UseShortfall> uses) {

        public List<Component> problems() {
            List<Component> problems = new ArrayList<>();
            for (BlockShortfall shortfall : blocks) {
                problems.add(toolProblem(shortfall.missing(), shortfall.total(), shortfall.state().getBlock().getName(), shortfall.exampleTool(),
                        shortfall.noTool()));
            }
            for (UseShortfall shortfall : uses) {
                problems.add(Component.literal("Needs " + shortfall.use().description() + " with " + shortfall.missing()
                        + " more use(s) left, for " + shortfall.use().purpose() + "."));
            }
            return problems;
        }

        public long missing() {
            return blocks.stream().mapToLong(BlockShortfall::missing).sum() + uses.stream().mapToLong(UseShortfall::missing).sum();
        }

        public boolean suppliable() {
            return !uses.isEmpty() || blocks.stream().anyMatch(shortfall -> shortfall.exampleTool() != null);
        }
    }

    public static List<Component> check(ServerLevel level, List<BlockPos> toClear, Map<BlockRequirements.ToolUse, Integer> toolUses,
                                        List<ItemStack> tools, boolean allowBreakingEnchanted) {
        return shortfalls(level, toClear, toolUses, tools, allowBreakingEnchanted).problems();
    }

    public static Shortfalls shortfalls(ServerLevel level, List<BlockPos> toClear, Map<BlockRequirements.ToolUse, Integer> toolUses,
                                        List<ItemStack> tools, boolean allowBreakingEnchanted) {
        List<Slot> slots = new ArrayList<>();
        for (ItemStack stack : tools) {
            if (stack.get(DataComponents.TOOL) != null || Arrays.stream(BlockRequirements.ToolUse.values()).anyMatch(use -> Services.COMPAT.canDo(stack, use))) {
                slots.add(new Slot(stack, usableDurability(stack, allowBreakingEnchanted)));
            }
        }

        Map<BlockState, Integer> needingTool = new LinkedHashMap<>();
        Map<BlockState, BlockPos> examplePos = new LinkedHashMap<>();
        for (BlockPos pos : toClear) {
            BlockState state = level.getBlockState(pos);
            if (state.requiresCorrectToolForDrops() && state.getDestroySpeed(level, pos) > 0.0F) {
                needingTool.merge(state, 1, Integer::sum);
                examplePos.putIfAbsent(state, pos);
            }
        }

        List<Map.Entry<BlockState, Integer>> ordered = new ArrayList<>(needingTool.entrySet());
        ordered.sort(Comparator.comparingInt(entry -> candidates(slots, stack -> stack.isCorrectToolForDrops(entry.getKey())).size()));

        List<BlockShortfall> blocks = new ArrayList<>();
        for (Map.Entry<BlockState, Integer> entry : ordered) {
            BlockState state = entry.getKey();
            BlockPos pos = examplePos.get(state);
            List<Slot> candidates = candidates(slots, stack -> stack.isCorrectToolForDrops(state));
            candidates.sort(Comparator.comparingDouble((Slot slot) -> slot.stack.getDestroySpeed(state)).reversed());
            int missing = allocate(candidates, entry.getValue(), slot -> damagePerBlock(slot.stack, state, level, pos));
            if (missing > 0) {
                blocks.add(new BlockShortfall(state, missing, entry.getValue(), pos, exampleTool(state), candidates.isEmpty()));
            }
        }

        List<UseShortfall> uses = new ArrayList<>();
        for (Map.Entry<BlockRequirements.ToolUse, Integer> entry : toolUses.entrySet()) {
            BlockRequirements.ToolUse use = entry.getKey();
            List<Slot> candidates = candidates(slots, stack -> Services.COMPAT.canDo(stack, use));
            int missing = allocate(candidates, entry.getValue(), slot -> slot.stack.isDamageableItem() ? 1 : 0);
            if (missing > 0) {
                uses.add(new UseShortfall(entry.getKey(), missing));
            }
        }
        return new Shortfalls(blocks, uses);
    }

    private static Component toolProblem(int missing, int total, Component blockName, @Nullable Item example, boolean noTool) {
        String needs = example == null ? "a suitable tool" : example.getDescription().getString() + " or better";
        if (noTool) {
            return Component.literal("No tool that can mine " + total + " ").append(blockName)
                    .append(Component.literal(" (needs " + needs + ")."));
        }
        return Component.literal("Not enough tool durability for " + missing + " of " + total + " ").append(blockName)
                .append(Component.literal(" (needs " + needs + ")."));
    }

    private static int allocate(List<Slot> candidates, int blocks, ToIntFunction<Slot> cost) {
        int remaining = blocks;
        for (Slot slot : candidates) {
            if (remaining == 0) {
                break;
            }
            int perBlock = cost.applyAsInt(slot);
            if (perBlock == 0 || slot.budget == Integer.MAX_VALUE) {
                return 0;
            }
            int possible = Math.min(remaining, slot.budget / perBlock);
            slot.budget -= possible * perBlock;
            remaining -= possible;
        }
        return remaining;
    }

    private static List<Slot> candidates(List<Slot> slots, Predicate<ItemStack> predicate) {
        List<Slot> result = new ArrayList<>();
        for (Slot slot : slots) {
            if (predicate.test(slot.stack)) {
                result.add(slot);
            }
        }
        return result;
    }

    @Nullable
    public static Item exampleTool(BlockState state) {
        for (Item item : EXAMPLE_TOOLS) {
            if (new ItemStack(item).isCorrectToolForDrops(state)) {
                return item;
            }
        }
        return null;
    }

    private static final class Slot {

        private final ItemStack stack;
        private int budget;

        private Slot(ItemStack stack, int budget) {
            this.stack = stack;
            this.budget = budget;
        }
    }
}
