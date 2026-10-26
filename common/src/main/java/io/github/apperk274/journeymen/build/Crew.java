package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.ForemanEntity;
import io.github.apperk274.journeymen.entity.JourneymanEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Crew {

    private Crew() {
    }

    public static List<BuilderEntity> loaded(@Nullable MinecraftServer server, BuildJob job) {
        List<BuilderEntity> members = new ArrayList<>();
        ServerLevel level = server == null ? null : server.getLevel(job.getDimension());
        if (level == null) {
            return members;
        }
        for (UUID id : job.getMembers()) {
            Entity entity = level.getEntity(id);
            if (entity instanceof BuilderEntity builder && builder.isAlive()) {
                members.add(builder);
            }
        }
        return members;
    }

    public static long chestKey(Level level, BlockPos pos) {
        BlockPos other = otherChestHalf(level, pos);
        return other == null ? pos.asLong() : Math.min(pos.asLong(), other.asLong());
    }

    @Nullable
    public static BlockPos otherChestHalf(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return pos.relative(ChestBlock.getConnectedDirection(state));
        }
        return null;
    }

    public static List<ItemStack> stock(ForemanEntity foreman, List<BuilderEntity> builders, List<Component> problems) {
        return stock(foreman.level(), foreman.getChests(), builders, problems);
    }

    public static List<ItemStack> stock(Level level, List<AssignedChest> chests, List<BuilderEntity> builders, List<Component> problems) {
        List<ItemStack> stock = new ArrayList<>();
        for (BuilderEntity builder : builders) {
            for (int slot = 0; slot < builder.getInventory().getContainerSize(); slot++) {
                ItemStack stack = builder.getInventory().getItem(slot);
                if (!stack.isEmpty()) {
                    stock.add(stack.copy());
                }
            }
        }
        stock.addAll(supplyStock(level, chests, problems));
        return stock;
    }

    public static List<ItemStack> supplyStock(Level level, List<AssignedChest> chests, List<Component> problems) {
        List<ItemStack> stock = new ArrayList<>();
        for (AssignedChest chest : chests) {
            if (chest.role() != AssignedChest.Role.SUPPLY) {
                continue;
            }
            if (!level.isLoaded(chest.pos())) {
                problems.add(Component.literal("The supply chest at " + chest.pos().toShortString() + " isn't loaded."));
                continue;
            }
            ItemStore store = ItemStores.at(level, chest.pos());
            if (store == null) {
                problems.add(Component.literal("There is no container at " + chest.pos().toShortString() + " anymore (supply chest)."));
                continue;
            }
            for (int slot = 0; slot < store.size(); slot++) {
                ItemStack stack = store.get(slot);
                if (!stack.isEmpty()) {
                    stock.add(stack.copy());
                }
            }
        }
        return stock;
    }

    public static int countSupplyChests(ForemanEntity foreman) {
        return (int) foreman.getChests().stream().filter(chest -> chest.role() == AssignedChest.Role.SUPPLY).count();
    }

    public static long countInDumpChests(ForemanEntity foreman, ItemStack template) {
        Level level = foreman.level();
        long count = 0;
        for (AssignedChest chest : foreman.getChests()) {
            if (chest.role() != AssignedChest.Role.DUMP || !level.isLoaded(chest.pos())) {
                continue;
            }
            ItemStore store = ItemStores.at(level, chest.pos());
            if (store == null) {
                continue;
            }
            for (int slot = 0; slot < store.size(); slot++) {
                ItemStack stack = store.get(slot);
                if (ItemMatch.same(stack, template)) {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    public static String names(List<? extends JourneymanEntity> crew) {
        return list(crew.stream().map(JourneymanEntity::getJourneymanName).toList());
    }

    public static String list(List<String> names) {
        if (names.size() <= 1) {
            return names.isEmpty() ? "" : names.get(0);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }
}
