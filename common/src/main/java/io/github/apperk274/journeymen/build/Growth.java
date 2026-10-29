package io.github.apperk274.journeymen.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

public final class Growth {

    private static final int MAX_TREE_BLOCKS = 4096;
    private static final int MAX_COLUMN = 64;

    private Growth() {
    }

    public static boolean grows(BlockState target) {
        return StateRules.forPlacement(target).isRandomlyTicking();
    }

    public static boolean grewFrom(BlockState target, BlockState actual) {
        Block block = target.getBlock();
        if (block instanceof SaplingBlock) {
            return actual.is(BlockTags.LOGS);
        }
        BlockState spreadOnto = BlockDrops.grownFrom(actual);
        if (spreadOnto != null && target.is(spreadOnto.getBlock()) && !target.is(actual.getBlock())) {
            return true;
        }
        if (!grows(target)) {
            return false;
        }
        if (block == actual.getBlock()) {
            return BlockUses.hasFaces(block) && BlockUses.hasFacesOf(actual, target);
        }
        Item item = BlockRequirements.itemOf(block);
        return item != Items.AIR && item == BlockRequirements.itemOf(actual.getBlock());
    }

    public static boolean roomToGrow(BlockState target, BlockState actual) {
        if (actual.isAir() || actual.getBlock() instanceof LiquidBlock) {
            return false;
        }
        if (target.isAir()) {
            return true;
        }
        return target.getBlock() instanceof LiquidBlock && target.getFluidState().isSource() && actual.getFluidState().isSource()
                && actual.getFluidState().getType().isSame(target.getFluidState().getType());
    }

    public static Set<BlockPos> grownInto(ServerLevel level, Collection<BlockPos> roots, Predicate<BlockPos> space,
                                          Function<BlockPos, BlockState> targets) {
        Set<BlockPos> grown = new HashSet<>();
        for (BlockPos root : roots) {
            BlockState target = targets.apply(root);
            if (target.getBlock() instanceof SaplingBlock) {
                tree(level, root, space, grown);
            } else {
                Item item = BlockRequirements.itemOf(target.getBlock());
                for (Direction direction : List.of(Direction.UP, Direction.DOWN)) {
                    BlockPos pos = root.relative(direction);
                    for (int i = 0; i < MAX_COLUMN && space.test(pos) && BlockRequirements.itemOf(level.getBlockState(pos).getBlock()) == item; i++) {
                        grown.add(pos);
                        pos = pos.relative(direction);
                    }
                }
            }
        }
        return grown;
    }

    private static void tree(ServerLevel level, BlockPos root, Predicate<BlockPos> space, Set<BlockPos> grown) {
        Deque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(root);
        seen.add(root);
        while (!queue.isEmpty() && seen.size() < MAX_TREE_BLOCKS) {
            BlockPos pos = queue.poll();
            for (Direction direction : Direction.values()) {
                BlockPos next = pos.relative(direction);
                if (seen.add(next) && space.test(next) && isTreePart(level.getBlockState(next))) {
                    grown.add(next);
                    queue.add(next);
                }
            }
        }
    }

    private static boolean isTreePart(BlockState state) {
        return state.is(BlockTags.LOGS) || state.is(BlockTags.LEAVES);
    }
}
