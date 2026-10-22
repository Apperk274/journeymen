package io.github.apperk274.journeymen.entity;

import com.mojang.authlib.GameProfile;
import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.build.AssignedChest;
import io.github.apperk274.journeymen.build.BuildJob;
import io.github.apperk274.journeymen.build.BuildPlan;
import io.github.apperk274.journeymen.build.ChestVisit;
import io.github.apperk274.journeymen.build.ClearingTask;
import io.github.apperk274.journeymen.build.ItemCounter;
import io.github.apperk274.journeymen.build.PlacingTask;
import io.github.apperk274.journeymen.build.PreCheck;
import io.github.apperk274.journeymen.build.Reach;
import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.entity.ai.BuilderMoveGoal;
import io.github.apperk274.journeymen.platform.Services;
import io.github.apperk274.journeymen.schematic.Schematic;
import io.github.apperk274.journeymen.schematic.SchematicFiles;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.OpenDoorGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Predicate;

public class BuilderEntity extends PathfinderMob {

    public static final int INVENTORY_SIZE = 27;
    private static final int REGISTRY_SYNC_INTERVAL = 40;
    private static final int REGENERATION_INTERVAL = 80;
    private static final double LONG_WALK_DISTANCE = 40.0;
    private static final int MAX_CHESTS = 16;
    private static final double NEAR_SITE = 16.0;
    private static final int NEAR_SITE_STUCK_TICKS = 60;
    private static final int STUCK_TICKS = 1200;
    private static final int PAUSE_RETRY_TICKS = 100;

    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE) {
        @Override
        public boolean stillValid(Player player) {
            return BuilderEntity.this.isAlive() && player.distanceToSqr(BuilderEntity.this) <= 64.0;
        }
    };

    @Nullable
    private UUID owner;
    @Nullable
    private String ownerName;
    private boolean following;
    @Nullable
    private BlockPos comeTarget;
    private final List<AssignedChest> chests = new ArrayList<>();
    @Nullable
    private BuildJob job;
    private double bestSiteDistance = Double.MAX_VALUE;
    private int ticksWithoutProgress;
    private int pausedTicks;
    @Nullable
    private ClearingTask clearing;
    @Nullable
    private PlacingTask placing;
    private int waitTicks;
    private final Deque<ChestVisit> visits = new ArrayDeque<>();
    private final Set<BlockPos> triedDumpChests = new HashSet<>();
    private boolean emptyingOut;
    private boolean clearedSomething;
    private final List<ItemStack> overflow = new ArrayList<>();
    private final Map<BlockPos, Integer> redoCounts = new HashMap<>();
    private final List<BlockPos> unclearable = new ArrayList<>();
    @Nullable
    private String lastNotice;
    private int lastNoticeTick;

    public BuilderEntity(EntityType<? extends BuilderEntity> type, Level level) {
        super(type, level);
        setDropChance(EquipmentSlot.MAINHAND, 0.0F);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.FOLLOW_RANGE, 64.0);
    }

    @Override
    protected PathNavigation createNavigation(Level level) {
        GroundPathNavigation navigation = new GroundPathNavigation(this, level);
        navigation.setCanOpenDoors(true);
        navigation.setCanPassDoors(true);
        navigation.setCanFloat(true);
        return navigation;
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new OpenDoorGoal(this, true));
        goalSelector.addGoal(2, new BuilderMoveGoal(this));
        goalSelector.addGoal(3, new LookAtPlayerGoal(this, Player.class, 8.0F) {
            @Override
            public boolean canUse() {
                return job == null && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return job == null && super.canContinueToUse();
            }
        });
        goalSelector.addGoal(4, new RandomLookAroundGoal(this) {
            @Override
            public boolean canUse() {
                return job == null && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return job == null && super.canContinueToUse();
            }
        });
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

    public String getBuilderName() {
        Component name = getCustomName();
        return name == null ? getType().getDescription().getString() : name.getString();
    }

    public boolean canBeCommandedBy(Player player) {
        return owner == null || owner.equals(player.getUUID()) || player.hasPermissions(2);
    }

    public Container getInventory() {
        return inventory;
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

    public void walkTowards(Vec3 target) {
        if (distanceToSqr(target) > LONG_WALK_DISTANCE * LONG_WALK_DISTANCE) {
            Vec3 step = LandRandomPos.getPosTowards(this, 32, 10, target);
            if (step != null) {
                getNavigation().moveTo(step.x, step.y, step.z, 1.0);
                return;
            }
        }
        getNavigation().moveTo(target.x, target.y, target.z, 1.0);
    }

    public List<AssignedChest> getChests() {
        return List.copyOf(chests);
    }

    @Nullable
    public String addChest(BlockPos pos, AssignedChest.Role role) {
        if (chests.size() >= MAX_CHESTS) {
            return "A builder can have at most " + MAX_CHESTS + " chests.";
        }
        if (!(level().getBlockEntity(pos) instanceof Container)) {
            return "There is no chest or other container at " + pos.toShortString() + ".";
        }
        BlockPos otherHalf = otherChestHalf(pos);
        for (AssignedChest chest : chests) {
            if (chest.pos().equals(pos) || chest.pos().equals(otherHalf)) {
                return "That chest is already assigned as a " + chest.role().id() + " chest.";
            }
        }
        chests.add(new AssignedChest(pos.immutable(), role));
        return null;
    }

    public boolean removeChest(BlockPos pos) {
        return chests.removeIf(chest -> chest.pos().equals(pos));
    }

    @Nullable
    private BlockPos otherChestHalf(BlockPos pos) {
        BlockState state = level().getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock && state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) {
            return pos.relative(ChestBlock.getConnectedDirection(state));
        }
        return null;
    }

    public List<ItemStack> collectStock(List<Component> problems) {
        List<ItemStack> stock = new ArrayList<>();
        for (ItemStack stack : inventory.getItems()) {
            if (!stack.isEmpty()) {
                stock.add(stack.copy());
            }
        }
        for (AssignedChest chest : chests) {
            if (chest.role() != AssignedChest.Role.SUPPLY) {
                continue;
            }
            if (!level().isLoaded(chest.pos())) {
                problems.add(Component.literal("The supply chest at " + chest.pos().toShortString() + " isn't loaded."));
                continue;
            }
            Container container = HopperBlockEntity.getContainerAt(level(), chest.pos());
            if (container == null) {
                problems.add(Component.literal("There is no container at " + chest.pos().toShortString() + " anymore (supply chest)."));
                continue;
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty()) {
                    stock.add(stack.copy());
                }
            }
        }
        return stock;
    }

    public long countInDumpChests(ItemStack template) {
        long count = 0;
        for (AssignedChest chest : chests) {
            if (chest.role() != AssignedChest.Role.DUMP || !level().isLoaded(chest.pos())) {
                continue;
            }
            Container container = HopperBlockEntity.getContainerAt(level(), chest.pos());
            if (container == null) {
                continue;
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (ItemStack.isSameItemSameComponents(stack, template)) {
                    count += stack.getCount();
                }
            }
        }
        return count;
    }

    public int countSupplyChests() {
        return (int) chests.stream().filter(chest -> chest.role() == AssignedChest.Role.SUPPLY).count();
    }

    @Nullable
    public BuildJob getJob() {
        return job;
    }

    public void startJob(BuildJob newJob, Schematic schematic, BuildPlan plan) {
        job = newJob;
        newJob.setLoaded(schematic, plan);
        newJob.setPhase(BuildJob.Phase.WALKING);
        following = false;
        comeTarget = null;
        bestSiteDistance = Double.MAX_VALUE;
        ticksWithoutProgress = 0;
        getNavigation().stop();
        updateJobArea();
    }

    public void cancelJob() {
        stopWork();
        job = null;
        redoCounts.clear();
        unclearable.clear();
        for (ItemStack stack : overflow) {
            ItemStack remainder = inventory.addItem(stack);
            if (!remainder.isEmpty()) {
                spawnAtLocation(remainder);
            }
        }
        overflow.clear();
        updateJobArea();
    }

    private void stopWork() {
        if (clearing != null) {
            clearing.stop();
            clearing = null;
        }
        if (placing != null) {
            placing.stop();
            placing = null;
        }
        waitTicks = 0;
        if (level() instanceof ServerLevel serverLevel) {
            visits.forEach(visit -> visit.abort(serverLevel));
        }
        visits.clear();
        triedDumpChests.clear();
        emptyingOut = false;
        getNavigation().stop();
        showInHand(ItemStack.EMPTY);
    }

    @Nullable
    public ClearingTask getClearing() {
        return clearing;
    }

    @Nullable
    public PlacingTask getPlacing() {
        return placing;
    }

    public void stepAwayFrom(BlockPos pos) {
        Vec3 away = DefaultRandomPos.getPosAway(this, 4, 2, Vec3.atCenterOf(pos));
        if (away != null) {
            getNavigation().moveTo(away.x, away.y, away.z, 1.0);
        }
    }

    public boolean hasItems(List<ItemStack> required) {
        ItemCounter needed = new ItemCounter();
        required.forEach(stack -> needed.add(stack, stack.getCount()));
        ItemCounter available = inventoryCounter();
        for (Map.Entry<ItemStack, Long> entry : needed.sorted()) {
            if (available.count(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private ItemCounter inventoryCounter() {
        ItemCounter counter = new ItemCounter();
        for (ItemStack stack : inventory.getItems()) {
            counter.add(stack, stack.getCount());
        }
        return counter;
    }

    public List<ItemStack> takeItems(List<ItemStack> required) {
        List<ItemStack> taken = new ArrayList<>();
        for (ItemStack wanted : required) {
            int remaining = wanted.getCount();
            ItemStack result = wanted.copyWithCount(0);
            for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, wanted)) {
                    int count = Math.min(remaining, stack.getCount());
                    if (result.isEmpty()) {
                        result = stack.copyWithCount(count);
                    } else {
                        result.grow(count);
                    }
                    stack.shrink(count);
                    remaining -= count;
                }
            }
            taken.add(result);
        }
        inventory.setChanged();
        return taken;
    }

    public void giveItem(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack remainder = inventory.addItem(stack);
        if (!remainder.isEmpty()) {
            overflow.add(remainder);
        }
    }

    public int findSlot(Predicate<ItemStack> predicate) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && predicate.test(stack)) {
                return slot;
            }
        }
        return -1;
    }

    public void fetchAmounts(Container container, ItemCounter amounts) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            long wanted = stack.isEmpty() ? 0 : amounts.count(stack);
            if (wanted <= 0) {
                continue;
            }
            int count = (int) Math.min(wanted, stack.getCount());
            ItemStack remainder = inventory.addItem(stack.copyWithCount(count));
            int moved = count - remainder.getCount();
            if (moved > 0) {
                amounts.remove(stack, moved);
                stack.shrink(moved);
                container.setItem(slot, stack.isEmpty() ? ItemStack.EMPTY : stack);
            }
        }
        inventory.setChanged();
    }

    public ServerPlayer actor() {
        ServerLevel serverLevel = (ServerLevel) level();
        ServerPlayer actor = Services.PLATFORM.getFakePlayer(serverLevel, new GameProfile(owner, getOwnerName()));
        if (actor.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) {
            actor.setGameMode(GameType.SURVIVAL);
        }
        actor.moveTo(getX(), getY(), getZ(), getYRot(), getXRot());
        return actor;
    }

    public void showInHand(ItemStack stack) {
        ItemStack shown = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        if (!ItemStack.matches(getMainHandItem(), shown)) {
            setItemSlot(EquipmentSlot.MAINHAND, shown);
        }
    }

    public void captureDrops(BlockPos pos) {
        for (ItemEntity item : level().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(1.5))) {
            if (item.isRemoved() || item.getAge() != 0) {
                continue;
            }
            ItemStack remainder = inventory.addItem(item.getItem().copy());
            if (!remainder.isEmpty()) {
                overflow.add(remainder);
            }
            item.discard();
        }
    }

    public void dumpInto(Container container) {
        for (int i = 0; i < overflow.size(); i++) {
            overflow.set(i, HopperBlockEntity.addItem(null, container, overflow.get(i), null));
        }
        overflow.removeIf(ItemStack::isEmpty);
        ItemCounter keep = itemsToKeep();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || isTool(stack)) {
                continue;
            }
            int dumpCount = stack.getCount() - (int) keep.remove(stack, stack.getCount());
            if (dumpCount <= 0) {
                continue;
            }
            ItemStack remainder = HopperBlockEntity.addItem(null, container, stack.split(dumpCount), null);
            stack.grow(remainder.getCount());
        }
        inventory.setChanged();
    }

    private ItemCounter itemsToKeep() {
        return job != null && job.getPlan() != null ? job.getPlan().requirements(registryAccess()).items() : new ItemCounter();
    }

    private boolean hasDumpableItems() {
        if (!overflow.isEmpty()) {
            return true;
        }
        ItemCounter keep = itemsToKeep();
        for (ItemStack stack : inventory.getItems()) {
            if (!stack.isEmpty() && !isTool(stack) && keep.remove(stack, stack.getCount()) < stack.getCount()) {
                return true;
            }
        }
        return false;
    }

    public void fetchFrom(Container container, Predicate<ItemStack> wanted) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && wanted.test(stack)) {
                container.setItem(slot, inventory.addItem(stack.copy()));
            }
        }
        inventory.setChanged();
    }

    private static boolean isTool(ItemStack stack) {
        return stack.get(DataComponents.TOOL) != null || stack.isDamageableItem();
    }

    private boolean hasFreeSlot() {
        for (ItemStack stack : inventory.getItems()) {
            if (stack.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private void pauseJob(String reason) {
        if (job == null) {
            return;
        }
        if (clearing != null) {
            clearing.stop();
        }
        if (level() instanceof ServerLevel serverLevel) {
            visits.forEach(visit -> visit.abort(serverLevel));
        }
        visits.clear();
        getNavigation().stop();
        job.pause(reason);
        pausedTicks = 0;
        int reminderTicks = JourneymenConfig.REMINDER_INTERVAL_SECONDS.get() * 20;
        if (!reason.equals(lastNotice) || tickCount - lastNoticeTick >= reminderTicks) {
            notifyOwner(reason);
            lastNotice = reason;
            lastNoticeTick = tickCount;
        }
    }

    private boolean handleVisits() {
        ChestVisit visit = visits.peek();
        if (visit != null) {
            switch (visit.tick(this)) {
                case WORKING -> {
                }
                case DONE -> {
                    visits.poll();
                    if (visit.getKind() == ChestVisit.Kind.DUMP && emptyingOut) {
                        queueEmptyingOut();
                    } else if (visit.getKind() == ChestVisit.Kind.DUMP) {
                        if (!overflow.isEmpty() || !hasFreeSlot()) {
                            startDump();
                        } else {
                            triedDumpChests.clear();
                        }
                    }
                }
                case FAILED -> {
                    visits.poll();
                    if (visit.getKind() == ChestVisit.Kind.DUMP && emptyingOut) {
                        queueEmptyingOut();
                    } else if (visit.getKind() == ChestVisit.Kind.DUMP) {
                        startDump();
                    } else {
                        pauseJob(visit.getFailure() == null ? "I couldn't get to a supply chest." : visit.getFailure());
                    }
                }
            }
            return true;
        }
        return false;
    }

    private void tickClearing() {
        if (handleVisits()) {
            return;
        }
        if (emptyingOut) {
            queueEmptyingOut();
            return;
        }
        if (!overflow.isEmpty()) {
            startDump();
            return;
        }
        if (clearing == null && !prepareClearing()) {
            return;
        }
        switch (clearing.tick()) {
            case WORKING -> {
            }
            case NEEDS_DUMP -> startDump();
            case NEEDS_TOOL -> startFetch(clearing.getNeededTool(), clearing.getNeededToolDescription());
            case BLOCKED -> pauseJob(clearing.getBlockedReason());
            case DONE -> finishClearing();
        }
    }

    private boolean prepareClearing() {
        if (job.getSchematic() == null) {
            return false;
        }
        BuildPlan plan = job.getPlan();
        if (plan == null) {
            if (!BuildPlan.isLoaded((ServerLevel) level(), job.getArea())) {
                pauseJob("Part of the site of " + job.getFile() + " isn't loaded.");
                return false;
            }
            plan = BuildPlan.create((ServerLevel) level(), job.getSchematic(), job.transform());
            job.setPlan(plan);
        }
        clearing = new ClearingTask(this, job, plan.getToClear(), plan.getFluidsInTheWay(), plan.targetsByPosition());
        ClearingTask task = clearing;
        for (AssignedChest chest : chests) {
            if (chest.role() != AssignedChest.Role.SUPPLY || !level().isLoaded(chest.pos())) {
                continue;
            }
            Container container = HopperBlockEntity.getContainerAt(level(), chest.pos());
            if (container != null && containsMatching(container, task::isUsefulTool)) {
                visits.add(ChestVisit.fetch(chest.pos(), task::isUsefulTool));
            }
        }
        return true;
    }

    private void finishClearing() {
        clearedSomething |= clearing.getCleared() > 0;
        List<BlockPos> skipped = clearing.getSkipped();
        clearing = null;
        BuildPlan plan = BuildPlan.create((ServerLevel) level(), job.getSchematic(), job.transform());
        job.setPlan(plan);
        List<BlockPos> leftover = new ArrayList<>();
        for (BlockPos pos : plan.getToClear()) {
            if (redoCounts.merge(pos, 1, Integer::sum) <= JourneymenConfig.REDO_LIMIT.get()) {
                leftover.add(pos);
            } else if (!unclearable.contains(pos)) {
                unclearable.add(pos);
            }
        }
        if (!leftover.isEmpty() || !plan.getFluidsInTheWay().isEmpty()) {
            clearing = new ClearingTask(this, job, leftover, plan.getFluidsInTheWay(), plan.targetsByPosition());
            return;
        }
        showInHand(ItemStack.EMPTY);
        if (!unclearable.isEmpty() || !skipped.isEmpty()) {
            BlockPos example = !unclearable.isEmpty() ? unclearable.get(0) : skipped.get(0);
            notifyOwner("I couldn't clear " + Math.max(unclearable.size(), skipped.size()) + " block(s) of the site, e.g. at "
                    + example.toShortString() + ".");
        }
        if (clearedSomething) {
            notifyOwner("The site of " + job.getFile() + " is clear.");
            clearedSomething = false;
        }
        emptyingOut = true;
        triedDumpChests.clear();
        queueEmptyingOut();
    }

    private void queueEmptyingOut() {
        if (hasDumpableItems()) {
            for (AssignedChest chest : chests) {
                if (chest.role() == AssignedChest.Role.DUMP && triedDumpChests.add(chest.pos())) {
                    visits.add(ChestVisit.dump(chest.pos()));
                    return;
                }
            }
            if (!triedDumpChests.isEmpty()) {
                notifyOwner("My dump chests are full, so I'm keeping the rest of what I cleared.");
            }
        }
        emptyingOut = false;
        triedDumpChests.clear();
        job.setPhase(BuildJob.Phase.PLACING);
    }

    private void tickPlacing() {
        if (handleVisits()) {
            return;
        }
        if (!overflow.isEmpty()) {
            startDump();
            return;
        }
        if (placing == null && !preparePlacing()) {
            return;
        }
        PlacingTask.Status status = placing.tick();
        if (status != PlacingTask.Status.WAITING) {
            waitTicks = 0;
        }
        switch (status) {
            case WORKING -> {
            }
            case NEEDS_ITEMS -> fetchMaterials(placing.getNeededItems());
            case NEEDS_TOOL -> startFetch(placing.getNeededTool(), placing.getNeededToolDescription());
            case BLOCKED -> pauseJob(placing.getBlockedReason());
            case WAITING -> {
                int reportTicks = JourneymenConfig.WAIT_REPORT_SECONDS.get() * 20;
                int reminderTicks = JourneymenConfig.REMINDER_INTERVAL_SECONDS.get() * 20;
                waitTicks++;
                if (waitTicks == reportTicks || waitTicks > reportTicks && (waitTicks - reportTicks) % reminderTicks == 0) {
                    BlockPos pos = placing.getWaitingAt();
                    notifyOwner("Someone is standing at " + (pos == null ? "my next spot" : pos.toShortString())
                            + ", where I need to place a block. I'm waiting until they move.");
                }
            }
            case DONE -> finishPlacing();
        }
    }

    private boolean preparePlacing() {
        if (job.getSchematic() == null) {
            return false;
        }
        BuildPlan plan = job.getPlan();
        if (plan == null) {
            if (!BuildPlan.isLoaded((ServerLevel) level(), job.getArea())) {
                pauseJob("Part of the site of " + job.getFile() + " isn't loaded.");
                return false;
            }
            plan = BuildPlan.create((ServerLevel) level(), job.getSchematic(), job.transform());
            job.setPlan(plan);
        }
        placing = new PlacingTask(this, plan, this::withinRedoLimit);
        return true;
    }

    private boolean withinRedoLimit(BlockPos pos) {
        return redoCounts.getOrDefault(pos, 0) <= JourneymenConfig.REDO_LIMIT.get();
    }

    private void fetchMaterials(@Nullable List<ItemStack> immediate) {
        if (!hasFreeSlot() && hasDumpableItems()) {
            startDump();
            return;
        }
        ItemCounter inInventory = inventoryCounter();
        ItemCounter inSupply = new ItemCounter();
        List<Component> ignored = new ArrayList<>();
        collectStock(ignored).forEach(stack -> inSupply.add(stack, stack.getCount()));
        if (immediate != null) {
            for (ItemStack stack : immediate) {
                if (inSupply.count(stack) < stack.getCount()) {
                    pauseForMaterials();
                    return;
                }
            }
        }
        int freeSlots = (int) inventory.getItems().stream().filter(ItemStack::isEmpty).count();
        if (freeSlots == 0) {
            pauseJob("My inventory is full of things I still need, so I can't carry the next materials. Please take out what I don't need.");
            return;
        }
        ItemCounter wanted = new ItemCounter();
        for (Map.Entry<ItemStack, Long> entry : placing.upcomingNeeds(freeSlots).sorted()) {
            long missing = entry.getValue() - inInventory.count(entry.getKey());
            if (missing > 0) {
                wanted.add(entry.getKey(), missing);
            }
        }
        if (immediate != null) {
            for (ItemStack stack : immediate) {
                long missing = stack.getCount() - inInventory.count(stack) - wanted.count(stack);
                if (missing > 0) {
                    wanted.add(stack, missing);
                }
            }
        }
        boolean queued = false;
        for (AssignedChest chest : chests) {
            if (chest.role() != AssignedChest.Role.SUPPLY || !level().isLoaded(chest.pos())) {
                continue;
            }
            Container container = HopperBlockEntity.getContainerAt(level(), chest.pos());
            if (container != null && containsMatching(container, stack -> wanted.count(stack) > 0)) {
                visits.add(ChestVisit.fetchAmounts(chest.pos(), wanted));
                queued = true;
            }
        }
        if (!queued) {
            pauseForMaterials();
        }
    }

    private void pauseForMaterials() {
        ItemCounter available = new ItemCounter();
        List<Component> ignored = new ArrayList<>();
        collectStock(ignored).forEach(stack -> available.add(stack, stack.getCount()));
        List<String> missing = new ArrayList<>();
        for (Map.Entry<ItemStack, Long> entry : placing.remainingNeeds().sorted()) {
            long shortfall = entry.getValue() - available.count(entry.getKey());
            if (shortfall > 0) {
                missing.add(shortfall + " " + entry.getKey().getHoverName().getString());
            }
        }
        if (missing.isEmpty()) {
            missing.add("some materials");
        }
        String list = String.join(", ", missing.subList(0, Math.min(5, missing.size())))
                + (missing.size() > 5 ? " and " + (missing.size() - 5) + " more kinds of items" : "");
        pauseJob("I'm missing " + list + " for " + job.getFile() + ". I'll continue once they're in my inventory or a supply chest.");
    }

    private void finishPlacing() {
        List<BlockPos> skipped = placing.getSkipped();
        placing.stop();
        placing = null;
        BuildPlan plan = BuildPlan.create((ServerLevel) level(), job.getSchematic(), job.transform());
        job.setPlan(plan);
        Set<BlockPos> leftover = new LinkedHashSet<>(plan.getToClear());
        plan.getToPlace().forEach(placement -> leftover.add(placement.pos()));
        plan.getToFix().forEach(placement -> leftover.add(placement.pos()));
        plan.getToWaterlog().forEach(placement -> leftover.add(placement.pos()));
        leftover.addAll(plan.getFluidsInTheWay());
        boolean redo = false;
        for (BlockPos pos : leftover) {
            if (redoCounts.merge(pos, 1, Integer::sum) <= JourneymenConfig.REDO_LIMIT.get()) {
                redo = true;
            } else if (!unclearable.contains(pos)) {
                unclearable.add(pos);
            }
        }
        if (!redo) {
            finishJob(skipped);
            return;
        }
        List<BlockPos> toClear = plan.getToClear().stream().filter(this::withinRedoLimit).toList();
        if (!toClear.isEmpty() || !plan.getFluidsInTheWay().isEmpty()) {
            clearing = new ClearingTask(this, job, toClear, plan.getFluidsInTheWay(), plan.targetsByPosition());
            job.setPhase(BuildJob.Phase.CLEARING);
            return;
        }
        placing = new PlacingTask(this, plan, this::withinRedoLimit);
    }

    private void finishJob(List<BlockPos> skipped) {
        String file = job.getFile();
        Set<BlockPos> problems = new LinkedHashSet<>(unclearable);
        problems.addAll(skipped);
        if (!problems.isEmpty()) {
            notifyOwner("I couldn't build " + problems.size() + " block(s) of " + file + ", e.g. at "
                    + problems.iterator().next().toShortString() + ".");
        }
        notifyOwner("I finished building " + file + ".");
        stopWork();
        job = null;
        redoCounts.clear();
        unclearable.clear();
        updateJobArea();
    }

    private void startDump() {
        for (AssignedChest chest : chests) {
            if (chest.role() == AssignedChest.Role.DUMP && triedDumpChests.add(chest.pos())) {
                visits.add(ChestVisit.dump(chest.pos()));
                return;
            }
        }
        triedDumpChests.clear();
        boolean hasDumpChest = chests.stream().anyMatch(chest -> chest.role() == AssignedChest.Role.DUMP);
        pauseJob(hasDumpChest
                ? "My inventory is full and my dump chests are full too. Please empty them or give me another one."
                : "My inventory is full. Give me a dump chest with /journeymen builder chest " + getBuilderName() + " add <pos> dump.");
    }

    private void startFetch(Predicate<ItemStack> wanted, String description) {
        for (AssignedChest chest : chests) {
            if (chest.role() != AssignedChest.Role.SUPPLY || !level().isLoaded(chest.pos())) {
                continue;
            }
            Container container = HopperBlockEntity.getContainerAt(level(), chest.pos());
            if (container != null && containsMatching(container, wanted)) {
                visits.add(ChestVisit.fetch(chest.pos(), wanted));
                return;
            }
        }
        pauseJob("I need " + description + ".");
    }

    private static boolean containsMatching(Container container, Predicate<ItemStack> wanted) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (!stack.isEmpty() && wanted.test(stack)) {
                return true;
            }
        }
        return false;
    }

    private void updateJobArea() {
        MinecraftServer server = level().getServer();
        if (server != null) {
            BuilderRegistry.get(server).setJobArea(getUUID(), job == null ? null : new BuilderRegistry.JobArea(level().dimension(), job.getArea()));
        }
    }

    public void notifyOwner(String message) {
        Constants.LOG.info("[{}.{}] {}", getOwnerName(), getBuilderName(), message);
        ServerPlayer ownerPlayer = getOwnerPlayer();
        if (ownerPlayer != null) {
            ownerPlayer.sendSystemMessage(Component.literal("[" + getBuilderName() + "] " + message));
        }
    }

    private void tickJob() {
        if (job == null) {
            return;
        }
        switch (job.getPhase()) {
            case LOADING -> reloadJob();
            case WALKING -> walkToSite();
            case CLEARING -> tickClearing();
            case PLACING -> tickPlacing();
            case PAUSED -> {
                if (++pausedTicks >= PAUSE_RETRY_TICKS) {
                    pausedTicks = 0;
                    bestSiteDistance = Double.MAX_VALUE;
                    ticksWithoutProgress = 0;
                    job.setPhase(job.getResumePhase());
                }
            }
            default -> {
            }
        }
    }

    private void reloadJob() {
        if (job == null || job.isLoading()) {
            return;
        }
        BuildJob loadingJob = job;
        loadingJob.setLoading(true);
        updateJobArea();
        MinecraftServer server = level().getServer();
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return SchematicFiles.load(loadingJob.getFile());
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, Util.backgroundExecutor())
                .whenCompleteAsync((schematic, error) -> {
                    if (job != loadingJob || isRemoved()) {
                        return;
                    }
                    if (error != null) {
                        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                        notifyOwner("I stopped working on " + loadingJob.getFile() + " because the file can't be loaded anymore: " + cause.getMessage());
                        cancelJob();
                        return;
                    }
                    loadingJob.setLoaded(schematic, null);
                    loadingJob.setPhase(loadingJob.getResumePhase());
                }, server);
    }

    private void walkToSite() {
        BoundingBox area = job.getArea();
        double distance = PreCheck.horizontalDistance(blockPosition(), area);
        boolean stuckNearby = distance <= NEAR_SITE && ticksWithoutProgress > NEAR_SITE_STUCK_TICKS;
        if (distance <= Reach.HORIZONTAL || stuckNearby) {
            getNavigation().stop();
            job.setPhase(BuildJob.Phase.CLEARING);
            return;
        }
        if (distance < bestSiteDistance - 0.5) {
            bestSiteDistance = distance;
            ticksWithoutProgress = 0;
        } else if (++ticksWithoutProgress > STUCK_TICKS) {
            getNavigation().stop();
            job.pause("can't find a way to the site");
            notifyOwner("I can't find a way to the site of " + job.getFile() + ". I'll try again in a while.");
            return;
        }
        if (tickCount % 20 == 0 || getNavigation().isDone()) {
            double x = Math.max(area.minX(), Math.min(area.maxX(), getX()));
            double z = Math.max(area.minZ(), Math.min(area.maxZ(), getZ()));
            walkTowards(new Vec3(x + 0.5, getY(), z + 0.5));
        }
    }

    public void assignOwner(ServerPlayer player, String name) {
        this.owner = player.getUUID();
        this.ownerName = player.getGameProfile().getName();
        setName(name);
        syncRegistry();
    }

    @Nullable
    public String rename(String newName) {
        String problem = BuilderNames.validate(newName);
        if (problem != null) {
            return problem;
        }
        MinecraftServer server = level().getServer();
        if (owner != null && server != null && BuilderRegistry.get(server).isNameTaken(owner, newName, getUUID())) {
            return "There is already a builder named " + newName + ".";
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
            player.displayClientMessage(Component.literal(getBuilderName() + " works for someone else."), true);
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
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!level().isClientSide) {
            if (canBeCommandedBy(player)) {
                player.openMenu(new SimpleMenuProvider(
                        (id, playerInventory, p) -> ChestMenu.threeRows(id, playerInventory, inventory),
                        getDisplayName()));
            } else {
                player.displayClientMessage(Component.literal(getBuilderName() + " works for someone else."), true);
            }
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
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
        pickUpNearbyItems();
        if (tickCount % REGENERATION_INTERVAL == 0 && getHealth() < getMaxHealth()) {
            heal(1.0F);
        }
        if (tickCount % REGISTRY_SYNC_INTERVAL == 0) {
            syncRegistry();
        }
        tickJob();
    }

    private void pickUpNearbyItems() {
        for (ItemEntity item : level().getEntitiesOfClass(ItemEntity.class, getBoundingBox().inflate(1.0, 0.5, 1.0))) {
            if (item.isRemoved() || item.hasPickUpDelay() || item.getItem().isEmpty()) {
                continue;
            }
            ItemStack stack = item.getItem();
            int before = stack.getCount();
            ItemStack remainder = inventory.addItem(stack.copy());
            int taken = before - remainder.getCount();
            if (taken <= 0) {
                continue;
            }
            take(item, taken);
            if (remainder.isEmpty()) {
                item.discard();
            } else {
                item.setItem(remainder);
            }
        }
    }

    private void syncRegistry() {
        MinecraftServer server = level().getServer();
        if (server == null || owner == null || isRemoved()) {
            return;
        }
        BuilderRegistry registry = BuilderRegistry.get(server);
        if (ownerName == null) {
            ownerName = server.getProfileCache() == null ? null : server.getProfileCache().get(owner).map(GameProfile::getName).orElse(null);
        }
        String name = hasCustomName() ? getBuilderName() : null;
        if (name == null || BuilderNames.validate(name) != null || registry.isNameTaken(owner, name, getUUID())) {
            name = BuilderNames.random(getRandom(), new HashSet<>(registry.namesOf(owner)));
            setName(name);
        }
        registry.put(getUUID(), owner, ownerName == null ? owner.toString() : ownerName, name, level().dimension(), blockPosition());
    }

    @Override
    public void remove(RemovalReason reason) {
        MinecraftServer server = level().getServer();
        if (server != null && reason.shouldDestroy()) {
            BuilderRegistry.get(server).remove(getUUID());
        }
        super.remove(reason);
    }

    @Override
    public void die(DamageSource source) {
        boolean wasDead = dead;
        super.die(source);
        ServerPlayer ownerPlayer = getOwnerPlayer();
        if (!wasDead && dead && ownerPlayer != null) {
            ownerPlayer.sendSystemMessage(getCombatTracker().getDeathMessage());
        }
    }

    @Override
    public int getMaxFallDistance() {
        return getHealth() > 8.0F ? Math.max(super.getMaxFallDistance(), 6) : super.getMaxFallDistance();
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
    protected void dropEquipment() {
        super.dropEquipment();
        Containers.dropContents(level(), this, inventory);
        overflow.forEach(this::spawnAtLocation);
        overflow.clear();
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
        ListTag chestList = new ListTag();
        chests.forEach(chest -> chestList.add(chest.save()));
        tag.put("Chests", chestList);
        if (job != null) {
            tag.put("Job", job.save());
        }
        ListTag overflowList = new ListTag();
        overflow.forEach(stack -> overflowList.add(stack.save(registryAccess())));
        tag.put("Overflow", overflowList);
        CompoundTag inventoryTag = new CompoundTag();
        ContainerHelper.saveAllItems(inventoryTag, inventory.getItems(), registryAccess());
        tag.put("Inventory", inventoryTag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        ownerName = tag.contains("OwnerName") ? tag.getString("OwnerName") : null;
        following = tag.getBoolean("Following");
        chests.clear();
        ListTag chestList = tag.getList("Chests", Tag.TAG_COMPOUND);
        for (int i = 0; i < chestList.size(); i++) {
            AssignedChest chest = AssignedChest.load(chestList.getCompound(i));
            if (chest != null) {
                chests.add(chest);
            }
        }
        job = tag.contains("Job", Tag.TAG_COMPOUND) ? BuildJob.load(tag.getCompound("Job")) : null;
        overflow.clear();
        ListTag overflowList = tag.getList("Overflow", Tag.TAG_COMPOUND);
        for (int i = 0; i < overflowList.size(); i++) {
            ItemStack.parse(registryAccess(), overflowList.getCompound(i)).ifPresent(overflow::add);
        }
        NonNullList<ItemStack> items = NonNullList.withSize(INVENTORY_SIZE, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag.getCompound("Inventory"), items, registryAccess());
        for (int slot = 0; slot < INVENTORY_SIZE; slot++) {
            inventory.setItem(slot, items.get(slot));
        }
    }
}
