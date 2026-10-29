package io.github.apperk274.journeymen.build;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractCandleBlock;
import net.minecraft.world.level.block.AbstractCauldronBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class BlockUses {

    public record Finish(@Nullable BlockState base, List<ItemStack> uses, @Nullable BlockRequirements.ToolUse toolUse) {

        public boolean obtainable() {
            return base != null;
        }
    }

    private static final Finish UNOBTAINABLE = new Finish(null, List.of(), null);

    private static final Map<Block, Item> CAULDRON_FILLS = Map.of(
            Blocks.WATER_CAULDRON, Items.WATER_BUCKET,
            Blocks.LAVA_CAULDRON, Items.LAVA_BUCKET,
            Blocks.POWDER_SNOW_CAULDRON, Items.POWDER_SNOW_BUCKET);

    private BlockUses() {
    }

    @Nullable
    public static Finish of(BlockState target) {
        Block block = target.getBlock();
        if (block instanceof AbstractCauldronBlock && block.asItem() instanceof BlockItem item && item.getBlock() != block) {
            return cauldron(target, item.getBlock());
        }
        if (block instanceof RespawnAnchorBlock && target.getValue(RespawnAnchorBlock.CHARGE) > 0) {
            return new Finish(target.setValue(RespawnAnchorBlock.CHARGE, 0),
                    List.of(new ItemStack(Items.GLOWSTONE, target.getValue(RespawnAnchorBlock.CHARGE))), null);
        }
        if (block instanceof EndPortalFrameBlock && target.getValue(EndPortalFrameBlock.HAS_EYE)) {
            return new Finish(target.setValue(EndPortalFrameBlock.HAS_EYE, false), List.of(new ItemStack(Items.ENDER_EYE)), null);
        }
        if (block instanceof AbstractCandleBlock && target.getValue(AbstractCandleBlock.LIT) && !WaterRules.isWaterlogged(target)) {
            return new Finish(target.setValue(AbstractCandleBlock.LIT, false), List.of(), BlockRequirements.ToolUse.IGNITE);
        }
        return null;
    }

    private static Finish cauldron(BlockState target, Block empty) {
        Item fill = CAULDRON_FILLS.get(target.getBlock());
        if (fill == null) {
            return UNOBTAINABLE;
        }
        List<ItemStack> uses = new ArrayList<>();
        uses.add(new ItemStack(fill));
        if (target.hasProperty(LayeredCauldronBlock.LEVEL)) {
            int taken = LayeredCauldronBlock.MAX_FILL_LEVEL - target.getValue(LayeredCauldronBlock.LEVEL);
            if (taken > 0 && !target.is(Blocks.WATER_CAULDRON)) {
                return UNOBTAINABLE;
            }
            if (taken > 0) {
                uses.add(new ItemStack(Items.GLASS_BOTTLE, taken));
            }
        }
        return new Finish(empty.defaultBlockState(), uses, null);
    }

    public static ItemStack fillFor(BlockState cauldron) {
        Item fill = CAULDRON_FILLS.get(cauldron.getBlock());
        return fill == null ? ItemStack.EMPTY : new ItemStack(fill);
    }

    public static boolean hasFaces(Block block) {
        return block instanceof MultifaceBlock || block instanceof VineBlock;
    }

    public static int faces(BlockState state) {
        int count = 0;
        for (BooleanProperty property : PipeBlock.PROPERTY_BY_DIRECTION.values()) {
            if (state.hasProperty(property) && state.getValue(property)) {
                count++;
            }
        }
        return count;
    }

    public static boolean hasFacesOf(BlockState actual, BlockState target) {
        for (BooleanProperty property : PipeBlock.PROPERTY_BY_DIRECTION.values()) {
            if (target.hasProperty(property) && target.getValue(property) && !actual.getValue(property)) {
                return false;
            }
        }
        return true;
    }
}
