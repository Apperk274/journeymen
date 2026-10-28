package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public final class NeedsIndex {

    private final BoundingBox area;
    private final boolean skipUnplaceable;
    private final Map<Item, List<BuildPlan.Placement>> byItem;
    private long countedAt = Long.MIN_VALUE;
    private final Map<Item, ItemCounter> counted = new HashMap<>();

    private NeedsIndex(BoundingBox area, boolean skipUnplaceable, Map<Item, List<BuildPlan.Placement>> byItem) {
        this.area = area;
        this.skipUnplaceable = skipUnplaceable;
        this.byItem = byItem;
    }

    public static NeedsIndex create(Schematic schematic, PlacementTransform transform, BuildOptions options, HolderLookup.Provider registries) {
        Map<Item, List<BuildPlan.Placement>> byItem = new LinkedHashMap<>();
        Map<BlockState, List<ItemStack>> plain = new HashMap<>();
        int width = schematic.getSize().getX();
        int height = schematic.getSize().getY();
        int length = schematic.getSize().getZ();
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    BlockState schematicState = schematic.getBlock(x, y, z);
                    if (schematicState == null || schematicState.isAir()) {
                        continue;
                    }
                    BlockState target = transform.transform(schematicState);
                    CompoundTag blockEntity = schematic.getBlockEntity(new BlockPos(x, y, z));
                    List<ItemStack> items = blockEntity == null
                            ? plain.computeIfAbsent(target, state -> BlockRequirements.of(state, null, registries).items())
                            : BlockRequirements.of(target, blockEntity, registries).items();
                    if (items.isEmpty()) {
                        continue;
                    }
                    BuildPlan.Placement placement = new BuildPlan.Placement(transform.toWorld(x, y, z), target, blockEntity);
                    Set<Item> kinds = new LinkedHashSet<>();
                    items.forEach(stack -> kinds.add(stack.getItem()));
                    kinds.forEach(item -> byItem.computeIfAbsent(item, key -> new ArrayList<>()).add(placement));
                }
            }
        }
        return new NeedsIndex(transform.area(schematic.getSize()), options.skipUnplaceable(), byItem);
    }

    public ItemCounter remaining(ServerLevel level, Collection<Item> items, Predicate<BlockPos> include, HolderLookup.Provider registries) {
        long now = level.getGameTime();
        if (now != countedAt) {
            countedAt = now;
            counted.clear();
        }
        ItemCounter result = new ItemCounter();
        for (Item item : items) {
            ItemCounter needs = counted.computeIfAbsent(item, key -> count(level, key, include, registries));
            needs.entries().forEach(entry -> result.add(entry.getKey(), entry.getValue()));
        }
        return result;
    }

    private ItemCounter count(ServerLevel level, Item item, Predicate<BlockPos> include, HolderLookup.Provider registries) {
        List<BuildPlan.Placement> placements = byItem.getOrDefault(item, List.of()).stream()
                .filter(placement -> include.test(placement.pos()) && level.isLoaded(placement.pos()))
                .toList();
        ItemCounter needs = new ItemCounter();
        if (placements.isEmpty()) {
            return needs;
        }
        for (Map.Entry<ItemStack, Long> entry : BuildPlan.of(level, area, placements, skipUnplaceable).requirements(registries).items().entries()) {
            if (entry.getKey().is(item)) {
                needs.add(entry.getKey(), entry.getValue());
            }
        }
        return needs;
    }
}
