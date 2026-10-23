package io.github.apperk274.journeymen.hire;

import io.github.apperk274.journeymen.block.ModBlocks;
import io.github.apperk274.journeymen.block.RecruitersStallBlock;
import io.github.apperk274.journeymen.block.StallPart;
import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.BuilderNames;
import io.github.apperk274.journeymen.entity.BuilderRegistry;
import io.github.apperk274.journeymen.entity.ModEntities;
import io.github.apperk274.journeymen.entity.RecruiterEntity;
import io.github.apperk274.journeymen.network.OpenHireScreenPayload;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;

public final class HireService {

    public static final double RANGE = 8.0;

    private HireService() {
    }

    @Nullable
    public static Item costItem() {
        ResourceLocation id = ResourceLocation.tryParse(JourneymenConfig.HIRE_COST_ITEM.get());
        return id == null ? null : BuiltInRegistries.ITEM.getOptional(id).filter(item -> item != Items.AIR).orElse(null);
    }

    public static int priceFor(int owned) {
        return JourneymenConfig.HIRE_COST_BASE.get() + JourneymenConfig.HIRE_COST_STEP.get() * owned;
    }

    public static int owned(ServerPlayer player) {
        return BuilderRegistry.get(player.server).ownedBy(player.getUUID()).size();
    }

    public static void openScreen(ServerPlayer player, RecruiterEntity recruiter) {
        Item item = costItem();
        if (item == null) {
            player.sendSystemMessage(Component.literal("The hire price item in the Journeymen config ("
                    + JourneymenConfig.HIRE_COST_ITEM.get() + ") doesn't exist. Ask an admin to fix hire_cost_item.").withStyle(ChatFormatting.RED));
            return;
        }
        int owned = owned(player);
        String name = BuilderNames.random(player.getRandom(), new HashSet<>(BuilderRegistry.get(player.server).namesOf(player.getUUID())));
        Services.NETWORK.sendToPlayer(player, new OpenHireScreenPayload(recruiter.getId(), new ItemStack(item, priceFor(owned)), owned,
                JourneymenConfig.MAX_BUILDERS_PER_PLAYER.get(), name));
    }

    @Nullable
    public static RecruiterEntity nearestRecruiter(ServerPlayer player) {
        List<RecruiterEntity> recruiters = player.level().getEntitiesOfClass(RecruiterEntity.class, player.getBoundingBox().inflate(RANGE),
                RecruiterEntity::isAlive);
        RecruiterEntity nearest = null;
        for (RecruiterEntity recruiter : recruiters) {
            if (nearest == null || player.distanceToSqr(recruiter) < player.distanceToSqr(nearest)) {
                nearest = recruiter;
            }
        }
        return nearest;
    }

    public static List<Component> hire(ServerPlayer player, RecruiterEntity recruiter, @Nullable String requestedName) {
        if (!recruiter.isAlive() || recruiter.level() != player.level() || player.distanceTo(recruiter) > RANGE) {
            return List.of(Component.literal("You're too far away from the recruiter.").withStyle(ChatFormatting.RED));
        }
        BlockPos origin = recruiter.getStall();
        if (origin == null || !RecruitersStallBlock.isIntactCore(player.level(), origin, ModBlocks.RECRUITERS_STALL)) {
            return List.of(Component.literal("This recruiter has no stall anymore.").withStyle(ChatFormatting.RED));
        }
        Item item = costItem();
        if (item == null) {
            return List.of(Component.literal("The hire price item in the Journeymen config doesn't exist. Ask an admin to fix hire_cost_item.")
                    .withStyle(ChatFormatting.RED));
        }
        BuilderRegistry registry = BuilderRegistry.get(player.server);
        int owned = owned(player);
        int max = JourneymenConfig.MAX_BUILDERS_PER_PLAYER.get();
        if (max > 0 && owned >= max) {
            return List.of(Component.literal("You already have " + owned + " builders, the most you can hire.").withStyle(ChatFormatting.RED));
        }
        String name = requestedName == null || requestedName.isBlank()
                ? BuilderNames.random(player.getRandom(), new HashSet<>(registry.namesOf(player.getUUID())))
                : requestedName.strip();
        String problem = BuilderNames.validate(name);
        if (problem != null) {
            return List.of(Component.literal(problem).withStyle(ChatFormatting.RED));
        }
        if (registry.isNameTaken(player.getUUID(), name, null)) {
            return List.of(Component.literal("You already have a builder named " + name + ".").withStyle(ChatFormatting.RED));
        }
        int price = priceFor(owned);
        int have = player.getInventory().countItem(item);
        if (!player.getAbilities().instabuild && have < price) {
            return List.of(Component.literal("Hiring a builder costs " + price + " ").append(item.getDescription())
                    .append(Component.literal(", but you only have " + have + ".")).withStyle(ChatFormatting.RED));
        }
        ServerLevel level = player.serverLevel();
        BuilderEntity builder = ModEntities.BUILDER.create(level);
        if (builder == null) {
            return List.of(Component.literal("Could not hire a builder.").withStyle(ChatFormatting.RED));
        }
        if (!player.getAbilities().instabuild) {
            player.getInventory().clearOrCountMatchingItems(stack -> stack.is(item), price, player.inventoryMenu.getCraftSlots());
        }
        BlockState core = level.getBlockState(origin);
        Direction facing = core.getValue(RecruitersStallBlock.FACING);
        BlockPos spot = StallPart.offset(origin, facing, 0, 0, -1);
        builder.moveTo(spot.getX() + 0.5, spot.getY(), spot.getZ() + 0.5, facing.toYRot(), 0.0F);
        builder.setYHeadRot(facing.toYRot());
        builder.assignOwner(player, name);
        if (!level.addFreshEntity(builder)) {
            registry.remove(builder.getUUID());
            return List.of(Component.literal("Could not hire a builder.").withStyle(ChatFormatting.RED));
        }
        return List.of(Component.literal("You hired " + name + " for " + price + " ").append(item.getDescription())
                .append(Component.literal(". Right-click them to give them work.")).withStyle(ChatFormatting.GREEN));
    }
}
