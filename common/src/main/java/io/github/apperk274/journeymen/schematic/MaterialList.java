package io.github.apperk274.journeymen.schematic;

import io.github.apperk274.journeymen.build.BlockDrops;
import io.github.apperk274.journeymen.build.BlockRequirements;
import io.github.apperk274.journeymen.build.BuildPlan;
import io.github.apperk274.journeymen.build.ItemCounter;
import io.github.apperk274.journeymen.build.PlacementTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class MaterialList {

    private final ItemCounter items;
    private final Map<BlockRequirements.ToolUse, Integer> toolUses;
    private final Map<Block, Integer> unobtainable;
    private final int blockCount;

    private MaterialList(ItemCounter items, Map<BlockRequirements.ToolUse, Integer> toolUses, Map<Block, Integer> unobtainable, int blockCount) {
        this.items = items;
        this.toolUses = toolUses;
        this.unobtainable = unobtainable;
        this.blockCount = blockCount;
    }

    public static MaterialList of(Schematic schematic, HolderLookup.Provider registries) {
        ItemCounter items = new ItemCounter();
        Map<BlockRequirements.ToolUse, Integer> toolUses = new EnumMap<>(BlockRequirements.ToolUse.class);
        Map<Block, Integer> unobtainable = new HashMap<>();
        int blockCount = 0;
        int[] counts = schematic.countByPaletteIndex(true);
        List<BlockState> palette = schematic.getPalette();
        Function<BlockPos, BlockState> neighbours = BuildPlan.neighbours(null, schematic, new PlacementTransform(BlockPos.ZERO, Vec3i.ZERO, Rotation.NONE));
        Map<BlockState, Integer> unmade = new HashMap<>();
        for (int i = 0; i < counts.length; i++) {
            BlockState state = palette.get(i);
            if (counts[i] <= 0 || state.isAir()) {
                continue;
            }
            blockCount += counts[i];
            int made = counts[i];
            if (BlockDrops.made(state.getBlock()) != null) {
                Vec3i size = schematic.getSize();
                for (int y = 0; y < size.getY(); y++) {
                    for (int z = 0; z < size.getZ(); z++) {
                        for (int x = 0; x < size.getX(); x++) {
                            BlockPos pos = new BlockPos(x, y, z);
                            if (schematic.paletteIndex(x, y, z) != i || schematic.getBlockEntityPositions().contains(pos)) {
                                continue;
                            }
                            BlockState from = BlockDrops.unmadeFrom(state, pos, neighbours);
                            if (from != null) {
                                unmade.merge(from, 1, Integer::sum);
                                made--;
                            }
                        }
                    }
                }
            }
            if (made > 0) {
                add(BlockRequirements.of(state, null, registries), state, made, items, toolUses, unobtainable);
            }
        }
        unmade.forEach((state, count) -> add(BlockRequirements.of(state, null, registries), state, count, items, toolUses, unobtainable));
        for (BlockPos pos : schematic.getBlockEntityPositions()) {
            BlockState state = schematic.getBlock(pos);
            if (state != null && !state.isAir()) {
                blockCount++;
                BlockState from = BlockDrops.unmadeFrom(state, pos, neighbours);
                BlockState placed = from != null ? from : state;
                add(BlockRequirements.of(placed, schematic.getBlockEntity(pos), registries), placed, 1, items, toolUses, unobtainable);
            }
        }
        Map<Block, Integer> sortedUnobtainable = new LinkedHashMap<>();
        unobtainable.entrySet().stream()
                .sorted(Map.Entry.<Block, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(entry -> BuiltInRegistries.BLOCK.getKey(entry.getKey()).toString()))
                .forEach(entry -> sortedUnobtainable.put(entry.getKey(), entry.getValue()));
        return new MaterialList(items, toolUses, sortedUnobtainable, blockCount);
    }

    private static void add(BlockRequirements.Requirement requirement, BlockState state, int count, ItemCounter items,
                            Map<BlockRequirements.ToolUse, Integer> toolUses, Map<Block, Integer> unobtainable) {
        if (!requirement.obtainable()) {
            unobtainable.merge(state.getBlock(), count, Integer::sum);
            return;
        }
        for (ItemStack stack : requirement.items()) {
            items.add(stack, (long) stack.getCount() * count);
        }
        if (requirement.toolUse() != null) {
            toolUses.merge(requirement.toolUse(), count, Integer::sum);
        }
    }

    public List<Map.Entry<ItemStack, Long>> getItems() {
        return items.sorted();
    }

    public ItemCounter getItemCounter() {
        return items;
    }

    public Map<BlockRequirements.ToolUse, Integer> getToolUses() {
        return toolUses;
    }

    public Map<Block, Integer> getUnobtainable() {
        return unobtainable;
    }

    public int getBlockCount() {
        return blockCount;
    }
}
