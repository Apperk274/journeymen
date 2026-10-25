package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.schematic.Schematic;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class MaterialSupply {

    public static final String MISSING_ONLY = "missing-only";
    private static final int TOOL_ROUNDS = 32;

    private MaterialSupply() {
    }

    public record Needs(List<ItemStack> stacks, Map<Block, Integer> unobtainable, Map<Block, Integer> noKnownTool) {
    }

    public record Target(List<ItemStack> slots, List<ItemStack> original, ItemStore store) {
    }

    public record Outcome(boolean success, long given, Component summary, List<Component> notes) {

        static Outcome failure(String message) {
            return new Outcome(false, 0, Component.literal(message).withStyle(ChatFormatting.RED), List.of());
        }
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
        toolUses.forEach((tool, uses) -> items.add(new ItemStack(tool), toolsFor(new ItemStack(tool), uses)));
        return new Needs(stacks(items), requirements.unobtainable(), noKnownTool);
    }

    public static Needs missing(ServerLevel level, BuildPlan plan, BuilderEntity builder, BuildOptions options) {
        BuildPlan.Requirements requirements = plan.requirements(level.registryAccess());
        List<ItemStack> stock = builder.collectStock(new ArrayList<>());
        ItemCounter available = new ItemCounter();
        stock.forEach(stack -> available.add(stack, stack.getCount()));
        ItemCounter items = new ItemCounter();
        for (Map.Entry<ItemStack, Long> entry : requirements.items().sorted()) {
            items.add(entry.getKey(), entry.getValue() - available.count(entry.getKey()));
        }

        List<ItemStack> tools = new ArrayList<>(stock);
        ToolCheck.Shortfalls shortfalls = ToolCheck.shortfalls(level, plan.getToClear(), requirements.toolUses(), tools, options.allowBreakingEnchanted());
        for (int round = 0; round < TOOL_ROUNDS && shortfalls.suppliable(); round++) {
            ToolCheck.BlockShortfall widest = widest(shortfalls.blocks());
            if (widest != null) {
                ItemStack tool = new ItemStack(widest.exampleTool());
                int perBlock = Math.max(1, ToolCheck.damagePerBlock(tool, widest.state(), level, widest.example()));
                addTools(items, tools, tool, (long) widest.missing() * perBlock);
            } else {
                for (ToolCheck.UseShortfall shortfall : shortfalls.uses()) {
                    addTools(items, tools, new ItemStack(shortfall.use() == BlockRequirements.ToolUse.HOE ? Items.WOODEN_HOE : Items.WOODEN_SHOVEL),
                            shortfall.missing());
                }
            }
            long before = shortfalls.missing();
            shortfalls = ToolCheck.shortfalls(level, plan.getToClear(), requirements.toolUses(), tools, options.allowBreakingEnchanted());
            if (shortfalls.missing() >= before) {
                break;
            }
        }
        Map<Block, Integer> noKnownTool = new LinkedHashMap<>();
        for (ToolCheck.BlockShortfall shortfall : shortfalls.blocks()) {
            if (shortfall.exampleTool() == null) {
                noKnownTool.merge(shortfall.state().getBlock(), shortfall.missing(), Integer::sum);
            }
        }
        return new Needs(stacks(items), requirements.unobtainable(), noKnownTool);
    }

    @Nullable
    private static ToolCheck.BlockShortfall widest(List<ToolCheck.BlockShortfall> shortfalls) {
        ToolCheck.BlockShortfall widest = null;
        long widestReach = -1;
        for (ToolCheck.BlockShortfall shortfall : shortfalls) {
            if (shortfall.exampleTool() == null) {
                continue;
            }
            ItemStack tool = new ItemStack(shortfall.exampleTool());
            long reach = shortfalls.stream().filter(other -> tool.isCorrectToolForDrops(other.state())).count();
            if (reach > widestReach) {
                widest = shortfall;
                widestReach = reach;
            }
        }
        return widest;
    }

    private static void addTools(ItemCounter items, List<ItemStack> tools, ItemStack tool, long uses) {
        long count = toolsFor(tool, uses);
        items.add(tool, count);
        for (long i = 0; i < count; i++) {
            tools.add(tool.copy());
        }
    }

    private static long toolsFor(ItemStack tool, long uses) {
        int durability = Math.max(1, tool.getMaxDamage());
        return (uses + durability - 1) / durability;
    }

    private static List<ItemStack> stacks(ItemCounter items) {
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
        return stacks;
    }

    public static Outcome supply(BuilderEntity builder, Schematic schematic, String file, BlockPos origin, Rotation rotation, BuildOptions options,
                                 boolean missingOnly, @Nullable ServerPlayer player) {
        ServerLevel level = (ServerLevel) builder.level();
        String name = builder.getBuilderName();
        PlacementTransform transform = PlacementTransform.of(schematic, origin, rotation, options.corner());
        if (!BuildPlan.isLoaded(level, transform.area(schematic.getSize()))) {
            return Outcome.failure("Part of the site isn't loaded. Go closer to it and try again. Nothing was given.");
        }
        BuildPlan plan = BuildPlan.create(level, schematic, transform, options);
        Needs needs = missingOnly ? missing(level, plan, builder, options) : needs(level, plan);
        List<Component> notes = new ArrayList<>();
        needs.unobtainable().forEach((block, count) -> notes.add(Component.literal("Can't be supplied (no survival item): " + count + " ")
                .append(block.getName()).withStyle(ChatFormatting.GOLD)));
        needs.noKnownTool().forEach((block, count) -> notes.add(Component.literal("No known tool for " + count + " ")
                .append(block.getName()).withStyle(ChatFormatting.GOLD)));
        if (missingOnly && needs.stacks().isEmpty()) {
            return new Outcome(true, 0, Component.literal("Nothing is missing for " + file + ": " + name + " already has everything it can be given.")
                    .withStyle(ChatFormatting.GREEN), notes);
        }

        List<Target> chests = new ArrayList<>();
        for (AssignedChest chest : builder.getChests()) {
            if (chest.role() == AssignedChest.Role.SUPPLY && level.isLoaded(chest.pos())) {
                ItemStore store = ItemStores.at(level, chest.pos());
                if (store != null) {
                    chests.add(snapshot(store));
                }
            }
        }
        Target playerTarget = player == null ? null : snapshot(player.getInventory());
        long toChests = 0;
        long toPlayer = 0;
        long notFitting = 0;
        for (ItemStack stack : needs.stacks()) {
            ItemStack remaining = stack;
            for (Target chest : chests) {
                remaining = insert(chest, remaining);
                if (remaining.isEmpty()) {
                    break;
                }
            }
            toChests += stack.getCount() - remaining.getCount();
            if (!remaining.isEmpty() && playerTarget != null) {
                int before = remaining.getCount();
                remaining = insert(playerTarget, remaining);
                toPlayer += before - remaining.getCount();
            }
            notFitting += remaining.getCount();
        }
        if (notFitting > 0) {
            String where = chests.isEmpty() ? name + " has no loaded supply chests" : "they don't fit in " + name + "'s supply chests";
            return Outcome.failure("Not enough room: " + notFitting + " of " + (toChests + toPlayer + notFitting) + " items don't fit ("
                    + where + (playerTarget == null ? ", and there is no player inventory to fall back on" : " or your inventory") + "). Nothing was given.");
        }
        List<ItemStack> leftover = new ArrayList<>();
        chests.forEach(chest -> leftover.addAll(apply(chest)));
        if (playerTarget != null) {
            leftover.addAll(apply(playerTarget));
        }
        if (player != null) {
            leftover.forEach(stack -> player.getInventory().placeItemBackInInventory(stack));
        }
        long total = toChests + toPlayer;
        return new Outcome(true, total, Component.literal("Supplied " + total + (missingOnly ? " missing" : "") + (total == 1 ? " item" : " items")
                + " for " + file + ": " + toChests + " into " + name + "'s supply chests" + (toPlayer > 0 ? ", " + toPlayer + " into your inventory" : "")
                + ".").withStyle(ChatFormatting.GREEN), notes);
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
