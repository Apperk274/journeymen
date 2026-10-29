package io.github.apperk274.journeymen.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CoralBlock;
import net.minecraft.world.level.block.CoralFanBlock;
import net.minecraft.world.level.block.CoralPlantBlock;
import net.minecraft.world.level.block.CoralWallFanBlock;
import net.minecraft.world.level.block.IceBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;

import java.util.function.Function;
import java.util.function.Predicate;

public final class WaterRules {

    private WaterRules() {
    }

    public static boolean holdsFluid(BlockState state) {
        if (state.getBlock() instanceof LiquidBlock || !state.getFluidState().isSource()) {
            return false;
        }
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            return state.getValue(BlockStateProperties.WATERLOGGED);
        }
        return state.getBlock() instanceof LiquidBlockContainer && !(state.getBlock() instanceof SimpleWaterloggedBlock);
    }

    public static boolean isWaterlogged(BlockState state) {
        return state.getOptionalValue(BlockStateProperties.WATERLOGGED).orElse(false);
    }

    public static ItemStack bucketFor(BlockState state) {
        return new ItemStack(state.getFluidState().getType().getBucket());
    }

    public static ItemStack emptyBucket() {
        return new ItemStack(Items.BUCKET);
    }

    public static boolean isWaterSource(BlockState state) {
        return state.getBlock() instanceof LiquidBlock && state.getFluidState().getType() == Fluids.WATER;
    }

    public static boolean diesWithoutWater(Block block) {
        return block instanceof CoralBlock || block instanceof CoralPlantBlock || block instanceof CoralFanBlock || block instanceof CoralWallFanBlock;
    }

    public static boolean touchesWater(BlockGetter level, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (level.getFluidState(pos.relative(direction)).is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    public static boolean touchesWater(Function<BlockPos, BlockState> states, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (states.apply(pos.relative(direction)).getFluidState().is(FluidTags.WATER)) {
                return true;
            }
        }
        return false;
    }

    public static BlockState drained(BlockState state) {
        if (state.getBlock() instanceof LiquidBlock) {
            return Blocks.AIR.defaultBlockState();
        }
        if (isWaterlogged(state)) {
            return state.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        return holdsFluid(state) ? Blocks.AIR.defaultBlockState() : state;
    }

    public static BlockState withFluidOf(BlockState placeState, BlockState target) {
        if (placeState.hasProperty(BlockStateProperties.WATERLOGGED) && isWaterlogged(target)) {
            return placeState.setValue(BlockStateProperties.WATERLOGGED, true);
        }
        return placeState;
    }

    public static boolean meltsWhenBroken(BlockState state) {
        return state.getBlock() instanceof IceBlock;
    }

    public static boolean keepsIceFromMelting(ItemStack stack) {
        return !stack.isEmpty() && EnchantmentHelper.hasTag(stack, EnchantmentTags.PREVENTS_ICE_MELTING);
    }

    public static Predicate<ItemStack> iceTool(boolean allowBreakingEnchanted) {
        return stack -> keepsIceFromMelting(stack) && ToolCheck.usableDurability(stack, allowBreakingEnchanted) >= 1;
    }
}
