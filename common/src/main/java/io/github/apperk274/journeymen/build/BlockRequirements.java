package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.Constants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.DyeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.PotDecorations;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
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
import java.util.List;
import java.util.Map;

public final class BlockRequirements {

    private static final List<IntegerProperty> ITEM_COUNT_PROPERTIES = List.of(
            BlockStateProperties.CANDLES,
            BlockStateProperties.PICKLES,
            BlockStateProperties.EGGS,
            BlockStateProperties.LAYERS,
            BlockStateProperties.FLOWER_AMOUNT);

    private static final Map<Block, Item> ITEM_OVERRIDES = Map.of(
            Blocks.KELP_PLANT, Items.KELP,
            Blocks.TWISTING_VINES_PLANT, Items.TWISTING_VINES,
            Blocks.WEEPING_VINES_PLANT, Items.WEEPING_VINES,
            Blocks.CAVE_VINES_PLANT, Items.GLOW_BERRIES,
            Blocks.ATTACHED_MELON_STEM, Items.MELON_SEEDS,
            Blocks.ATTACHED_PUMPKIN_STEM, Items.PUMPKIN_SEEDS,
            Blocks.BIG_DRIPLEAF_STEM, Items.BIG_DRIPLEAF,
            Blocks.BAMBOO_SAPLING, Items.BAMBOO);

    private BlockRequirements() {
    }

    public enum ToolUse {
        HOE("a hoe"),
        SHOVEL("a shovel");

        private final String description;

        ToolUse(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    public record Requirement(List<ItemStack> items, @Nullable ToolUse toolUse, @Nullable BlockState madeFrom, boolean obtainable) {

        static final Requirement NOTHING = new Requirement(List.of(), null, null, true);
        static final Requirement UNOBTAINABLE = new Requirement(List.of(), null, null, false);
    }

    public static Requirement of(BlockState schematicTarget, @Nullable CompoundTag blockEntityTag, @Nullable HolderLookup.Provider registries) {
        BlockState target = BlockDrops.placedFor(schematicTarget);
        if (target.isAir()
                || target.getOptionalValue(BlockStateProperties.DOUBLE_BLOCK_HALF).orElse(null) == DoubleBlockHalf.UPPER
                || target.getOptionalValue(BlockStateProperties.BED_PART).orElse(null) == BedPart.HEAD) {
            return Requirement.NOTHING;
        }
        Block block = target.getBlock();
        if (block instanceof PistonHeadBlock || block instanceof MovingPistonBlock) {
            return Requirement.NOTHING;
        }
        if (block instanceof LiquidBlock) {
            if (!target.getFluidState().isSource()) {
                return Requirement.NOTHING;
            }
            ItemStack bucket = new ItemStack(target.getFluidState().getType().getBucket());
            return bucket.isEmpty() ? Requirement.UNOBTAINABLE : new Requirement(List.of(bucket), null, null, true);
        }

        List<ItemStack> items = new ArrayList<>();
        BlockDrops.Made made = BlockDrops.made(block);
        if (made != null) {
            items.add(new ItemStack(made.from().getBlock().asItem()));
        } else if (block instanceof FlowerPotBlock pot && pot.getPotted() != Blocks.AIR) {
            items.add(new ItemStack(Items.FLOWER_POT));
            items.add(new ItemStack(pot.getPotted()));
        } else {
            Item item = ITEM_OVERRIDES.getOrDefault(block, block.asItem());
            if (item == Items.AIR) {
                return Requirement.UNOBTAINABLE;
            }
            int count = target.getOptionalValue(BlockStateProperties.SLAB_TYPE).orElse(null) == SlabType.DOUBLE ? 2 : 1;
            for (IntegerProperty property : ITEM_COUNT_PROPERTIES) {
                if (target.hasProperty(property)) {
                    count = target.getValue(property);
                }
            }
            items.add(new ItemStack(item, count));
        }
        if (blockEntityTag != null && registries != null) {
            addBlockEntityItems(target, blockEntityTag, registries, items);
        }
        if (target.getOptionalValue(BlockStateProperties.WATERLOGGED).orElse(false)) {
            items.add(new ItemStack(Items.WATER_BUCKET));
        }
        return new Requirement(List.copyOf(items), made == null ? null : made.toolUse(), made == null ? null : made.from(), true);
    }

    private static void addBlockEntityItems(BlockState target, CompoundTag tag, HolderLookup.Provider registries, List<ItemStack> items) {
        BlockEntity blockEntity;
        try {
            blockEntity = BlockEntity.loadStatic(BlockPos.ZERO, target, tag, registries);
        } catch (RuntimeException e) {
            Constants.LOG.warn("Could not read block entity data for {}", target, e);
            return;
        }
        if (blockEntity instanceof BannerBlockEntity banner && !banner.getPatterns().layers().isEmpty()) {
            items.get(0).set(DataComponents.BANNER_PATTERNS, banner.getPatterns());
        } else if (blockEntity instanceof DecoratedPotBlockEntity pot) {
            if (!pot.getDecorations().equals(PotDecorations.EMPTY)) {
                items.get(0).set(DataComponents.POT_DECORATIONS, pot.getDecorations());
            }
            addIfPresent(items, pot.getTheItem());
        } else if (blockEntity instanceof LecternBlockEntity lectern) {
            addIfPresent(items, lectern.getBook());
        } else if (blockEntity instanceof JukeboxBlockEntity jukebox) {
            addIfPresent(items, jukebox.getTheItem());
        } else if (blockEntity instanceof ChiseledBookShelfBlockEntity shelf) {
            for (int slot = 0; slot < shelf.getContainerSize(); slot++) {
                addIfPresent(items, shelf.getItem(slot));
            }
        } else if (blockEntity instanceof SignBlockEntity sign) {
            addSignItems(sign.getFrontText(), items);
            addSignItems(sign.getBackText(), items);
        }
    }

    private static void addSignItems(SignText text, List<ItemStack> items) {
        boolean hasText = false;
        for (int line = 0; line < 4; line++) {
            if (!text.getMessage(line, false).getString().isEmpty()) {
                hasText = true;
                break;
            }
        }
        if (!hasText) {
            return;
        }
        if (text.getColor() != DyeColor.BLACK) {
            items.add(new ItemStack(DyeItem.byColor(text.getColor())));
        }
        if (text.hasGlowingText()) {
            items.add(new ItemStack(Items.GLOW_INK_SAC));
        }
    }

    private static void addIfPresent(List<ItemStack> items, ItemStack stack) {
        if (!stack.isEmpty()) {
            items.add(stack.copy());
        }
    }
}
