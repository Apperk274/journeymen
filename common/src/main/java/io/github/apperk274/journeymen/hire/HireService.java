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
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public final class HireService {

    public static final double RANGE = 8.0;
    private static final int[][] BESIDE = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
    private static final int[][] STALL_FRONT = {{0, -1}, {-1, -1}, {1, -1}, {0, -2}, {-1, -2}, {1, -2}};

    private HireService() {
    }

    @Nullable
    public static HirePrice cost() {
        return HirePrice.parse(JourneymenConfig.HIRE_COST_ITEM.get());
    }

    public static long foremanPrice(int owned) {
        return price(JourneymenConfig.FOREMAN_COST_BASE.get(), JourneymenConfig.FOREMAN_COST_STEP.get(), owned);
    }

    public static long builderPrice(int owned) {
        return price(JourneymenConfig.HIRE_COST_BASE.get(), JourneymenConfig.HIRE_COST_STEP.get(), owned);
    }

    private static long price(long base, long step, long owned) {
        try {
            return Math.addExact(base, Math.multiplyExact(step, owned));
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    public static boolean payable(long price) {
        return price >= 0 && price <= Integer.MAX_VALUE;
    }

    public static int shownPrice(long price) {
        return (int) Math.max(0, Math.min(Integer.MAX_VALUE, price));
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
        Services.NETWORK.sendToPlayer(player, new OpenHireScreenPayload(recruiter.getId(), cost.display(player.getInventory(), shownPrice(foremanPrice(owned))),
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
        Payment payment = payment(player, cost, foremanPrice(owned), "a foreman", "foreman_cost_base or foreman_cost_step");
        if (payment.error() != null) {
            return List.of(payment.error());
        }
        ServerLevel level = player.serverLevel();
        BlockState core = level.getBlockState(origin);
        Direction facing = core.getValue(RecruitersStallBlock.FACING);
        Vec3 spot = stallSpot(level, origin, facing);
        if (spot == null) {
            return error("There is no room in front of the stall for a new foreman. Clear the space in front of the counter.");
        }
        ForemanEntity foreman = spawnForeman(level, player.getUUID(), player.getGameProfile().getName(), name, spot, facing.toYRot());
        if (foreman == null) {
            return error("Could not hire a foreman. Nothing was paid.");
        }
        charge(player, cost, payment);
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
        Payment payment = payment(player, cost, builderPrice(owned), "a builder", "hire_cost_base or hire_cost_step");
        if (payment.error() != null) {
            return List.of(payment.error());
        }
        Vec3 spot = besideOf(foreman);
        if (spot == null) {
            return error("There is no room next to " + foreman.getJourneymanName() + " for a new builder.");
        }
        BuilderEntity builder = spawnBuilder((ServerLevel) foreman.level(), foreman, name, spot, foreman.getYRot());
        if (builder == null) {
            return error("Could not hire a builder. Nothing was paid.");
        }
        charge(player, cost, payment);
        return List.of(Component.literal(foreman.getJourneymanName() + " hired " + name + " for ").append(payment.paid())
                .append(Component.literal(".")).withStyle(ChatFormatting.GREEN));
    }

    @Nullable
    public static String adopt(MinecraftServer server, ForemanEntity foreman, JourneymanRegistry.Entry entry) {
        JourneymanRegistry registry = JourneymanRegistry.get(server);
        if (entry.kind() != JourneymanRegistry.Kind.BUILDER) {
            return entry.name() + " is a " + entry.kind().id() + ", not a builder.";
        }
        if (!entry.owner().equals(foreman.getOwner())) {
            return entry.name() + " and " + foreman.getJourneymanName() + " work for different players.";
        }
        if (entry.foreman() != null) {
            String current = registry.get(entry.foreman()).map(JourneymanRegistry.Entry::name).orElse("another foreman");
            return entry.name() + (entry.foreman().equals(foreman.getUUID()) ? " is already in " + foreman.getJourneymanName() + "'s crew."
                    : " is in " + current + "'s crew. Crews are fixed.");
        }
        if (foreman.isCrewFull()) {
            return foreman.getJourneymanName() + "'s crew is full (" + JourneymenConfig.MAX_CREW_SIZE.get() + " builders).";
        }
        if (registry.loaded(server, entry.id()) instanceof BuilderEntity builder) {
            builder.setForeman(foreman);
        } else {
            registry.setForeman(entry.id(), foreman.getUUID());
        }
        return null;
    }

    private record Payment(int amount, @Nullable Component paid, @Nullable Component error) {
    }

    private static Payment payment(ServerPlayer player, HirePrice cost, long price, String what, String keys) {
        Component itemName = cost.display(player.getInventory(), 1).getHoverName();
        boolean free = player.getAbilities().instabuild;
        if (!free && !payable(price)) {
            return new Payment(0, null, Component.literal("A" + what.substring(1) + " would cost more than " + Integer.MAX_VALUE + " ").append(itemName)
                    .append(Component.literal(" now, which nobody can pay. Ask an admin to lower " + keys + " in the Journeymen config."))
                    .withStyle(ChatFormatting.RED));
        }
        int amount = shownPrice(price);
        int have = cost.count(player.getInventory());
        if (!free && have < amount) {
            return new Payment(0, null, Component.literal("Hiring " + what + " costs " + amount + " ").append(itemName)
                    .append(Component.literal(", but you only have " + have + ".")).withStyle(ChatFormatting.RED));
        }
        return new Payment(free ? 0 : amount, Component.literal(amount + " ").append(itemName), null);
    }

    private static void charge(ServerPlayer player, HirePrice cost, Payment payment) {
        if (payment.amount() > 0) {
            player.getInventory().clearOrCountMatchingItems(cost::matches, payment.amount(), player.inventoryMenu.getCraftSlots());
        }
    }

    private static List<Component> error(String message) {
        return List.of(Component.literal(message).withStyle(ChatFormatting.RED));
    }

    @Nullable
    public static Vec3 besideOf(JourneymanEntity journeyman) {
        ServerLevel level = (ServerLevel) journeyman.level();
        BlockPos base = journeyman.blockPosition();
        for (int[] offset : BESIDE) {
            BlockPos pos = base.offset(offset[0], 0, offset[1]);
            Vec3 feet = Vec3.atBottomCenterOf(pos);
            if (fits(level, ModEntities.BUILDER, feet) && !level.getBlockState(pos.below()).isAir()) {
                return feet;
            }
        }
        return fits(level, ModEntities.BUILDER, journeyman.position()) ? journeyman.position() : null;
    }

    @Nullable
    private static Vec3 stallSpot(ServerLevel level, BlockPos origin, Direction facing) {
        for (int[] offset : STALL_FRONT) {
            Vec3 feet = Vec3.atBottomCenterOf(StallPart.offset(origin, facing, offset[0], 0, offset[1]));
            if (fits(level, ModEntities.FOREMAN, feet)) {
                return feet;
            }
        }
        return null;
    }

    private static boolean fits(ServerLevel level, EntityType<?> type, Vec3 feet) {
        AABB box = type.getDimensions().makeBoundingBox(feet);
        return level.getWorldBorder().isWithinBounds(BlockPos.containing(feet)) && level.noCollision(box) && !level.containsAnyLiquid(box);
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
