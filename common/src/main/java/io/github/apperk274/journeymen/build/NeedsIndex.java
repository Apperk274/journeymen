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
import java.util.function.Function;
import java.util.function.Predicate;

public final class NeedsIndex {

    private final BoundingBox area;
    private final boolean skipUnplaceable;
    private final Map<Item, List<BuildPlan.Placement>> byItem;
    private final Map<BlockPos, BlockState> unmade;
    private long countedAt = Long.MIN_VALUE;
    private final Map<Item, ItemCounter> counted = new HashMap<>();

    private NeedsIndex(BoundingBox area, boolean skipUnplaceable, Map<Item, List<BuildPlan.Placement>> byItem, Map<BlockPos, BlockState> unmade) {
        this.area = area;
        this.skipUnplaceable = skipUnplaceable;
        this.byItem = byItem;
        this.unmade = unmade;
    }

    public static NeedsIndex create(ServerLevel level, Schematic schematic, PlacementTransform transform, BuildOptions options,
                                    HolderLookup.Provider registries) {
        Map<Item, List<BuildPlan.Placement>> byItem = new LinkedHashMap<>();
        Map<BlockPos, BlockState> unmade = new HashMap<>();
        Function<BlockPos, BlockState> neighbours = BuildPlan.neighbours(level, schematic, transform);
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
                    BlockPos pos = transform.toWorld(x, y, z);
                    BlockState from = BlockDrops.unmadeFrom(target, pos, neighbours);
                    BlockState placed = from != null ? from : target;
                    CompoundTag blockEntity = schematic.getBlockEntity(x, y, z);
                    List<ItemStack> items = blockEntity == null
                            ? plain.computeIfAbsent(placed, state -> BlockRequirements.of(state, null, registries).items())
                            : BlockRequirements.of(placed, blockEntity, registries).items();
                    if (items.isEmpty()) {
                        continue;
                    }
                    if (from != null) {
                        unmade.put(pos, from);
                    }
                    BuildPlan.Placement placement = new BuildPlan.Placement(pos, target, blockEntity);
                    Set<Item> kinds = new LinkedHashSet<>();
                    items.forEach(stack -> kinds.add(stack.getItem()));
                    kinds.forEach(item -> byItem.computeIfAbsent(item, key -> new ArrayList<>()).add(placement));
                }
            }
        }
        return new NeedsIndex(transform.area(schematic.getSize()), options.skipUnplaceable(), byItem, unmade);
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
        for (Map.Entry<ItemStack, Long> entry : BuildPlan.of(level, area, placements, skipUnplaceable, unmade).requirements(registries).items().entries()) {
            if (entry.getKey().is(item)) {
                needs.add(entry.getKey(), entry.getValue());
            }
        }
        return needs;
    }
}
