package io.github.apperk274.journeymen.build;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChorusPlantBlock;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class StateRules {

    private static final Set<Property<?>> ENVIRONMENT = Set.of(
            BlockStateProperties.POWERED,
            BlockStateProperties.LIT,
            BlockStateProperties.TRIGGERED,
            BlockStateProperties.ENABLED,
            BlockStateProperties.OCCUPIED,
            BlockStateProperties.SNOWY,
            BlockStateProperties.NOTEBLOCK_INSTRUMENT,
            BlockStateProperties.IN_WALL,
            BlockStateProperties.ATTACHED,
            BlockStateProperties.DISARMED,
            BlockStateProperties.STAIRS_SHAPE,
            BlockStateProperties.POWER,
            BlockStateProperties.DISTANCE,
            BlockStateProperties.STABILITY_DISTANCE,
            BlockStateProperties.BOTTOM,
            BlockStateProperties.BAMBOO_LEAVES,
            BlockStateProperties.SCULK_SENSOR_PHASE,
            BlockStateProperties.SHRIEKING,
            BlockStateProperties.CAN_SUMMON,
            BlockStateProperties.BLOOM,
            BlockStateProperties.TILT,
            BlockStateProperties.CRAFTING,
            BlockStateProperties.PERSISTENT,
            BlockStateProperties.WATERLOGGED);

    private static final List<Property<?>> RESET_WHEN_PLACED = List.of(
            BlockStateProperties.AGE_1,
            BlockStateProperties.AGE_2,
            BlockStateProperties.AGE_3,
            BlockStateProperties.AGE_4,
            BlockStateProperties.AGE_5,
            BlockStateProperties.AGE_7,
            BlockStateProperties.AGE_15,
            BlockStateProperties.AGE_25,
            BlockStateProperties.STAGE,
            BlockStateProperties.BITES,
            BlockStateProperties.LEVEL_HONEY,
            BlockStateProperties.LEVEL_COMPOSTER,
            BlockStateProperties.BERRIES,
            BlockStateProperties.HATCH,
            BlockStateProperties.DUSTED,
            BlockStateProperties.EXTENDED,
            BlockStateProperties.CHISELED_BOOKSHELF_SLOT_0_OCCUPIED,
            BlockStateProperties.CHISELED_BOOKSHELF_SLOT_1_OCCUPIED,
            BlockStateProperties.CHISELED_BOOKSHELF_SLOT_2_OCCUPIED,
            BlockStateProperties.CHISELED_BOOKSHELF_SLOT_3_OCCUPIED,
            BlockStateProperties.CHISELED_BOOKSHELF_SLOT_4_OCCUPIED,
            BlockStateProperties.CHISELED_BOOKSHELF_SLOT_5_OCCUPIED,
            BlockStateProperties.HAS_BOOK,
            BlockStateProperties.HAS_RECORD);

    private StateRules() {
    }

    public static BlockState forPlacement(BlockState target) {
        BlockState state = target;
        BlockState defaults = target.getBlock().defaultBlockState();
        for (Property<?> property : RESET_WHEN_PLACED) {
            if (state.hasProperty(property)) {
                state = copy(state, defaults, property);
            }
        }
        if (state.hasProperty(BlockStateProperties.PERSISTENT)) {
            state = state.setValue(BlockStateProperties.PERSISTENT, true);
        }
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) {
            state = state.setValue(BlockStateProperties.WATERLOGGED, false);
        }
        return state;
    }

    public static boolean matches(BlockState target, BlockState actual) {
        if (target.getBlock() != actual.getBlock()) {
            return false;
        }
        Set<Property<?>> ignored = ignoredProperties(target.getBlock());
        for (Property<?> property : target.getProperties()) {
            if (!ignored.contains(property) && !target.getValue(property).equals(actual.getValue(property))) {
                return false;
            }
        }
        return true;
    }

    public static boolean matchesIncludingWater(BlockState target, BlockState actual) {
        return matches(target, actual)
                && target.getOptionalValue(BlockStateProperties.WATERLOGGED).equals(actual.getOptionalValue(BlockStateProperties.WATERLOGGED));
    }

    public static boolean fixableByHand(BlockState target, BlockState actual) {
        if (target.getBlock() != actual.getBlock()) {
            return false;
        }
        Set<Property<?>> ignored = ignoredProperties(target.getBlock());
        Set<Property<?>> interactive = interactiveProperties(target.getBlock());
        boolean differs = false;
        for (Property<?> property : target.getProperties()) {
            if (ignored.contains(property) || target.getValue(property).equals(actual.getValue(property))) {
                continue;
            }
            if (!interactive.contains(property)) {
                return false;
            }
            differs = true;
        }
        return differs;
    }

    private static Set<Property<?>> ignoredProperties(Block block) {
        Set<Property<?>> ignored = new HashSet<>(ENVIRONMENT);
        ignored.addAll(RESET_WHEN_PLACED);
        if (block instanceof LeverBlock) {
            ignored.remove(BlockStateProperties.POWERED);
        }
        if (block instanceof CrossCollisionBlock || block instanceof TripWireBlock || block instanceof ChorusPlantBlock) {
            ignored.addAll(List.of(BlockStateProperties.NORTH, BlockStateProperties.EAST, BlockStateProperties.SOUTH, BlockStateProperties.WEST));
        }
        if (block instanceof ChorusPlantBlock) {
            ignored.addAll(List.of(BlockStateProperties.UP, BlockStateProperties.DOWN));
        }
        if (block instanceof WallBlock) {
            ignored.addAll(List.of(BlockStateProperties.NORTH_WALL, BlockStateProperties.EAST_WALL, BlockStateProperties.SOUTH_WALL,
                    BlockStateProperties.WEST_WALL, BlockStateProperties.UP));
        }
        if (block instanceof RedStoneWireBlock) {
            ignored.addAll(List.of(BlockStateProperties.NORTH_REDSTONE, BlockStateProperties.EAST_REDSTONE,
                    BlockStateProperties.SOUTH_REDSTONE, BlockStateProperties.WEST_REDSTONE));
        }
        return ignored;
    }

    private static Set<Property<?>> interactiveProperties(Block block) {
        if (block instanceof DoorBlock door) {
            return door.type().canOpenByHand() ? Set.of(BlockStateProperties.OPEN) : Set.of();
        }
        if (block instanceof TrapDoorBlock) {
            return block == Blocks.IRON_TRAPDOOR ? Set.of() : Set.of(BlockStateProperties.OPEN);
        }
        if (block instanceof FenceGateBlock) {
            return Set.of(BlockStateProperties.OPEN);
        }
        if (block instanceof LeverBlock) {
            return Set.of(BlockStateProperties.POWERED);
        }
        if (block instanceof RepeaterBlock) {
            return Set.of(BlockStateProperties.DELAY);
        }
        if (block instanceof ComparatorBlock) {
            return Set.of(BlockStateProperties.MODE_COMPARATOR);
        }
        if (block instanceof NoteBlock) {
            return Set.of(BlockStateProperties.NOTE);
        }
        if (block instanceof DaylightDetectorBlock) {
            return Set.of(BlockStateProperties.INVERTED);
        }
        return Set.of();
    }

    private static <T extends Comparable<T>> BlockState copy(BlockState state, BlockState from, Property<T> property) {
        return state.setValue(property, from.getValue(property));
    }
}
