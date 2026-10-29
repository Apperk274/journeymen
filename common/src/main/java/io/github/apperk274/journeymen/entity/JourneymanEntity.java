package io.github.apperk274.journeymen.entity;

import com.mojang.authlib.GameProfile;
import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.build.BuildJob;
import io.github.apperk274.journeymen.build.PreCheck;
import io.github.apperk274.journeymen.entity.ai.BuilderNodeEvaluator;
import io.github.apperk274.journeymen.entity.ai.JourneymanMoveGoal;
import io.github.apperk274.journeymen.entity.ai.OpenPassageGoal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.UUID;

public abstract class JourneymanEntity extends PathfinderMob {

    private static final int REGISTRY_SYNC_INTERVAL = 40;
    private static final int REGENERATION_INTERVAL = 80;
    private static final double LONG_WALK_DISTANCE = 40.0;
    private static final double LONG_WALK_STEP = 8.0;
    private static final double COME_ARRIVE_DISTANCE = 1.5;

    @Nullable
    private UUID owner;
    @Nullable
    private String ownerName;
    private boolean following;
    @Nullable
    private BlockPos comeTarget;
    private final StuckRecovery stuck = new StuckRecovery(this);
    private boolean gone;

    protected JourneymanEntity(EntityType<? extends JourneymanEntity> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 64.0);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        GroundPathNavigation navigation = new GroundPathNavigation(this, level) {
            @Override
            protected PathFinder createPathFinder(int maxVisitedNodes) {
                nodeEvaluator = new BuilderNodeEvaluator();
                nodeEvaluator.setCanPassDoors(true);
                return new PathFinder(nodeEvaluator, maxVisitedNodes);
            }
        };
        navigation.setCanOpenDoors(true);
        navigation.setCanPassDoors(true);
        navigation.setCanFloat(true);
        return navigation;
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new OpenPassageGoal(this));
        goalSelector.addGoal(2, new JourneymanMoveGoal(this));
        goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0F) {
            @Override
            public boolean canUse() {
                return !isWorking() && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return !isWorking() && super.canContinueToUse();
            }
        });
        goalSelector.addGoal(4, new RandomLookAroundGoal(this) {
            @Override
            public boolean canUse() {
                return !isWorking() && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return !isWorking() && super.canContinueToUse();
            }
        });
    }

    public abstract JourneymanRegistry.Kind kind();

    public abstract boolean isWorking();

    @Nullable
    public abstract BuildJob getJob();

    @Nullable
    protected abstract StuckRecovery.Goal workGoal();

    @Nullable
    protected abstract StuckRecovery.Goal unstickWorkGoal();

    protected abstract int workDone();

    protected abstract void tickWork();

    protected abstract void tickPeriodic();

    @Nullable
    protected UUID registryForeman() {
        return null;
    }

    protected void afterEscape() {
    }

    protected void onGone(boolean died) {
    }

    @Nullable
    public UUID getOwner() {
        return owner;
    }

    @Nullable
    public ServerPlayer getOwnerPlayer() {
        MinecraftServer server = level().getServer();
        return server == null || owner == null ? null : server.getPlayerList().getPlayer(owner);
    }

    public String getOwnerName() {
        return ownerName != null ? ownerName : owner != null ? owner.toString() : "nobody";
    }

    public String getJourneymanName() {
        Component name = getCustomName();
        return name == null ? getType().getDescription().getString() : name.getString();
    }

    public String qualifiedName() {
        return getOwnerName() + "." + getJourneymanName();
    }

    public boolean canBeCommandedBy(Player player) {
        return owner == null || owner.equals(player.getUUID()) || player.hasPermissions(2);
    }

    public boolean isFollowing() {
        return following;
    }

    public void setFollowing(boolean following) {
        this.following = following;
        this.comeTarget = null;
        getNavigation().stop();
    }

    @Nullable
    public BlockPos getComeTarget() {
        return comeTarget;
    }

    public void comeTo(BlockPos target) {
        this.following = false;
        this.comeTarget = target.immutable();
    }

    public void clearComeTarget() {
        this.comeTarget = null;
    }

    protected void stopIdleOrders() {
        following = false;
        comeTarget = null;
    }

    public void walkTowards(Vec3 target) {
        if (distanceToSqr(target) > LONG_WALK_DISTANCE * LONG_WALK_DISTANCE) {
            double distance = Math.sqrt(distanceToSqr(target));
            if (leadsCloser(getNavigation().getPath(), target, distance)) {
                return;
            }
            Path direct = getNavigation().createPath(BlockPos.containing(target), 0);
            if (leadsCloser(direct, target, distance)) {
                getNavigation().moveTo(direct, 1.0);
                return;
            }
            Vec3 step = LandRandomPos.getPosTowards(this, 32, 10, target);
            if (step != null) {
                getNavigation().moveTo(step.x, step.y, step.z, 1.0);
                return;
            }
        }
        getNavigation().moveTo(target.x, target.y, target.z, 1.0);
    }

    private static boolean leadsCloser(@Nullable Path path, Vec3 target, double distance) {
        return path != null && !path.isDone() && path.getEndNode() != null
                && Vec3.atBottomCenterOf(path.getEndNode().asBlockPos()).distanceTo(target) < distance - LONG_WALK_STEP;
    }

    public void notifyOwner(String message) {
        Constants.LOG.info("[{}.{}] {}", getOwnerName(), getJourneymanName(), message);
        deliverToOwner(Component.literal("[" + getJourneymanName() + "] " + message));
    }

    protected void deliverToOwner(Component message) {
        ServerPlayer ownerPlayer = getOwnerPlayer();
        MinecraftServer server = level().getServer();
        if (ownerPlayer != null) {
            ownerPlayer.sendSystemMessage(message);
        } else if (owner != null && server != null) {
            JourneymanRegistry.get(server).addNotice(owner, message.getString());
        }
    }

    protected void reportRepeatedlyStuck(String times, BlockPos from, String goal) {
        notifyOwner("I keep getting stuck: I had to find my way out " + times + ", last at " + from.toShortString() + " on the way to " + goal
                + ". Maybe leave me a way through, like a door or a path.");
    }

    @Nullable
    StuckRecovery.Goal movementGoal() {
        if (!isWorking()) {
            if (comeTarget != null) {
                double distance = Math.sqrt(distanceToSqr(Vec3.atBottomCenterOf(comeTarget)));
                return distance > COME_ARRIVE_DISTANCE ? spotGoal(comeTarget) : null;
            }
            ServerPlayer ownerPlayer = following ? getOwnerPlayer() : null;
            if (ownerPlayer != null && ownerPlayer.level() == level() && !ownerPlayer.isSpectator()
                    && distanceTo(ownerPlayer) >= StuckRecovery.FOLLOW_DISTANCE) {
                return ownerGoal(ownerPlayer);
            }
            return null;
        }
        return workGoal();
    }

    @Nullable
    public String unstick(@Nullable BlockPos fallback) {
        StuckRecovery.Goal goal = movementGoal();
        if (goal == null && isWorking()) {
            goal = unstickWorkGoal();
        }
        ServerPlayer ownerPlayer = following ? getOwnerPlayer() : null;
        if (goal == null && ownerPlayer != null && ownerPlayer.level() == level() && !ownerPlayer.isSpectator()) {
            goal = ownerGoal(ownerPlayer);
        }
        if (goal == null && fallback != null) {
            goal = spotGoal(fallback);
        }
        if (goal == null) {
            return getJourneymanName() + " has nowhere to go.";
        }
        return stuck.escape(goal, true);
    }

    protected StuckRecovery.Goal chestGoal(BlockPos chest) {
        return new StuckRecovery.Goal(StuckRecovery.Kind.CHEST, chest, chest, getEyePosition().distanceTo(Vec3.atCenterOf(chest)),
                "the chest at " + chest.toShortString());
    }

    protected StuckRecovery.Goal ownerGoal(ServerPlayer ownerPlayer) {
        return new StuckRecovery.Goal(StuckRecovery.Kind.OWNER, ownerPlayer.getUUID(), ownerPlayer.blockPosition(), distanceTo(ownerPlayer),
                "its owner " + ownerPlayer.getGameProfile().getName());
    }

    protected StuckRecovery.Goal spotGoal(BlockPos spot) {
        return new StuckRecovery.Goal(StuckRecovery.Kind.SPOT, spot, spot, Math.sqrt(distanceToSqr(Vec3.atBottomCenterOf(spot))),
                spot.toShortString());
    }

    protected StuckRecovery.Goal reachGoal(BlockPos stand) {
        return new StuckRecovery.Goal(StuckRecovery.Kind.SPOT, stand, stand, Math.sqrt(distanceToSqr(Vec3.atBottomCenterOf(stand))),
                "the spot at " + stand.toShortString() + " within reach of the next blocks");
    }

    @Nullable
    protected StuckRecovery.Goal siteGoal(BuildJob job, boolean fromInside) {
        BlockPos outside = outsideOf(job.getArea(), fromInside);
        if (outside == null) {
            return null;
        }
        return new StuckRecovery.Goal(StuckRecovery.Kind.SITE, job.getArea(), outside, PreCheck.horizontalDistance(blockPosition(), job.getArea()),
                "the site of " + job.getFile());
    }

    @Nullable
    protected BlockPos outsideOf(BoundingBox area, boolean fromInside) {
        BlockPos pos = blockPosition();
        int x = Mth.clamp(pos.getX(), area.minX() - 1, area.maxX() + 1);
        int z = Mth.clamp(pos.getZ(), area.minZ() - 1, area.maxZ() + 1);
        if (x >= area.minX() && x <= area.maxX() && z >= area.minZ() && z <= area.maxZ()) {
            if (!fromInside) {
                return null;
            }
            int west = x - area.minX();
            int east = area.maxX() - x;
            int north = z - area.minZ();
            int south = area.maxZ() - z;
            int nearest = Math.min(Math.min(west, east), Math.min(north, south));
            if (nearest == west) {
                x = area.minX() - 1;
            } else if (nearest == east) {
                x = area.maxX() + 1;
            } else if (nearest == north) {
                z = area.minZ() - 1;
            } else {
                z = area.maxZ() + 1;
            }
        }
        return new BlockPos(x, pos.getY(), z);
    }

    void escaped() {
        afterEscape();
        syncRegistry();
    }

    public StuckRecovery getStuckRecovery() {
        return stuck;
    }

    public void assignOwner(UUID ownerId, String ownerName, String name) {
        this.owner = ownerId;
        this.ownerName = ownerName;
        setName(name);
        syncRegistry();
    }

    @Nullable
    public String rename(String newName) {
        String problem = JourneymanNames.validate(newName);
        if (problem != null) {
            return problem;
        }
        MinecraftServer server = level().getServer();
        if (owner != null && server != null && JourneymanRegistry.get(server).isNameTaken(owner, newName, getUUID())) {
            return getOwnerName() + " already has a journeyman named " + newName + ".";
        }
        setName(newName);
        syncRegistry();
        return null;
    }

    public InteractionResult renameWithNameTag(ItemStack stack, Player player) {
        Component name = stack.get(DataComponents.CUSTOM_NAME);
        if (name == null) {
            return InteractionResult.PASS;
        }
        if (level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!canBeCommandedBy(player)) {
            player.displayClientMessage(Component.literal(getJourneymanName() + " works for someone else."), true);
            return InteractionResult.CONSUME;
        }
        String problem = rename(name.getString());
        if (problem != null) {
            player.displayClientMessage(Component.literal(problem), true);
            return InteractionResult.CONSUME;
        }
        stack.shrink(1);
        return InteractionResult.CONSUME;
    }

    private void setName(String name) {
        setCustomName(Component.literal(name));
        setCustomNameVisible(true);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        MinecraftServer server = level().getServer();
        if (server != null && !server.isPvpAllowed() && source.getEntity() instanceof Player player
                && owner != null && !owner.equals(player.getUUID())) {
            return false;
        }
        return super.hurt(source, amount);
    }

    @Override
    public void aiStep() {
        updateSwingTime();
        super.aiStep();
        if (level().isClientSide || !isAlive()) {
            return;
        }
        if (tickCount % REGENERATION_INTERVAL == 0 && getHealth() < getMaxHealth()) {
            heal(1.0F);
        }
        if (tickCount % REGISTRY_SYNC_INTERVAL == 0) {
            syncRegistry();
        }
        tickWork();
        if (isAlive() && !isRemoved()) {
            stuck.tick(movementGoal(), workDone(), isWorking());
        }
        if (isAlive() && !isRemoved()) {
            tickPeriodic();
        }
    }

    public void refreshOwnerName(String name) {
        ownerName = name;
    }

    protected void syncRegistry() {
        MinecraftServer server = level().getServer();
        if (server == null || owner == null || isRemoved()) {
            return;
        }
        JourneymanRegistry registry = JourneymanRegistry.get(server);
        ServerPlayer online = server.getPlayerList().getPlayer(owner);
        if (online != null) {
            ownerName = online.getGameProfile().getName();
        } else {
            registry.get(getUUID()).filter(entry -> entry.owner().equals(owner) && !entry.ownerName().isEmpty())
                    .ifPresent(entry -> ownerName = entry.ownerName());
        }
        if (ownerName == null) {
            ownerName = server.getProfileCache() == null ? null : server.getProfileCache().get(owner).map(GameProfile::getName).orElse(null);
        }
        String name = hasCustomName() ? getJourneymanName() : null;
        if (name == null || JourneymanNames.validate(name) != null || registry.isNameTaken(owner, name, getUUID())) {
            name = JourneymanNames.random(getRandom(), new HashSet<>(registry.namesOf(owner)));
            setName(name);
        }
        registry.put(getUUID(), kind(), owner, ownerName == null ? owner.toString() : ownerName, name, level().dimension(), blockPosition(),
                registryForeman());
    }

    @Override
    public void remove(Entity.RemovalReason reason) {
        MinecraftServer server = level().getServer();
        if (server != null && reason.shouldDestroy() && !gone && !isDeadOrDying()) {
            gone = true;
            onGone(false);
            JourneymanRegistry.get(server).remove(getUUID());
        }
        super.remove(reason);
    }

    @Override
    public void die(DamageSource source) {
        boolean wasDead = dead;
        super.die(source);
        if (!wasDead && dead && !level().isClientSide) {
            Component message = getCombatTracker().getDeathMessage();
            Constants.LOG.info("[{}.{}] {}", getOwnerName(), getJourneymanName(), message.getString());
            deliverToOwner(message);
            if (!gone) {
                gone = true;
                onGone(true);
                MinecraftServer server = level().getServer();
                if (server != null) {
                    JourneymanRegistry.get(server).remove(getUUID());
                }
            }
        }
    }

    @Override
    public boolean canUsePortal(boolean allowPassengers) {
        return !isWorking() && super.canUsePortal(allowPassengers);
    }

    public void stepAside(Vec3 from) {
        Vec3 away = DefaultRandomPos.getPosAway(this, 4, 2, from);
        if (away != null) {
            getNavigation().moveTo(away.x, away.y, away.z, 1.0);
        }
    }

    public boolean isInJobArea(BlockPos pos) {
        return jobAreaAt(pos) != null;
    }

    @Nullable
    public BoundingBox jobAreaAt(BlockPos pos) {
        MinecraftServer server = level().getServer();
        if (server == null) {
            return null;
        }
        for (BuildJob other : JourneymanRegistry.get(server).jobs()) {
            BoundingBox area = other.getArea();
            if (other.getDimension().equals(level().dimension()) && pos.getX() >= area.minX() && pos.getX() <= area.maxX()
                    && pos.getZ() >= area.minZ() && pos.getZ() <= area.maxZ()) {
                return area;
            }
        }
        return null;
    }

    @Override
    public int getMaxFallDistance() {
        return getHealth() > 8.0F ? Math.max(super.getMaxFallDistance(), 6) : super.getMaxFallDistance();
    }

    @Override
    public boolean canBeLeashed() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    @Override
    public boolean requiresCustomPersistence() {
        return true;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
        if (ownerName != null) {
            tag.putString("OwnerName", ownerName);
        }
        tag.putBoolean("Following", following);
        stuck.save(tag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        ownerName = tag.contains("OwnerName") ? tag.getString("OwnerName") : null;
        following = tag.getBoolean("Following");
        stuck.load(tag);
    }
}
