package io.github.apperk274.journeymen.hire;

import io.github.apperk274.journeymen.block.ModBlocks;
import io.github.apperk274.journeymen.block.RecruitersStallBlock;
import io.github.apperk274.journeymen.block.StallPart;
import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.ForemanEntity;
import io.github.apperk274.journeymen.entity.JourneymanEntity;
import io.github.apperk274.journeymen.entity.JourneymanNames;
import io.github.apperk274.journeymen.entity.JourneymanRegistry;
import io.github.apperk274.journeymen.entity.ModEntities;
import io.github.apperk274.journeymen.entity.RecruiterEntity;
import io.github.apperk274.journeymen.network.OpenHireScreenPayload;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public final class HireService {

    public static final double RANGE = 8.0;
    private static final int[][] BESIDE = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};

    private HireService() {
    }

    @Nullable
    public static HirePrice cost() {
        return HirePrice.parse(JourneymenConfig.HIRE_COST_ITEM.get());
    }

    public static int foremanPrice(int owned) {
        return JourneymenConfig.FOREMAN_COST_BASE.get() + JourneymenConfig.FOREMAN_COST_STEP.get() * owned;
    }

    public static int builderPrice(int owned) {
        return JourneymenConfig.HIRE_COST_BASE.get() + JourneymenConfig.HIRE_COST_STEP.get() * owned;
    }

    public static int owned(ServerPlayer player, JourneymanRegistry.Kind kind) {
        return JourneymanRegistry.get(player.server).ownedBy(player.getUUID(), kind).size();
    }

    public static void openScreen(ServerPlayer player, RecruiterEntity recruiter) {
        HirePrice cost = cost();
        if (cost == null) {
            player.sendSystemMessage(Component.literal("The hire price item in the Journeymen config ("
                    + JourneymenConfig.HIRE_COST_ITEM.get() + ") doesn't exist. Ask an admin to fix hire_cost_item.").withStyle(ChatFormatting.RED));
            return;
        }
        int owned = owned(player, JourneymanRegistry.Kind.FOREMAN);
        String name = JourneymanNames.random(player.getRandom(), new HashSet<>(JourneymanRegistry.get(player.server).namesOf(player.getUUID())));
        Services.NETWORK.sendToPlayer(player, new OpenHireScreenPayload(recruiter.getId(), cost.display(player.getInventory(), foremanPrice(owned)),
                JourneymenConfig.HIRE_COST_ITEM.get(), owned, JourneymenConfig.MAX_FOREMEN_PER_PLAYER.get(), name));
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

    public static String pickName(JourneymanRegistry registry, UUID owner, RandomSource random, @Nullable String requested) {
        return requested == null || requested.isBlank() ? JourneymanNames.random(random, new HashSet<>(registry.namesOf(owner))) : requested.strip();
    }

    @Nullable
    public static String nameProblem(JourneymanRegistry registry, UUID owner, String ownerName, String name, boolean own) {
        String problem = JourneymanNames.validate(name);
        if (problem != null) {
            return problem;
        }
        if (registry.isNameTaken(owner, name, null)) {
            return (own ? "You already have" : ownerName + " already has") + " a journeyman named " + name + ".";
        }
        return null;
    }

    public static List<Component> hire(ServerPlayer player, RecruiterEntity recruiter, @Nullable String requestedName) {
        if (!recruiter.isAlive() || recruiter.level() != player.level() || player.distanceTo(recruiter) > RANGE) {
            return error("You're too far away from the recruiter.");
        }
        BlockPos origin = recruiter.getStall();
        if (origin == null || !RecruitersStallBlock.isIntactCore(player.level(), origin, ModBlocks.RECRUITERS_STALL)) {
            return error("This recruiter has no stall anymore.");
        }
        HirePrice cost = cost();
        if (cost == null) {
            return error("The hire price item in the Journeymen config doesn't exist. Ask an admin to fix hire_cost_item.");
        }
        JourneymanRegistry registry = JourneymanRegistry.get(player.server);
        int owned = owned(player, JourneymanRegistry.Kind.FOREMAN);
        int max = JourneymenConfig.MAX_FOREMEN_PER_PLAYER.get();
        if (max > 0 && owned >= max) {
            return error("You already have " + owned + " foremen, the most you can hire.");
        }
        String name = pickName(registry, player.getUUID(), player.getRandom(), requestedName);
        String problem = nameProblem(registry, player.getUUID(), player.getGameProfile().getName(), name, true);
        if (problem != null) {
            return error(problem);
        }
        int price = foremanPrice(owned);
        Payment payment = pay(player, cost, price, "a foreman");
        if (payment.error() != null) {
            return List.of(payment.error());
        }
        ServerLevel level = player.serverLevel();
        BlockState core = level.getBlockState(origin);
        Direction facing = core.getValue(RecruitersStallBlock.FACING);
        BlockPos spot = StallPart.offset(origin, facing, 0, 0, -1);
        ForemanEntity foreman = spawnForeman(level, player.getUUID(), player.getGameProfile().getName(), name, Vec3.atBottomCenterOf(spot),
                facing.toYRot());
        if (foreman == null) {
            return error("Could not hire a foreman.");
        }
        return List.of(Component.literal("You hired " + name + " for ").append(payment.paid())
                .append(Component.literal(". He hires builders for you: /journeymen foreman hire " + name + ".")).withStyle(ChatFormatting.GREEN));
    }

    public static List<Component> hireBuilder(ServerPlayer player, ForemanEntity foreman, @Nullable String requestedName) {
        if (!foreman.isAlive() || foreman.level() != player.level() || player.distanceTo(foreman) > RANGE) {
            return error("You need to be within " + (int) RANGE + " blocks of " + foreman.getJourneymanName() + " to hire a builder.");
        }
        if (!foreman.canBeCommandedBy(player)) {
            return error(foreman.getJourneymanName() + " works for someone else.");
        }
        HirePrice cost = cost();
        if (cost == null) {
            return error("The hire price item in the Journeymen config doesn't exist. Ask an admin to fix hire_cost_item.");
        }
        int maxCrew = JourneymenConfig.MAX_CREW_SIZE.get();
        if (foreman.isCrewFull()) {
            return error(foreman.getJourneymanName() + "'s crew is full (" + maxCrew + " builders).");
        }
        UUID owner = foreman.getOwner();
        if (owner == null) {
            return error(foreman.getJourneymanName() + " has no owner.");
        }
        JourneymanRegistry registry = JourneymanRegistry.get(player.server);
        boolean own = owner.equals(player.getUUID());
        int owned = registry.ownedBy(owner, JourneymanRegistry.Kind.BUILDER).size();
        int max = JourneymenConfig.MAX_BUILDERS_PER_PLAYER.get();
        if (max > 0 && owned >= max) {
            return error((own ? "You already have " : foreman.getOwnerName() + " already has ") + owned + " builders, the most you can hire.");
        }
        String name = pickName(registry, owner, player.getRandom(), requestedName);
        String problem = nameProblem(registry, owner, foreman.getOwnerName(), name, own);
        if (problem != null) {
            return error(problem);
        }
        int price = builderPrice(owned);
        Payment payment = pay(player, cost, price, "a builder");
        if (payment.error() != null) {
            return List.of(payment.error());
        }
        BuilderEntity builder = spawnBuilder((ServerLevel) foreman.level(), foreman, name, besideOf(foreman), foreman.getYRot());
        if (builder == null) {
            return error("Could not hire a builder.");
        }
        return List.of(Component.literal(foreman.getJourneymanName() + " hired " + name + " for ").append(payment.paid())
                .append(Component.literal(".")).withStyle(ChatFormatting.GREEN));
    }

    private record Payment(@Nullable Component paid, @Nullable Component error) {
    }

    private static Payment pay(ServerPlayer player, HirePrice cost, int price, String what) {
        int have = cost.count(player.getInventory());
        Component itemName = cost.display(player.getInventory(), 1).getHoverName();
        if (!player.getAbilities().instabuild && have < price) {
            return new Payment(null, Component.literal("Hiring " + what + " costs " + price + " ").append(itemName)
                    .append(Component.literal(", but you only have " + have + ".")).withStyle(ChatFormatting.RED));
        }
        if (!player.getAbilities().instabuild) {
            player.getInventory().clearOrCountMatchingItems(cost::matches, price, player.inventoryMenu.getCraftSlots());
        }
        return new Payment(Component.literal(price + " ").append(itemName), null);
    }

    private static List<Component> error(String message) {
        return List.of(Component.literal(message).withStyle(ChatFormatting.RED));
    }

    public static Vec3 besideOf(JourneymanEntity journeyman) {
        ServerLevel level = (ServerLevel) journeyman.level();
        BlockPos base = journeyman.blockPosition();
        for (int[] offset : BESIDE) {
            BlockPos pos = base.offset(offset[0], 0, offset[1]);
            Vec3 feet = Vec3.atBottomCenterOf(pos);
            if (level.noCollision(journeyman.getType().getDimensions().makeBoundingBox(feet)) && !level.getBlockState(pos.below()).isAir()) {
                return feet;
            }
        }
        return journeyman.position();
    }

    @Nullable
    public static ForemanEntity spawnForeman(ServerLevel level, UUID owner, String ownerName, String name, Vec3 pos, float yaw) {
        ForemanEntity foreman = ModEntities.FOREMAN.create(level);
        if (foreman == null) {
            return null;
        }
        foreman.moveTo(pos.x, pos.y, pos.z, yaw, 0.0F);
        foreman.setYHeadRot(yaw);
        foreman.assignOwner(owner, ownerName, name);
        if (!level.addFreshEntity(foreman)) {
            JourneymanRegistry.get(level.getServer()).remove(foreman.getUUID());
            return null;
        }
        return foreman;
    }

    @Nullable
    public static BuilderEntity spawnBuilder(ServerLevel level, ForemanEntity foreman, String name, Vec3 pos, float yaw) {
        BuilderEntity builder = ModEntities.BUILDER.create(level);
        if (builder == null || foreman.getOwner() == null) {
            return null;
        }
        builder.moveTo(pos.x, pos.y, pos.z, yaw, 0.0F);
        builder.setYHeadRot(yaw);
        builder.setForeman(foreman);
        builder.assignOwner(foreman.getOwner(), foreman.getOwnerName(), name);
        if (!level.addFreshEntity(builder)) {
            JourneymanRegistry.get(level.getServer()).remove(builder.getUUID());
            return null;
        }
        return builder;
    }
}
