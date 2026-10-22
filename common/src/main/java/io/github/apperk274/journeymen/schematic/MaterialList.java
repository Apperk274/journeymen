package io.github.apperk274.journeymen.schematic;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class MaterialList {

    private static final List<IntegerProperty> ITEM_COUNT_PROPERTIES = List.of(
            BlockStateProperties.CANDLES,
            BlockStateProperties.PICKLES,
            BlockStateProperties.EGGS,
            BlockStateProperties.LAYERS,
            BlockStateProperties.FLOWER_AMOUNT);

    private final Map<Item, Integer> items;
    private final Map<Block, Integer> unobtainable;
    private final int blockCount;

    private MaterialList(Map<Item, Integer> items, Map<Block, Integer> unobtainable, int blockCount) {
        this.items = items;
        this.unobtainable = unobtainable;
        this.blockCount = blockCount;
    }

    public static MaterialList of(Schematic schematic) {
        Map<Item, Integer> items = new HashMap<>();
        Map<Block, Integer> unobtainable = new HashMap<>();
        int blockCount = 0;
        int[] counts = schematic.countByPaletteIndex();
        List<BlockState> palette = schematic.getPalette();
        for (int i = 0; i < counts.length; i++) {
            int count = counts[i];
            BlockState state = palette.get(i);
            if (count == 0 || state.isAir()) {
                continue;
            }
            blockCount += count;
            List<ItemStack> required = requiredItems(state);
            if (required == null) {
                unobtainable.merge(state.getBlock(), count, Integer::sum);
                continue;
            }
            for (ItemStack stack : required) {
                items.merge(stack.getItem(), stack.getCount() * count, Integer::sum);
            }
        }
        return new MaterialList(sorted(items, BuiltInRegistries.ITEM::getKey), sorted(unobtainable, BuiltInRegistries.BLOCK::getKey), blockCount);
    }

    @Nullable
    public static List<ItemStack> requiredItems(BlockState state) {
        if (state.isAir()
                || state.getOptionalValue(BlockStateProperties.DOUBLE_BLOCK_HALF).orElse(null) == DoubleBlockHalf.UPPER
                || state.getOptionalValue(BlockStateProperties.BED_PART).orElse(null) == BedPart.HEAD) {
            return List.of();
        }
        Block block = state.getBlock();
        if (block instanceof PistonHeadBlock || block instanceof MovingPistonBlock) {
            return List.of();
        }
        if (block instanceof LiquidBlock) {
            return state.getFluidState().isSource()
                    ? List.of(new ItemStack(state.getFluidState().getType().getBucket()))
                    : List.of();
        }

        List<ItemStack> items = new ArrayList<>();
        if (block instanceof FlowerPotBlock pot && pot.getPotted() != Blocks.AIR) {
            items.add(new ItemStack(Items.FLOWER_POT));
            items.add(new ItemStack(pot.getPotted()));
        } else {
            Item item = block.asItem();
            if (item == Items.AIR) {
                return null;
            }
            int count = state.getOptionalValue(BlockStateProperties.SLAB_TYPE).orElse(null) == SlabType.DOUBLE ? 2 : 1;
            for (IntegerProperty property : ITEM_COUNT_PROPERTIES) {
                if (state.hasProperty(property)) {
                    count = state.getValue(property);
                }
            }
            items.add(new ItemStack(item, count));
        }
        if (state.getOptionalValue(BlockStateProperties.WATERLOGGED).orElse(false)) {
            items.add(new ItemStack(Items.WATER_BUCKET));
        }
        return items;
    }

    public Map<Item, Integer> getItems() {
        return items;
    }

    public Map<Block, Integer> getUnobtainable() {
        return unobtainable;
    }

    public int getBlockCount() {
        return blockCount;
    }

    private static <T> Map<T, Integer> sorted(Map<T, Integer> counts, Function<T, Object> key) {
        Map<T, Integer> result = new LinkedHashMap<>();
        counts.entrySet().stream()
                .sorted(Map.Entry.<T, Integer>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(entry -> key.apply(entry.getKey()).toString()))
                .forEach(entry -> result.put(entry.getKey(), entry.getValue()));
        return result;
    }
}
