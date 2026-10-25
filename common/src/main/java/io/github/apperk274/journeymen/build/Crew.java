package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.BuilderRegistry;
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
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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

    public static List<BuilderEntity> of(BuilderEntity builder) {
        BuildJob job = builder.getJob();
        if (job == null) {
            return List.of(builder);
        }
        List<BuilderEntity> members = loaded(builder.getServer(), job);
        if (!members.contains(builder)) {
            members.add(0, builder);
        }
        return members;
    }

    public static List<AssignedChest> chests(BuilderEntity first, List<BuilderEntity> crew) {
        List<AssignedChest> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Level level = first.level();
        addChests(level, first, result, seen);
        for (BuilderEntity member : crew) {
            if (member != first && member.level() == level) {
                addChests(level, member, result, seen);
            }
        }
        return result;
    }

    private static void addChests(Level level, BuilderEntity member, List<AssignedChest> result, Set<Long> seen) {
        for (AssignedChest chest : member.getChests()) {
            if (seen.add(chestKey(level, chest.pos()))) {
                result.add(chest);
            }
        }
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

    public static List<ItemStack> stock(List<BuilderEntity> crew, List<Component> problems) {
        List<ItemStack> stock = new ArrayList<>();
        for (BuilderEntity member : crew) {
            for (int slot = 0; slot < member.getInventory().getContainerSize(); slot++) {
                ItemStack stack = member.getInventory().getItem(slot);
                if (!stack.isEmpty()) {
                    stock.add(stack.copy());
                }
            }
        }
        BuilderEntity first = crew.get(0);
        stock.addAll(supplyStock(first.level(), chests(first, crew), problems));
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

    public static int countSupplyChests(List<BuilderEntity> crew) {
        return (int) chests(crew.get(0), crew).stream().filter(chest -> chest.role() == AssignedChest.Role.SUPPLY).count();
    }

    public static long countInDumpChests(List<BuilderEntity> crew, ItemStack template) {
        Level level = crew.get(0).level();
        long count = 0;
        for (AssignedChest chest : chests(crew.get(0), crew)) {
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

    @Nullable
    public static String join(BuilderEntity helper, BuilderEntity builder, boolean mayMixOwners) {
        BuildJob job = builder.getJob();
        if (job == null) {
            return builder.getBuilderName() + " has no job to help with.";
        }
        if (helper == builder || job.hasMember(helper.getUUID())) {
            return helper.getBuilderName() + " is already in the crew of " + job.getFile() + ".";
        }
        if (helper.getJob() != null) {
            return helper.getBuilderName() + " is busy with " + helper.getJob().getFile() + ". Cancel that job or leave its crew first.";
        }
        if (!mayMixOwners && !Objects.equals(helper.getOwner(), builder.getOwner())) {
            return "Only operators can put builders of different owners in one crew.";
        }
        if (helper.level() != builder.level()) {
            return helper.getBuilderName() + " is in another dimension.";
        }
        double distance = PreCheck.horizontalDistance(helper.blockPosition(), job.getArea());
        if (distance > JourneymenConfig.MAX_SITE_DISTANCE.get()) {
            return "The site of " + job.getFile() + " is " + Math.round(distance) + " blocks away from " + helper.getBuilderName()
                    + "; the limit is " + JourneymenConfig.MAX_SITE_DISTANCE.get() + ".";
        }
        int max = JourneymenConfig.MAX_CREW_SIZE.get();
        if (max > 0 && job.size() >= max) {
            return "The crew of " + job.getFile() + " is full (" + max + " builders).";
        }
        BuilderRegistry.get(Objects.requireNonNull(builder.getServer())).joinJob(job, helper.getUUID());
        helper.beginJob(job);
        return null;
    }

    public static String names(List<BuilderEntity> crew) {
        List<String> names = crew.stream().map(BuilderEntity::getBuilderName).toList();
        if (names.size() <= 1) {
            return names.isEmpty() ? "" : names.get(0);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }
}
