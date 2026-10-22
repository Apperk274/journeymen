package io.github.apperk274.journeymen.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MaterialSupply {

    private MaterialSupply() {
    }

    public record Needs(List<ItemStack> stacks, Map<Block, Integer> unobtainable, Map<Block, Integer> noKnownTool) {
    }

    public record Target(List<ItemStack> slots, Container container) {
    }

    public static Needs needs(ServerLevel level, BuildPlan plan) {
        BuildPlan.Requirements requirements = plan.requirements(level.registryAccess());
        ItemCounter items = new ItemCounter();
        requirements.items().sorted().forEach(entry -> items.add(entry.getKey(), entry.getValue()));

        Map<Item, Long> toolUses = new LinkedHashMap<>();
        Map<Block, Integer> noKnownTool = new LinkedHashMap<>();
        for (BlockPos pos : plan.getToClear()) {
            BlockState state = level.getBlockState(pos);
            if (!ToolChoice.needsTool(state, level, pos)) {
                continue;
            }
            Item tool = ToolCheck.exampleTool(state);
            if (tool == null) {
                noKnownTool.merge(state.getBlock(), 1, Integer::sum);
                continue;
            }
            Tool component = new ItemStack(tool).get(DataComponents.TOOL);
            toolUses.merge(tool, (long) (component == null ? 1 : Math.max(1, component.damagePerBlock())), Long::sum);
        }
        requirements.toolUses().forEach((use, count) ->
                toolUses.merge(use == BlockRequirements.ToolUse.HOE ? Items.WOODEN_HOE : Items.WOODEN_SHOVEL, (long) count, Long::sum));
        toolUses.forEach((tool, uses) -> {
            int durability = Math.max(1, new ItemStack(tool).getMaxDamage());
            items.add(new ItemStack(tool), (uses + durability - 1) / durability);
        });

        List<ItemStack> stacks = new ArrayList<>();
        for (Map.Entry<ItemStack, Long> entry : items.sorted()) {
            long remaining = entry.getValue();
            int max = entry.getKey().getMaxStackSize();
            while (remaining > 0) {
                int count = (int) Math.min(max, remaining);
                stacks.add(entry.getKey().copyWithCount(count));
                remaining -= count;
            }
        }
        return new Needs(stacks, requirements.unobtainable(), noKnownTool);
    }

    public static Target snapshot(Container container) {
        List<ItemStack> slots = new ArrayList<>();
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            slots.add(container.getItem(slot).copy());
        }
        return new Target(slots, container);
    }

    public static Target snapshot(Inventory inventory) {
        List<ItemStack> slots = new ArrayList<>();
        for (ItemStack stack : inventory.items) {
            slots.add(stack.copy());
        }
        return new Target(slots, inventory);
    }

    public static ItemStack insert(Target target, ItemStack stack, boolean playerInventory) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < target.slots().size() && !remaining.isEmpty(); slot++) {
            ItemStack existing = target.slots().get(slot);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, remaining) && canPlace(target, slot, remaining, playerInventory)) {
                int room = maxStack(target, remaining, playerInventory) - existing.getCount();
                int moved = Math.min(room, remaining.getCount());
                if (moved > 0) {
                    existing.grow(moved);
                    remaining.shrink(moved);
                }
            }
        }
        for (int slot = 0; slot < target.slots().size() && !remaining.isEmpty(); slot++) {
            if (target.slots().get(slot).isEmpty() && canPlace(target, slot, remaining, playerInventory)) {
                int moved = Math.min(maxStack(target, remaining, playerInventory), remaining.getCount());
                target.slots().set(slot, remaining.split(moved));
            }
        }
        return remaining;
    }

    public static void apply(Target target) {
        for (int slot = 0; slot < target.slots().size(); slot++) {
            ItemStack wanted = target.slots().get(slot);
            if (!ItemStack.matches(target.container().getItem(slot), wanted)) {
                target.container().setItem(slot, wanted);
            }
        }
        target.container().setChanged();
    }

    private static boolean canPlace(Target target, int slot, ItemStack stack, boolean playerInventory) {
        return playerInventory || target.container().canPlaceItem(slot, stack);
    }

    private static int maxStack(Target target, ItemStack stack, boolean playerInventory) {
        return playerInventory ? stack.getMaxStackSize() : target.container().getMaxStackSize(stack);
    }
}
