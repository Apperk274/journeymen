package io.github.apperk274.journeymen.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
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

    public record Target(List<ItemStack> slots, List<ItemStack> original, ItemStore store) {
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

    public static Target snapshot(ItemStore store) {
        List<ItemStack> slots = new ArrayList<>();
        for (int slot = 0; slot < store.size(); slot++) {
            slots.add(store.get(slot).copy());
        }
        return new Target(slots, slots.stream().map(ItemStack::copy).toList(), store);
    }

    public static Target snapshot(Inventory inventory) {
        return snapshot(new ContainerStore(inventory, inventory.items.size()));
    }

    public static ItemStack insert(Target target, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < target.slots().size() && !remaining.isEmpty(); slot++) {
            ItemStack existing = target.slots().get(slot);
            if (!existing.isEmpty() && ItemStack.isSameItemSameComponents(existing, remaining) && target.store().accepts(slot, remaining)) {
                int room = target.store().maxStack(slot, remaining) - existing.getCount();
                int moved = Math.min(room, remaining.getCount());
                if (moved > 0) {
                    existing.grow(moved);
                    remaining.shrink(moved);
                }
            }
        }
        for (int slot = 0; slot < target.slots().size() && !remaining.isEmpty(); slot++) {
            if (target.slots().get(slot).isEmpty() && target.store().accepts(slot, remaining)) {
                int moved = Math.min(target.store().maxStack(slot, remaining), remaining.getCount());
                target.slots().set(slot, remaining.split(moved));
            }
        }
        return remaining;
    }

    public static List<ItemStack> apply(Target target) {
        List<ItemStack> leftover = new ArrayList<>();
        for (int slot = 0; slot < target.slots().size(); slot++) {
            ItemStack wanted = target.slots().get(slot);
            int added = wanted.getCount() - target.original().get(slot).getCount();
            if (added > 0) {
                ItemStack remainder = target.store().insert(slot, wanted.copyWithCount(added), false);
                if (!remainder.isEmpty()) {
                    leftover.add(target.store().insert(remainder));
                }
            }
        }
        target.store().setChanged();
        leftover.removeIf(ItemStack::isEmpty);
        return leftover;
    }
}
