package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class BlockDrops {

    public record Made(BlockState from, BlockRequirements.ToolUse toolUse) {
    }

    @Nullable
    private static volatile MinecraftServer server;
    private static final Map<Block, Optional<Block>> PLAIN_DROPS = new ConcurrentHashMap<>();
    private static final Map<Block, Optional<Made>> MADE = new ConcurrentHashMap<>();

    private BlockDrops() {
    }

    public static void setServer(@Nullable MinecraftServer current) {
        server = current;
        PLAIN_DROPS.clear();
        MADE.clear();
    }

    @Nullable
    public static BlockState grownFrom(BlockState target) {
        if (!target.hasProperty(BlockStateProperties.SNOWY)) {
            return null;
        }
        return plainDrop(target.getBlock()).map(Block::defaultBlockState).orElse(null);
    }

    public static BlockState placedFor(BlockState target) {
        BlockState base = grownFrom(target);
        return base != null ? base : target;
    }

    @Nullable
    public static Made made(Block target) {
        return MADE.computeIfAbsent(target, BlockDrops::findMade).orElse(null);
    }

    private static Optional<Made> findMade(Block target) {
        MinecraftServer current = server;
        Optional<Block> source = plainDrop(target);
        if (current == null || source.isEmpty()) {
            return Optional.empty();
        }
        ServerLevel level = current.overworld();
        BlockState from = source.get().defaultBlockState();
        for (BlockRequirements.ToolUse use : BlockRequirements.ToolUse.values()) {
            BlockState result = Services.COMPAT.toolModifiedState(level, from, use);
            if (result != null && result.is(target)) {
                return Optional.of(new Made(from, use));
            }
        }
        return Optional.empty();
    }

    private static Optional<Block> plainDrop(Block block) {
        return PLAIN_DROPS.computeIfAbsent(block, BlockDrops::findPlainDrop);
    }

    private static Optional<Block> findPlainDrop(Block block) {
        MinecraftServer current = server;
        if (current == null) {
            return Optional.empty();
        }
        List<ItemStack> drops;
        try {
            drops = Block.getDrops(block.defaultBlockState(), current.overworld(), BlockPos.ZERO, null);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        if (drops.size() == 1 && drops.get(0).getItem() instanceof BlockItem item && item.getBlock() != block) {
            return Optional.of(item.getBlock());
        }
        return Optional.empty();
    }
}
