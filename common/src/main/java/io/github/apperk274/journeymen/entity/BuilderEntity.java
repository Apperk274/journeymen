package io.github.apperk274.journeymen.entity;

import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.build.AssignedChest;
import io.github.apperk274.journeymen.build.BlockRequirements;
import io.github.apperk274.journeymen.build.BuildJob;
import io.github.apperk274.journeymen.build.BuildPlan;
import io.github.apperk274.journeymen.build.ChestVisit;
import io.github.apperk274.journeymen.build.ClearingTask;
import io.github.apperk274.journeymen.build.ClearingWork;
import io.github.apperk274.journeymen.build.CrewWork;
import io.github.apperk274.journeymen.build.Crew;
import io.github.apperk274.journeymen.build.ItemCounter;
import io.github.apperk274.journeymen.build.ItemMatch;
import io.github.apperk274.journeymen.build.ItemStore;
import io.github.apperk274.journeymen.build.ItemStores;
import io.github.apperk274.journeymen.build.OwnerRights;
import io.github.apperk274.journeymen.build.PlacingTask;
import io.github.apperk274.journeymen.build.PlacingWork;
import io.github.apperk274.journeymen.build.PreCheck;
import io.github.apperk274.journeymen.build.Reach;
import io.github.apperk274.journeymen.build.ToolCheck;
import io.github.apperk274.journeymen.build.ToolPlan;
import io.github.apperk274.journeymen.build.WorkState;
import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.menu.BuilderMenu;
import io.github.apperk274.journeymen.menu.BuilderScreenService;
import io.github.apperk274.journeymen.menu.LinkModeService;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
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
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public class BuilderEntity extends JourneymanEntity {

    public static final int INVENTORY_SIZE = 27;
    private static final double NEAR_SITE = 16.0;
    private static final int NEAR_SITE_STUCK_TICKS = 60;
    private static final int STUCK_TICKS = 1200;
    private static final int PAUSE_RETRY_TICKS = 100;
    private static final int STORE_RETRY_TICKS = 1200;
    private static final int CREW_WAIT_TICKS = 20;
    private static final int HAND_BACK_TICKS = 100;
    private static final int STEP_ASIDE_TICKS = 40;
    private static final int LEAVE_SITE_TICKS = 60;
    private static final int LOAD_GRACE_TICKS = 100;
    private static final double WALK_BLOCKS_PER_TICK = 0.2;
    private static final int CHEST_TICKS = 20;
    private static final int FROZEN_TICKS = 5;
    private static final int ASK_ASIDE_TICKS = 20;

    private final SimpleContainer inventory = new SimpleContainer(INVENTORY_SIZE) {
        @Override
        public boolean stillValid(Player player) {
            return BuilderEntity.this.isAlive() && player.distanceToSqr(BuilderEntity.this) <= 64.0;
        }
    };

    @Nullable
    private UUID foremanId;
    @Nullable
    private ForemanEntity cachedForeman;
    private int cachedForemanTick = -1;
    private boolean reconciled;
    private boolean pendingWork;
    private boolean waitingForForeman;
    private boolean elsewhere;
    private long lastWorkTick = -1;
    private int askedAsideTick = Integer.MIN_VALUE / 2;
    @Nullable
    private BuildJob job;
    private final WorkState work = new WorkState();
    private double bestSiteDistance = Double.MAX_VALUE;
    private int ticksWithoutProgress;
    private int pausedTicks;
    @Nullable
    private ClearingTask clearing;
    @Nullable
    private PlacingTask placing;
    @Nullable
    private ClearingTask finishing;
    private int waitTicks;
    private final Deque<ChestVisit> visits = new ArrayDeque<>();
    private final Set<BlockPos> triedDumpChests = new LinkedHashSet<>();
    private final Map<BlockPos, ChestVisit.Problem> dumpProblems = new HashMap<>();
    private boolean emptyingOut;
    private boolean clearedSomething;
    private boolean toolsFetched;
    private int storeBlockedUntil;
    private final List<ItemStack> overflow = new ArrayList<>();
    @Nullable
    private BuildPlan needsPlan;
    private ItemCounter needs = new ItemCounter();
    @Nullable
    private String lastNotice;
    private int lastNoticeTick;
    private int visitsDone;
    private int crewModeTick = -1;
    private boolean crewMode;
    private int crewWaitUntil;
    private int idleSince = -1;
    private boolean idle;
    private int stepAsideUntil;

    public BuilderEntity(EntityType<? extends BuilderEntity> type, Level level) {
        super(type, level);
        setDropChance(EquipmentSlot.MAINHAND, 0.0F);
        for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            setDropChance(slot, 2.0F);
        }
    }

    @Override
    public JourneymanRegistry.Kind kind() {
        return JourneymanRegistry.Kind.BUILDER;
    }

    @Override
    public boolean isWorking() {
        return getJob() != null;
    }

    @Nullable
    public UUID getForemanId() {
        return foremanId;
    }

    @Nullable
    @Override
    protected UUID registryForeman() {
        return foremanId;
    }

    @Nullable
    public ForemanEntity foreman() {
        MinecraftServer server = level().getServer();
        if (foremanId == null || server == null) {
            return null;
        }
        if (cachedForemanTick != tickCount || cachedForeman != null && !cachedForeman.isAlive()) {
            cachedForemanTick = tickCount;
            cachedForeman = JourneymanRegistry.get(server).loaded(server, foremanId) instanceof ForemanEntity found
                    && found.level() instanceof ServerLevel foremanLevel && foremanLevel.isPositionEntityTicking(found.blockPosition()) ? found : null;
        }
        return cachedForeman;
    }

    public void setForeman(@Nullable ForemanEntity foreman) {
        foremanId = foreman == null ? null : foreman.getUUID();
        cachedForemanTick = -1;
        reconciled = true;
        MinecraftServer server = level().getServer();
        if (server != null && JourneymanRegistry.get(server).get(getUUID()).isPresent()) {
            JourneymanRegistry.get(server).setForeman(getUUID(), foremanId);
        }
    }

    public void becomeOrphan() {
        leaveWork();
        setForeman(null);
    }

    private void reconcileForeman() {
        reconciled = true;
        MinecraftServer server = level().getServer();
        if (server == null) {
            return;
        }
        JourneymanRegistry registry = JourneymanRegistry.get(server);
        JourneymanRegistry.Entry mine = registry.get(getUUID()).orElse(null);
        UUID wanted = mine != null ? mine.foreman() : foremanId;
        JourneymanRegistry.Entry foremanEntry = registry.get(wanted).orElse(null);
        if (foremanEntry == null || foremanEntry.kind() != JourneymanRegistry.Kind.FOREMAN || !foremanEntry.owner().equals(getOwner())) {
            wanted = null;
        }
        foremanId = wanted;
        cachedForemanTick = -1;
        if (foremanId == null) {
            pendingWork = false;
            work.reset();
        }
        syncRegistry();
    }

    private void report(String message) {
        ForemanEntity foreman = foreman();
        if (foreman != null) {
            foreman.relay(message);
        } else {
            notifyOwner(message);
        }
    }

    private void announce(String message) {
        ForemanEntity foreman = foreman();
        if (foreman != null) {
            foreman.notifyOwner(message);
        } else {
            notifyOwner(message);
        }
    }

    @Override
    protected void reportRepeatedlyStuck(String times, BlockPos from, String goal) {
        ForemanEntity foreman = foreman();
        if (foreman == null) {
            super.reportRepeatedlyStuck(times, from, goal);
            return;
        }
        foreman.relay(getJourneymanName() + " keeps getting stuck: it had to find its way out " + times + ", last at " + from.toShortString()
                + " on the way to " + goal + ". Maybe leave it a way through, like a door or a path.");
    }

    public Container getInventory() {
        return inventory;
    }

    public List<AssignedChest> jobChests() {
        ForemanEntity foreman = foreman();
        return foreman == null ? List.of() : foreman.getChests();
    }

    public List<ItemStack> collectStock(List<Component> problems) {
        List<ItemStack> stock = new ArrayList<>();
        for (ItemStack stack : inventory.getItems()) {
            if (!stack.isEmpty()) {
                stock.add(stack.copy());
            }
        }
        stock.addAll(Crew.supplyStock(level(), reachableChests(), problems));
        return stock;
    }

    private List<AssignedChest> reachableChests() {
        return jobChests().stream().filter(chest -> !isUnreachable(chest.pos())).toList();
    }

    private boolean isUnreachable(BlockPos chest) {
        return job != null && job.getCrewWork().isUnreachable(chest, level().getGameTime());
    }

    @Nullable
    @Override
    public BuildJob getJob() {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return job;
        }
        if (!reconciled) {
            reconcileForeman();
        }
        if (job == null && pendingWork) {
            pendingWork = false;
            BuildJob found = foremanId == null ? null : JourneymanRegistry.get(serverLevel.getServer()).jobOf(foremanId);
            if (found != null && found.hasMember(getUUID())) {
                job = found;
            } else {
                work.reset();
            }
        }
        return job;
    }

    public WorkState getWork() {
        return work;
    }

    public boolean inCrewMode() {
        if (crewModeTick != tickCount) {
            crewModeTick = tickCount;
            BuildJob current = getJob();
            crewMode = current != null && current.size() > 1 && Crew.loaded(getServer(), current).size() > 1;
        }
        return crewMode;
    }

    public Set<UUID> activeCrew() {
        Set<UUID> active = new HashSet<>();
        if (job != null) {
            Crew.loaded(getServer(), job).forEach(member -> active.add(member.getUUID()));
        }
        active.add(getUUID());
        return active;
    }

    public ItemCounter countInventory() {
        return inventoryCounter();
    }

    public int freeSlots() {
        return (int) inventory.getItems().stream().filter(ItemStack::isEmpty).count();
    }

    public ItemCounter fetchableStock() {
        ItemCounter stock = new ItemCounter();
        Crew.supplyStock(level(), reachableChests(), new ArrayList<>()).forEach(stack -> stock.add(stack, stack.getCount()));
        dumpFallback().entries().forEach(entry -> stock.add(entry.getKey(), entry.getValue()));
        if (job != null) {
            for (Map.Entry<ItemStack, Long> entry : job.getCrewWork().reservedByOthers(getUUID()).entries()) {
                stock.remove(entry.getKey(), entry.getValue());
            }
        }
        return stock;
    }

    private ItemCounter supplyFetchable() {
        ItemCounter stock = new ItemCounter();
        Crew.supplyStock(level(), reachableChests(), new ArrayList<>()).forEach(stack -> stock.add(stack, stack.getCount()));
        if (job != null) {
            for (Map.Entry<ItemStack, Long> entry : job.getCrewWork().reservedByOthers(getUUID()).entries()) {
                stock.remove(entry.getKey(), entry.getValue());
            }
        }
        return stock;
    }

    private ItemCounter dumpStock() {
        ItemCounter stock = new ItemCounter();
        for (AssignedChest chest : reachableChests()) {
            if (chest.role() != AssignedChest.Role.DUMP || !level().isLoaded(chest.pos())) {
                continue;
            }
            ItemStore store = ItemStores.at(level(), chest.pos());
            if (store == null) {
                continue;
            }
            for (int slot = 0; slot < store.size(); slot++) {
                ItemStack stack = store.get(slot);
                if (!stack.isEmpty() && !isTool(stack)) {
                    stock.add(stack, stack.getCount());
                }
            }
        }
        return stock;
    }

    private ItemCounter dumpFallback() {
        ItemCounter fallback = new ItemCounter();
        if (job == null || !level().dimension().equals(job.getDimension())) {
            return fallback;
        }
        ItemCounter inDump = dumpStock();
        if (inDump.isEmpty()) {
            return fallback;
        }
        Set<Item> kinds = new LinkedHashSet<>();
        inDump.entries().forEach(entry -> kinds.add(entry.getKey().getItem()));
        ItemCounter needed = job.remainingNeeds((ServerLevel) level(), kinds, this::withinRedoLimit);
        if (needed.isEmpty()) {
            return fallback;
        }
        ItemCounter covered = new ItemCounter();
        Crew.supplyStock(level(), reachableChests(), new ArrayList<>()).forEach(stack -> covered.add(stack, stack.getCount()));
        List<BuilderEntity> members = new ArrayList<>(Crew.loaded(getServer(), job));
        if (!members.contains(this)) {
            members.add(this);
        }
        for (BuilderEntity member : members) {
            member.inventory.getItems().forEach(stack -> covered.add(stack, stack.getCount()));
            member.overflow.forEach(stack -> covered.add(stack, stack.getCount()));
        }
        for (Map.Entry<ItemStack, Long> entry : needed.entries()) {
            long shortfall = entry.getValue() - covered.count(entry.getKey());
            if (shortfall > 0) {
                fallback.add(entry.getKey(), Math.min(shortfall, inDump.count(entry.getKey())));
            }
        }
        return fallback;
    }

    private boolean queueFetches(ItemCounter take) {
        ItemCounter supply = supplyFetchable();
        ItemCounter fromSupply = new ItemCounter();
        ItemCounter fromDump = new ItemCounter();
        for (Map.Entry<ItemStack, Long> entry : take.entries()) {
            long inSupply = Math.min(entry.getValue(), supply.count(entry.getKey()));
            fromSupply.add(entry.getKey(), inSupply);
            fromDump.add(entry.getKey(), entry.getValue() - inSupply);
        }
        boolean queued = false;
        for (AssignedChest chest : reachableChests()) {
            boolean dump = chest.role() == AssignedChest.Role.DUMP;
            ItemCounter amounts = dump ? fromDump : fromSupply;
            if (amounts.isEmpty() || !level().isLoaded(chest.pos())) {
                continue;
            }
            ItemStore store = ItemStores.at(level(), chest.pos());
            if (store != null && containsMatching(store, stack -> amounts.count(stack) > 0)) {
                queueVisit(dump ? ChestVisit.fetchFromDump(chest.pos(), amounts) : ChestVisit.fetchAmounts(chest.pos(), amounts));
                queued = true;
            }
        }
        return queued;
    }

    public ItemCounter heldByOtherMembers() {
        ItemCounter held = new ItemCounter();
        if (job == null) {
            return held;
        }
        for (BuilderEntity member : Crew.loaded(getServer(), job)) {
            if (member != this) {
                member.inventory.getItems().forEach(stack -> held.add(stack, stack.getCount()));
            }
        }
        for (Map.Entry<ItemStack, Long> entry : job.getCrewWork().reservedByOthers(getUUID()).entries()) {
            held.add(entry.getKey(), entry.getValue());
        }
        return held;
    }

    public boolean supplyHas(Predicate<ItemStack> wanted) {
        for (AssignedChest chest : reachableChests()) {
            if (chest.role() != AssignedChest.Role.SUPPLY || !level().isLoaded(chest.pos())) {
                continue;
            }
            ItemStore store = ItemStores.at(level(), chest.pos());
            if (store != null && containsMatching(store, wanted)) {
                return true;
            }
        }
        return false;
    }

    public boolean crewHolds(Predicate<ItemStack> wanted) {
        if (job == null) {
            return false;
        }
        for (BuilderEntity member : Crew.loaded(getServer(), job)) {
            if (member != this && member.findSlot(wanted) >= 0) {
                return true;
            }
        }
        return false;
    }

    public boolean askToStepAside(AABB box) {
        List<Entity> blocking = level().getEntities((Entity) null, box, entity -> !entity.isSpectator() && entity.blocksBuilding);
        if (blocking.isEmpty() || job == null) {
            return false;
        }
        for (Entity entity : blocking) {
            if (!(entity instanceof JourneymanEntity other) || other == this) {
                return false;
            }
        }
        if (tickCount - askedAsideTick >= ASK_ASIDE_TICKS) {
            askedAsideTick = tickCount;
            for (Entity entity : blocking) {
                ((JourneymanEntity) entity).stepAside(box.getCenter());
            }
        }
        return true;
    }

    @Override
    public void stepAside(Vec3 from) {
        stepAsideUntil = tickCount + STEP_ASIDE_TICKS;
        Vec3 away = DefaultRandomPos.getPosAway(this, 4, 2, from);
        if (away == null || !getNavigation().moveTo(away.x, away.y, away.z, 1.0)) {
            leaveSite();
        }
    }

    private void leaveSite() {
        if (job == null) {
            return;
        }
        BoundingBox area = job.getArea();
        BlockPos pos = blockPosition();
        if (pos.getX() < area.minX() || pos.getX() > area.maxX() || pos.getZ() < area.minZ() || pos.getZ() > area.maxZ()) {
            return;
        }
        StuckRecovery.Goal goal = siteGoal(job, true);
        if (goal != null) {
            walkTowards(Vec3.atBottomCenterOf(goal.target()));
        }
    }

    private void waitForCrew(boolean handBack) {
        idle = true;
        crewWaitUntil = tickCount + CREW_WAIT_TICKS;
        if (idleSince < 0) {
            idleSince = tickCount;
        }
        if (tickCount - idleSince >= LEAVE_SITE_TICKS && getNavigation().isDone()) {
            leaveSite();
        }
        if (handBack && tickCount - idleSince >= HAND_BACK_TICKS && tickCount >= storeBlockedUntil && hasSpareItems(new ItemCounter())) {
            idleSince = tickCount;
            queueUnload(new ItemCounter());
        }
    }

    private void releaseClaims() {
        if (clearing != null) {
            clearing.releaseClaims();
        }
        if (placing != null) {
            placing.releaseClaims();
        }
        if (job != null) {
            job.getCrewWork().release(getUUID());
        }
    }

    public void beginJob(BuildJob newJob) {
        if (finishing != null) {
            finishing.stop();
            finishing = null;
        }
        job = newJob;
        pendingWork = false;
        waitingForForeman = false;
        work.setPhase(BuildJob.Phase.WALKING);
        stopIdleOrders();
        bestSiteDistance = Double.MAX_VALUE;
        ticksWithoutProgress = 0;
        getNavigation().stop();
        toolsFetched = false;
        clearedSomething = false;
        Constants.LOG.debug("{} starts {}", getJourneymanName(), newJob.getFile());
        if (newJob.getSchematic() == null) {
            work.setPhase(BuildJob.Phase.LOADING);
        } else if (newJob.getPlan() != null && level().dimension().equals(newJob.getDimension())) {
            prepareForSite(newJob.getPlan());
        }
    }

    private void prepareForSite(BuildPlan plan) {
        if (!plan.getToClear().isEmpty()) {
            CrewWork shared = job.getCrewWork();
            if (shared.getClearing() == null && shared.getPlacing() == null && !shared.isClearingDone()) {
                shared.setClearing(new ClearingWork((ServerLevel) level(), plan.getToClear(), plan.getFluidsInTheWay(), plan.targetsByPosition()));
            }
            queueToolRestock(null);
        } else if (plan.getFluidsInTheWay().isEmpty()) {
            CrewWork shared = job.getCrewWork();
            if (shared.getPlacing() == null) {
                shared.setPlacing(new PlacingWork((ServerLevel) level(), plan, this::withinRedoLimit, PlacingTask.rampStart(this)));
            }
            placing = new PlacingTask(this, job, shared.getPlacing());
            if (!inCrewMode()) {
                fetchMaterials(null);
            }
        }
    }

    private void queueToolRestock(@Nullable Predicate<ItemStack> mustHave) {
        toolsFetched = true;
        ClearingWork clearingWork = job == null ? null : job.getCrewWork().getClearing();
        if (clearingWork == null) {
            return;
        }
        Predicate<ItemStack> useful = mustHave == null ? stack -> ClearingTask.isUsefulTool(stack, clearingWork)
                : stack -> mustHave.test(stack) || ClearingTask.isUsefulTool(stack, clearingWork);
        boolean queued = false;
        for (AssignedChest chest : reachableChests()) {
            if (chest.role() != AssignedChest.Role.SUPPLY || !level().isLoaded(chest.pos())) {
                continue;
            }
            ItemStore store = ItemStores.at(level(), chest.pos());
            if (store != null && containsMatching(store, useful)) {
                queueVisit(ChestVisit.restockTools(chest.pos(), mustHave));
                queued = true;
            }
        }
        ItemStack excess = tickCount >= storeBlockedUntil ? firstExcessTool() : ItemStack.EMPTY;
        if (!excess.isEmpty() && visits.stream().noneMatch(visit -> visit.getKind() == ChestVisit.Kind.RESTOCK && hasRoomAt(visit.getChest(), excess))) {
            BlockPos room = supplyChestWithRoom(List.of(excess), null);
            if (room != null) {
                queueVisit(ChestVisit.restockTools(room, mustHave));
                queued = true;
            }
        }
        AssignedChest first = firstSupplyChest();
        if (!queued && first != null) {
            queueVisit(ChestVisit.restockTools(first.pos(), mustHave));
        }
    }

    private ItemStack firstExcessTool() {
        ToolPlan plan = toolPlan();
        if (plan == null) {
            return ItemStack.EMPTY;
        }
        List<Integer> excess = plan.excessSlots(inventory);
        return excess.isEmpty() ? ItemStack.EMPTY : inventory.getItem(excess.get(0));
    }

    private boolean hasRoomAt(BlockPos chest, ItemStack stack) {
        ItemStore store = level().isLoaded(chest) ? ItemStores.at(level(), chest) : null;
        return store != null && hasRoom(store, stack);
    }

    private static boolean hasRoom(ItemStore store, ItemStack stack) {
        ItemStack one = stack.copyWithCount(1);
        for (int slot = 0; slot < store.size(); slot++) {
            if (store.insert(slot, one, true).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    @Nullable
    private BlockPos supplyChestWithRoom(List<ItemStack> stacks, @Nullable BlockPos except) {
        for (AssignedChest chest : reachableChests()) {
            if (chest.role() != AssignedChest.Role.SUPPLY || chest.pos().equals(except) || !level().isLoaded(chest.pos())) {
                continue;
            }
            ItemStore store = ItemStores.at(level(), chest.pos());
            if (store != null && stacks.stream().anyMatch(stack -> hasRoom(store, stack))) {
                return chest.pos();
            }
        }
        return null;
    }

    @Nullable
    private ToolPlan toolPlan() {
        ClearingWork clearingWork = job == null ? null : job.getCrewWork().getClearing();
        if (clearingWork == null || job.getCrewWork().isClearingDone()) {
            return null;
        }
        List<ItemStack> crewTools = new ArrayList<>();
        int members = 1;
        if (inCrewMode()) {
            members = activeCrew().size();
            for (BuilderEntity member : Crew.loaded(getServer(), job)) {
                if (member != this) {
                    member.inventory.getItems().stream().filter(stack -> !stack.isEmpty() && isTool(stack)).forEach(crewTools::add);
                }
            }
        }
        List<ItemStack> supply = Crew.supplyStock(level(), reachableChests(), new ArrayList<>());
        ItemCounter staying = tickCount < storeBlockedUntil || firstSupplyChest() == null ? supplyShortfall() : new ItemCounter();
        Predicate<ItemStack> stays = stack -> isTool(stack) || staying.remove(stack, stack.getCount()) > 0;
        return ToolPlan.compute((ServerLevel) level(), clearingWork, inventory, stays, supply, crewTools, members, job.getOptions().allowBreakingEnchanted());
    }

    public boolean restockWorthVisiting(ItemStore store, Predicate<ItemStack> mustHave) {
        boolean missing = mustHave != ChestVisit.NOTHING && findSlot(mustHave) < 0;
        if (missing && containsMatching(store, mustHave)) {
            return true;
        }
        ToolPlan plan = toolPlan();
        if (plan == null) {
            return false;
        }
        List<Integer> excessSlots = tickCount >= storeBlockedUntil ? plan.excessSlots(inventory) : List.of();
        boolean excess = !excessSlots.isEmpty() && hasRoom(store, inventory.getItem(excessSlots.get(0)));
        if (excess || !plan.slotsToTake(store).isEmpty()) {
            return true;
        }
        if (!plan.hasDeficit() && !missing) {
            ChestVisit current = visits.peek();
            visits.removeIf(visit -> visit != current && visit.getKind() == ChestVisit.Kind.RESTOCK);
        }
        return false;
    }

    public void restockToolsAt(ItemStore store, BlockPos chest, Predicate<ItemStack> mustHave) {
        ToolPlan plan = toolPlan();
        int returned = 0;
        int taken = 0;
        if (plan != null) {
            if (tickCount >= storeBlockedUntil) {
                List<Integer> excess = plan.excessSlots(inventory);
                ItemStack left = ItemStack.EMPTY;
                for (int slot : excess) {
                    ItemStack stack = inventory.getItem(slot);
                    if (store.insert(stack.copy()).isEmpty()) {
                        plan.returned(stack);
                        inventory.setItem(slot, ItemStack.EMPTY);
                        returned++;
                    } else if (left.isEmpty()) {
                        left = stack;
                    }
                }
                if (!left.isEmpty()) {
                    ItemStack unreturned = left;
                    ChestVisit current = visits.peek();
                    boolean later = visits.stream().anyMatch(visit -> visit != current && visit.getKind() == ChestVisit.Kind.RESTOCK
                            && hasRoomAt(visit.getChest(), unreturned));
                    BlockPos other = later ? null : supplyChestWithRoom(List.of(unreturned), chest);
                    if (other != null) {
                        queueVisit(ChestVisit.restockTools(other, ChestVisit.NOTHING));
                    } else if (!later) {
                        storeBlockedUntil = tickCount + STORE_RETRY_TICKS;
                    }
                }
            }
            for (int slot : plan.slotsToTake(store)) {
                if (!hasFreeSlot()) {
                    break;
                }
                ItemStack stack = store.take(slot, store.get(slot).getCount());
                plan.took(stack);
                putBack(store, slot, inventory.addItem(stack));
                taken++;
            }
        }
        if (mustHave != ChestVisit.NOTHING && findSlot(mustHave) < 0) {
            for (int slot = 0; slot < store.size(); slot++) {
                ItemStack stack = store.get(slot);
                if (!stack.isEmpty() && mustHave.test(stack) && hasFreeSlot()) {
                    putBack(store, slot, inventory.addItem(store.take(slot, stack.getCount())));
                    taken++;
                    break;
                }
            }
        }
        inventory.setChanged();
        if (plan == null && taken > 0) {
            Constants.trace("[tools] {}.{} at {} takes {} tools", getOwnerName(), getJourneymanName(), chest.toShortString(), taken);
        }
        if (plan != null) {
            List<String> kinds = new ArrayList<>();
            for (ToolPlan.Kind kind : plan.kinds()) {
                kinds.add(kind.example().getHoverName().getString() + " " + kind.carried() + "/" + kind.target() + " for " + kind.blocks()
                        + (plan.members() > 1 ? "/" + plan.members() : "") + " blocks");
            }
            Constants.trace("[tools] {}.{} at {} takes {} and returns {} tools: {}; {} slots, {} tools carried", getOwnerName(),
                    getJourneymanName(), chest.toShortString(), taken, returned, kinds.isEmpty() ? "no tools needed" : String.join(", ", kinds),
                    plan.capacity(), inventory.getItems().stream().filter(stack -> !stack.isEmpty() && isTool(stack)).count());
        }
    }

    public boolean wantsSpeedTool(BlockState state, BlockPos pos) {
        if (job == null || clearing == null) {
            return false;
        }
        ItemStack best = ItemStack.EMPTY;
        BlockPos bestChest = null;
        float bestSpeed = 1.0F;
        boolean allowBreakingEnchanted = job.getOptions().allowBreakingEnchanted();
        for (AssignedChest chest : reachableChests()) {
            if (chest.role() != AssignedChest.Role.SUPPLY || !level().isLoaded(chest.pos())) {
                continue;
            }
            ItemStore store = ItemStores.at(level(), chest.pos());
            if (store == null) {
                continue;
            }
            for (int slot = 0; slot < store.size(); slot++) {
                ItemStack stack = store.get(slot);
                if (stack.isEmpty() || stack.get(DataComponents.TOOL) == null
                        || ToolCheck.usableDurability(stack, allowBreakingEnchanted) < Math.max(1, ToolCheck.damagePerBlock(stack, state, (ServerLevel) level(), pos))) {
                    continue;
                }
                float speed = stack.getDestroySpeed(state);
                if (speed > bestSpeed) {
                    best = stack;
                    bestChest = chest.pos();
                    bestSpeed = speed;
                }
            }
        }
        if (bestChest == null) {
            return false;
        }
        int members = inCrewMode() ? activeCrew().size() : 1;
        double saved = ToolPlan.savedTicks(this, best, clearing.getWork(), (ServerLevel) level(), members);
        double trip = 2.0 * Math.sqrt(distanceToSqr(Vec3.atCenterOf(bestChest))) / WALK_BLOCKS_PER_TICK + CHEST_TICKS;
        boolean worth = saved > 2.0 * trip;
        if (Services.PLATFORM.isDevelopmentEnvironment()) {
            Constants.LOG.info("[tools] {}.{} has no tool for {}: {} saves about {} ticks, a trip takes about {} ticks, so it {}", getOwnerName(),
                    getJourneymanName(), state.getBlock().getName().getString(), best.getHoverName().getString(), Math.round(saved), Math.round(trip),
                    worth ? "fetches more" : "digs by hand");
        }
        return worth;
    }

    private void queueVisit(ChestVisit visit) {
        Constants.LOG.debug("{} goes to the chest at {} to {}", getJourneymanName(), visit.getChest().toShortString(),
                visit.getKind() == ChestVisit.Kind.DUMP ? "dump items" : "fetch items");
        visits.add(visit);
    }

    private void endWholeJob(BuildJob current) {
        ForemanEntity foreman = foreman();
        if (foreman != null) {
            foreman.endJob(current);
            return;
        }
        MinecraftServer server = level().getServer();
        for (BuilderEntity member : Crew.loaded(server, current)) {
            member.leaveWork();
        }
        if (server != null) {
            JourneymanRegistry.get(server).removeJob(current.getId());
        }
    }

    public void leaveWork() {
        if (clearing != null && clearing.isBreaking()) {
            finishing = clearing;
            clearing = null;
        }
        detachJob();
    }

    private void detachJob() {
        releaseClaims();
        stopWork();
        job = null;
        pendingWork = false;
        waitingForForeman = false;
        elsewhere = false;
        work.reset();
        absorbOverflow();
        inventory.setChanged();
    }

    private void suspendWork() {
        if (clearing != null && clearing.isBreaking()) {
            finishing = clearing;
            clearing = null;
        }
        releaseClaims();
        stopWork();
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
        resetDumpTries();
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
                if (ItemMatch.same(stack, wanted)) {
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

    public void fetchAmounts(ItemStore store, BlockPos chest, ItemCounter amounts, boolean fromDump) {
        ItemCounter fetched = new ItemCounter();
        ItemCounter limit = fromDump ? dumpFallback() : null;
        int keepEmpty = 0;
        for (Map.Entry<ItemStack, Long> entry : amounts.entries()) {
            for (int slot = 0; slot < store.size() && amounts.count(entry.getKey()) > 0; slot++) {
                ItemStack stack = store.get(slot);
                if (!ItemMatch.same(stack, entry.getKey())) {
                    continue;
                }
                int count = (int) Math.min(Math.min(amounts.count(stack), stack.getCount()), roomFor(stack, keepEmpty));
                if (limit != null) {
                    count = (int) Math.min(count, limit.count(stack));
                }
                if (count <= 0) {
                    continue;
                }
                ItemStack taken = store.take(slot, count);
                ItemStack remainder = inventory.addItem(taken.copy());
                amounts.remove(taken, taken.getCount() - remainder.getCount());
                fetched.add(taken, taken.getCount() - remainder.getCount());
                if (limit != null) {
                    limit.remove(taken, taken.getCount() - remainder.getCount());
                }
                if (job != null) {
                    job.getCrewWork().reservationOf(getUUID()).remove(taken, taken.getCount() - remainder.getCount());
                }
                putBack(store, slot, remainder);
            }
            long left = amounts.count(entry.getKey());
            if (left > 0) {
                int max = Math.max(1, Math.min(entry.getKey().getMaxStackSize(), inventory.getMaxStackSize()));
                keepEmpty += (int) ((Math.max(0, left - roomFor(entry.getKey(), Integer.MAX_VALUE)) + max - 1) / max);
            }
        }
        inventory.setChanged();
        logFetch(fromDump, chest, fetched);
    }

    private int roomFor(ItemStack stack) {
        return roomFor(stack, 0);
    }

    private int roomFor(ItemStack stack, int keepEmpty) {
        int max = Math.min(stack.getMaxStackSize(), inventory.getMaxStackSize());
        int room = 0;
        int empty = 0;
        for (ItemStack existing : inventory.getItems()) {
            if (existing.isEmpty()) {
                empty++;
            } else if (ItemStack.isSameItemSameComponents(existing, stack)) {
                room += Math.max(0, max - existing.getCount());
            }
        }
        return room + Math.max(0, empty - keepEmpty) * max;
    }

    private void putBack(ItemStore store, int slot, ItemStack remainder) {
        if (remainder.isEmpty()) {
            return;
        }
        remainder = store.insert(slot, remainder, false);
        if (!remainder.isEmpty()) {
            remainder = store.insert(remainder);
        }
        if (!remainder.isEmpty()) {
            overflow.add(remainder);
        }
    }

    public ServerPlayer actor() {
        ServerLevel serverLevel = (ServerLevel) level();
        ServerPlayer actor = OwnerRights.actor(serverLevel, getOwner(), getOwnerName());
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

    public void dumpInto(ItemStore store, BlockPos chest) {
        ItemCounter keep = itemsToKeep();
        ItemCounter dumped = new ItemCounter();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && !isTool(stack)) {
                moveInto(store, stack, stack.getCount() - (int) keep.remove(stack, stack.getCount()), dumped);
            }
        }
        for (ItemStack stack : overflow) {
            moveInto(store, stack, isTool(stack) ? stack.getCount() : stack.getCount() - (int) keep.remove(stack, stack.getCount()), dumped);
        }
        overflow.removeIf(ItemStack::isEmpty);
        absorbOverflow();
        inventory.setChanged();
        logUnload("dumps", chest, dumped);
    }

    private static void moveInto(ItemStore store, ItemStack stack, int count, ItemCounter moved) {
        if (count <= 0) {
            return;
        }
        ItemStack example = stack.copyWithCount(1);
        ItemStack remainder = store.insert(stack.split(count));
        moved.add(example, count - remainder.getCount());
        stack.grow(remainder.getCount());
    }

    private void absorbOverflow() {
        for (int i = 0; i < overflow.size(); i++) {
            overflow.set(i, inventory.addItem(overflow.get(i)));
        }
        overflow.removeIf(ItemStack::isEmpty);
    }

    private void logUnload(String verb, BlockPos chest, ItemCounter moved) {
        Constants.trace("[unload] {}.{} {} {} items at {}: {}", getOwnerName(), getJourneymanName(), verb, total(moved), chest.toShortString(),
                describe(moved));
    }

    private void logFetch(boolean fromDump, BlockPos chest, ItemCounter fetched) {
        Constants.trace("[fetch] {}.{} takes {} items from the {} chest at {}: {}", getOwnerName(), getJourneymanName(), total(fetched),
                fromDump ? "dump" : "supply", chest.toShortString(), describe(fetched));
    }

    private static long total(ItemCounter items) {
        return items.entries().stream().mapToLong(Map.Entry::getValue).sum();
    }

    private static String describe(ItemCounter items) {
        List<String> kinds = new ArrayList<>();
        for (Map.Entry<ItemStack, Long> entry : items.sorted()) {
            kinds.add(ItemMatch.name(entry.getKey()).getString() + " " + entry.getValue());
        }
        return kinds.isEmpty() ? "nothing" : String.join(", ", kinds);
    }

    private boolean clearingRules() {
        BuildJob.Phase phase = work.getPhase() == BuildJob.Phase.PAUSED ? work.getResumePhase() : work.getPhase();
        return emptyingOut || phase == BuildJob.Phase.CLEARING;
    }

    private ItemCounter itemsToKeep() {
        return clearingRules() ? supplyShortfall() : jobNeeds();
    }

    private ItemCounter jobNeeds() {
        if (job == null) {
            return new ItemCounter();
        }
        BuildPlan plan = job.getPlan();
        if (plan != null && plan != needsPlan) {
            needsPlan = plan;
            needs = plan.requirements(registryAccess()).items();
        }
        ItemCounter result = plan == null ? new ItemCounter() : needs.copy();
        if (!level().dimension().equals(job.getDimension())) {
            return result;
        }
        Set<Item> carried = new LinkedHashSet<>();
        for (ItemStack stack : inventory.getItems()) {
            if (!stack.isEmpty() && !isTool(stack)) {
                carried.add(stack.getItem());
            }
        }
        for (ItemStack stack : overflow) {
            if (!isTool(stack)) {
                carried.add(stack.getItem());
            }
        }
        ItemCounter current = job.remainingNeeds((ServerLevel) level(), carried, this::withinRedoLimit);
        for (Map.Entry<ItemStack, Long> entry : current.entries()) {
            result.add(entry.getKey(), entry.getValue() - result.count(entry.getKey()));
        }
        return result;
    }

    private ItemCounter supplyShortfall() {
        ItemCounter needed = jobNeeds();
        if (needed.isEmpty()) {
            return needed;
        }
        ItemCounter covered = new ItemCounter();
        Crew.supplyStock(level(), reachableChests(), new ArrayList<>()).forEach(stack -> covered.add(stack, stack.getCount()));
        for (BuilderEntity member : Crew.loaded(getServer(), job)) {
            if (member != this) {
                member.inventory.getItems().forEach(stack -> covered.add(stack, stack.getCount()));
            }
        }
        ItemCounter shortfall = new ItemCounter();
        for (Map.Entry<ItemStack, Long> entry : needed.entries()) {
            shortfall.add(entry.getKey(), entry.getValue() - covered.count(entry.getKey()));
        }
        return shortfall;
    }

    private boolean hasDumpableItems() {
        ItemCounter keep = itemsToKeep();
        for (ItemStack stack : overflow) {
            if (isTool(stack) || keep.remove(stack, stack.getCount()) < stack.getCount()) {
                return true;
            }
        }
        for (ItemStack stack : inventory.getItems()) {
            if (!stack.isEmpty() && !isTool(stack) && keep.remove(stack, stack.getCount()) < stack.getCount()) {
                return true;
            }
        }
        return false;
    }

    public void fetchFrom(ItemStore store, BlockPos chest, Predicate<ItemStack> wanted, boolean oneEach, boolean fromDump) {
        ItemCounter fetched = new ItemCounter();
        for (int slot = 0; slot < store.size(); slot++) {
            ItemStack stack = store.get(slot);
            if (oneEach && !stack.isEmpty() && findSlot(carried -> carried.is(stack.getItem())) >= 0) {
                continue;
            }
            int count = stack.isEmpty() || !wanted.test(stack) ? 0 : Math.min(oneEach ? 1 : stack.getCount(), roomFor(stack));
            if (count > 0) {
                ItemStack taken = store.take(slot, count);
                ItemStack remainder = inventory.addItem(taken.copy());
                fetched.add(taken, taken.getCount() - remainder.getCount());
                putBack(store, slot, remainder);
            }
        }
        inventory.setChanged();
        logFetch(fromDump, chest, fetched);
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
        pauseJob(reason, true);
    }

    private void pauseJob(String reason, boolean tell) {
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
        if (inCrewMode()) {
            releaseClaims();
        }
        work.pause(reason);
        pausedTicks = 0;
        int reminderTicks = JourneymenConfig.REMINDER_INTERVAL_SECONDS.get() * 20;
        if (tell && (!reason.equals(lastNotice) || tickCount - lastNoticeTick >= reminderTicks)) {
            report(reason);
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
                    visitsDone++;
                    if (visit.getKind() == ChestVisit.Kind.DUMP && emptyingOut) {
                        queueEmptyingOut();
                    } else if (visit.getKind() == ChestVisit.Kind.DUMP && visits.isEmpty()) {
                        if (!overflow.isEmpty() || !hasFreeSlot()) {
                            startDump();
                        } else {
                            resetDumpTries();
                            if (!clearingRules() || !storeShortfall()) {
                                restockAfterUnload();
                            }
                        }
                    } else if (visit.getKind() == ChestVisit.Kind.DUMP) {
                        resetDumpTries();
                    } else if (visit.getKind() == ChestVisit.Kind.STORE && visits.isEmpty()) {
                        restockAfterUnload();
                    }
                }
                case FAILED -> {
                    visits.poll();
                    if (visit.getProblem() == ChestVisit.Problem.UNREACHABLE) {
                        job.getCrewWork().markUnreachable(visit.getChest(), level().getGameTime());
                    }
                    if (visit.getKind() == ChestVisit.Kind.DUMP && visit.getProblem() != null) {
                        dumpProblems.put(visit.getChest(), visit.getProblem());
                    }
                    if (visit.getKind() == ChestVisit.Kind.DUMP && emptyingOut) {
                        queueEmptyingOut();
                    } else if (visit.getKind() == ChestVisit.Kind.DUMP) {
                        startDump();
                    } else if (visit.getProblem() == ChestVisit.Problem.UNREACHABLE) {
                        report(visit.getFailure());
                    } else {
                        pauseJob(visit.getFailure() == null ? getJourneymanName() + " couldn't get to a supply chest." : visit.getFailure());
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
        if (tickCount < crewWaitUntil || tickCount < stepAsideUntil) {
            return;
        }
        CrewWork shared = job.getCrewWork();
        if (shared.isClearingDone()) {
            if (clearing != null) {
                clearedSomething |= clearing.getOwnCleared() > 0;
                clearing.stop();
            }
            leaveClearing();
            return;
        }
        if ((clearing == null || clearing.getWork() != shared.getClearing()) && !prepareClearing()) {
            return;
        }
        ClearingTask.Status status = clearing.tick();
        if (status != ClearingTask.Status.IDLE && status != ClearingTask.Status.NEEDS_TOOL) {
            idle = false;
            idleSince = -1;
        }
        switch (status) {
            case WORKING -> {
            }
            case NEEDS_DUMP -> startDump();
            case NEEDS_TOOL -> startFetch(clearing.getNeededTool(), clearing.getNeededToolDescription());
            case NEEDS_SPEED_TOOL -> speedToolTrip();
            case BLOCKED -> pauseJob(clearing.getBlockedReason());
            case IDLE -> waitForCrew(false);
            case DONE -> finishClearing();
        }
    }

    private boolean prepareClearing() {
        if (job.getSchematic() == null) {
            return false;
        }
        CrewWork shared = job.getCrewWork();
        if (shared.getClearing() == null) {
            BuildPlan plan = job.getPlan();
            if (plan == null) {
                if (!BuildPlan.isLoaded((ServerLevel) level(), job.getArea())) {
                    pauseJob("Part of the site of " + job.getFile() + " isn't loaded.");
                    return false;
                }
                plan = BuildPlan.create(jobLevel(), job.getSchematic(), job.transform(), job.getOptions());
                job.setPlan(plan);
            }
            shared.setClearing(new ClearingWork((ServerLevel) level(), plan.getToClear(), plan.getFluidsInTheWay(), plan.targetsByPosition()));
        }
        if (clearing != null) {
            clearedSomething |= clearing.getOwnCleared() > 0;
            clearing.stop();
        }
        clearing = new ClearingTask(this, job, shared.getClearing());
        if (!toolsFetched) {
            queueToolRestock(null);
        }
        return true;
    }

    private void finishClearing() {
        clearedSomething |= clearing.getOwnCleared() > 0;
        ClearingWork done = clearing.getWork();
        clearing = null;
        CrewWork shared = job.getCrewWork();
        if (shared.getClearing() == done) {
            List<BlockPos> skipped = done.getSkipped();
            shared.noteCleared(done.getCleared() > 0);
            BuildPlan plan = BuildPlan.create(jobLevel(), job.getSchematic(), job.transform(), job.getOptions());
            job.setPlan(plan);
            List<BlockPos> leftover = new ArrayList<>();
            for (BlockPos pos : plan.getToClear()) {
                if (job.getRedoCounts().merge(pos, 1, Integer::sum) <= JourneymenConfig.REDO_LIMIT.get()) {
                    leftover.add(pos);
                } else if (!job.getUnclearable().contains(pos)) {
                    job.getUnclearable().add(pos);
                }
            }
            if (!leftover.isEmpty() || !plan.getFluidsInTheWay().isEmpty()) {
                shared.setClearing(new ClearingWork((ServerLevel) level(), leftover, plan.getFluidsInTheWay(), plan.targetsByPosition()));
                clearing = new ClearingTask(this, job, shared.getClearing());
                return;
            }
            showInHand(ItemStack.EMPTY);
            if (!job.getUnclearable().isEmpty() || !skipped.isEmpty()) {
                BlockPos example = !job.getUnclearable().isEmpty() ? job.getUnclearable().get(0) : skipped.get(0);
                announce("I couldn't clear " + Math.max(job.getUnclearable().size(), skipped.size()) + " block(s) of the site, e.g. at "
                        + example.toShortString() + ".");
            }
            boolean anyCleared = shared.takeClearedAny();
            shared.finishClearing();
            if (anyCleared) {
                announce("The site of " + job.getFile() + " is clear.");
            }
        }
        leaveClearing();
    }

    private void leaveClearing() {
        clearing = null;
        showInHand(ItemStack.EMPTY);
        boolean collected = clearedSomething;
        clearedSomething = false;
        if (!collected) {
            work.setPhase(BuildJob.Phase.PLACING);
            return;
        }
        emptyingOut = true;
        resetDumpTries();
        queueEmptyingOut();
    }

    private void queueEmptyingOut() {
        if (hasDumpableItems()) {
            BlockPos dump = nextDumpChest();
            if (dump != null) {
                queueVisit(ChestVisit.dump(dump));
                return;
            }
            if (!triedDumpChests.isEmpty()) {
                String problems = describeDumpProblems(" " + getJourneymanName() + " keeps the rest of what it cleared.");
                announce(problems != null ? problems : "My dump chests are full, so " + getJourneymanName() + " keeps the rest of what it cleared.");
            }
        }
        emptyingOut = false;
        resetDumpTries();
        work.setPhase(BuildJob.Phase.PLACING);
        storeShortfall();
    }

    private void tickPlacing() {
        if (handleVisits()) {
            return;
        }
        if (!overflow.isEmpty()) {
            startDump();
            return;
        }
        if (tickCount < crewWaitUntil || tickCount < stepAsideUntil) {
            return;
        }
        CrewWork shared = job.getCrewWork();
        if (!shared.isClearingDone() && shared.getClearing() != null) {
            if (placing != null) {
                placing.stop();
                placing = null;
            }
            work.setPhase(BuildJob.Phase.CLEARING);
            return;
        }
        if ((placing == null || placing.getWork() != shared.getPlacing()) && !preparePlacing()) {
            return;
        }
        PlacingTask.Status status = placing.tick();
        if (status != PlacingTask.Status.WAITING) {
            waitTicks = 0;
        }
        if (status != PlacingTask.Status.IDLE) {
            idle = false;
            idleSince = -1;
        }
        boolean crew = inCrewMode();
        if (crew && placing.takeNewBatch() && job.getCrewWork().othersStarving(getUUID()) && tickCount >= storeBlockedUntil
                && hasSpareItems(placing.batchNeeds())) {
            queueUnload(placing.batchNeeds());
        }
        switch (status) {
            case WORKING -> {
            }
            case NEEDS_ITEMS -> {
                if (crew) {
                    fetchCrewMaterials(placing.getNeededItems());
                } else {
                    fetchMaterials(placing.getNeededItems());
                }
            }
            case NEEDS_TOOL -> startFetch(placing.getNeededTool(), placing.getNeededToolDescription());
            case NEEDS_ROOM -> makeRoom(new ItemCounter());
            case MISSING -> pauseForMaterials();
            case BLOCKED -> pauseJob(placing.getBlockedReason());
            case IDLE -> waitForCrew(true);
            case WAITING -> {
                int reportTicks = JourneymenConfig.WAIT_REPORT_SECONDS.get() * 20;
                int reminderTicks = JourneymenConfig.REMINDER_INTERVAL_SECONDS.get() * 20;
                waitTicks++;
                if (waitTicks == reportTicks || waitTicks > reportTicks && (waitTicks - reportTicks) % reminderTicks == 0) {
                    BlockPos pos = placing.getWaitingAt();
                    report("Someone is standing at " + (pos == null ? getJourneymanName() + "'s next spot" : pos.toShortString())
                            + ", where " + getJourneymanName() + " needs to place a block. It's waiting until they move.");
                }
            }
            case DONE -> finishPlacing();
        }
    }

    private boolean preparePlacing() {
        if (job.getSchematic() == null) {
            return false;
        }
        CrewWork shared = job.getCrewWork();
        if (shared.getPlacing() == null) {
            BuildPlan plan = job.getPlan();
            if (plan == null) {
                if (!BuildPlan.isLoaded((ServerLevel) level(), job.getArea())) {
                    pauseJob("Part of the site of " + job.getFile() + " isn't loaded.");
                    return false;
                }
                plan = BuildPlan.create(jobLevel(), job.getSchematic(), job.transform(), job.getOptions());
                job.setPlan(plan);
            }
            shared.setPlacing(new PlacingWork((ServerLevel) level(), plan, this::withinRedoLimit, PlacingTask.rampStart(this)));
        }
        if (placing != null) {
            placing.stop();
        }
        placing = new PlacingTask(this, job, shared.getPlacing());
        return true;
    }

    private void fetchCrewMaterials(@Nullable List<ItemStack> immediate) {
        ItemCounter inInventory = inventoryCounter();
        ItemCounter needs = placing.batchNeeds();
        ItemCounter wanted = missingFrom(needs, inInventory);
        ItemCounter fetchable = fetchableStock();
        ItemCounter take = new ItemCounter();
        for (Map.Entry<ItemStack, Long> entry : wanted.entries()) {
            take.add(entry.getKey(), Math.min(entry.getValue(), fetchable.count(entry.getKey())));
        }
        if (immediate != null) {
            ItemCounter held = heldByOtherMembers();
            for (ItemStack stack : immediate) {
                if (inInventory.count(stack) + take.count(stack) >= stack.getCount()) {
                    continue;
                }
                if (inInventory.count(stack) + fetchable.count(stack) + held.count(stack) >= stack.getCount()) {
                    placing.releaseClaims();
                    waitForCrew(true);
                } else if (job.getOptions().skipMissing()) {
                    placing.skipCurrent();
                } else {
                    pauseForMaterials();
                }
                return;
            }
        }
        int freeSlots = freeSlots();
        if (take.slotsBeyond(inInventory) > freeSlots
                && (freeSlots == 0 || hasDumpableItems() || tickCount >= storeBlockedUntil && hasSpareItems(needs))) {
            makeRoom(needs);
            return;
        }
        job.getCrewWork().reserve(getUUID(), take.copy());
        ItemCounter carried = inInventory.copy();
        take.entries().forEach(entry -> carried.add(entry.getKey(), entry.getValue()));
        int members = activeCrew().size();
        ItemCounter upcoming = placing.upcomingUnclaimed(Math.max(0, freeSlots - take.slotsBeyond(inInventory)) * members, carried);
        for (Map.Entry<ItemStack, Long> entry : upcoming.entries()) {
            long share = (entry.getValue() + members - 1) / members;
            long room = fetchable.count(entry.getKey()) - take.count(entry.getKey());
            take.add(entry.getKey(), Math.min(share, room));
        }
        while (take.slotsBeyond(inInventory) > freeSlots && !take.isEmpty()) {
            Map.Entry<ItemStack, Long> last = take.entries().get(take.entries().size() - 1);
            take.remove(last.getKey(), Math.min(last.getValue(), last.getKey().getMaxStackSize()));
        }
        for (BlockRequirements.ToolUse use : placing.batchToolUses()) {
            Predicate<ItemStack> usable = PlacingTask.toolFor(use, job.getOptions().allowBreakingEnchanted());
            if (findSlot(usable) < 0) {
                for (AssignedChest chest : reachableChests()) {
                    if (chest.role() != AssignedChest.Role.SUPPLY || !level().isLoaded(chest.pos())) {
                        continue;
                    }
                    ItemStore store = ItemStores.at(level(), chest.pos());
                    if (store != null && containsMatching(store, usable)) {
                        queueVisit(ChestVisit.fetchOneEach(chest.pos(), usable));
                        break;
                    }
                }
            }
        }
        if (take.isEmpty()) {
            return;
        }
        queueFetches(take);
    }

    private void makeRoom(ItemCounter keep) {
        if (queueUnload(keep)) {
            return;
        }
        if (hasDumpableItems()) {
            startDump();
        } else {
            pauseJob(noRoomForMaterials(keep));
        }
    }

    private String noRoomForMaterials(ItemCounter keep) {
        if (hasSpareItems(keep)) {
            return getJourneymanName() + "'s inventory is full, so it can't carry the next materials, and "
                    + (firstSupplyChest() == null ? "I have no supply chest to take back what it doesn't need yet. Please give me one."
                    : "my supply chests have no room to take back what it doesn't need yet. Please make room in my supply chests.") + supplyChestProblems();
        }
        return getJourneymanName() + "'s inventory is full of things it still needs, so it can't carry the next materials. Please take out what it doesn't need."
                + supplyChestProblems();
    }

    private ServerLevel jobLevel() {
        ServerLevel level = getServer() == null ? null : getServer().getLevel(job.getDimension());
        return level != null ? level : (ServerLevel) level();
    }

    private boolean withinRedoLimit(BlockPos pos) {
        return job.getRedoCounts().getOrDefault(pos, 0) <= JourneymenConfig.REDO_LIMIT.get() && !job.getSkippedPositions().contains(pos);
    }

    private void fetchMaterials(@Nullable List<ItemStack> immediate) {
        ItemCounter inInventory = inventoryCounter();
        ItemCounter inSupply = new ItemCounter();
        List<Component> ignored = new ArrayList<>();
        collectStock(ignored).forEach(stack -> inSupply.add(stack, stack.getCount()));
        dumpFallback().entries().forEach(entry -> inSupply.add(entry.getKey(), entry.getValue()));
        if (immediate != null) {
            for (ItemStack stack : immediate) {
                if (inSupply.count(stack) < stack.getCount()) {
                    if (job.getOptions().skipMissing()) {
                        placing.skipCurrent();
                    } else {
                        pauseForMaterials();
                    }
                    return;
                }
            }
        }
        int freeSlots = freeSlots();
        int toolSlots = (int) inventory.getItems().stream().filter(stack -> !stack.isEmpty() && isTool(stack)).count();
        ItemCounter soon = placing.upcomingNeeds(INVENTORY_SIZE - toolSlots, new ItemCounter());
        boolean unloading = soon.slotsBeyond(inInventory) > freeSlots && queueUnload(soon);
        ItemCounter first = new ItemCounter();
        if (immediate != null) {
            immediate.forEach(stack -> first.add(stack, stack.getCount()));
        }
        if (!unloading && first.slotsBeyond(inInventory) > freeSlots) {
            if (hasDumpableItems()) {
                startDump();
            } else {
                pauseJob(noRoomForMaterials(soon));
            }
            return;
        }
        ItemCounter fetchable = fetchableStock();
        ItemCounter wanted = new ItemCounter();
        for (Map.Entry<ItemStack, Long> entry : missingFrom(unloading ? soon : placing.upcomingNeeds(freeSlots, inInventory), inInventory).entries()) {
            wanted.add(entry.getKey(), Math.min(entry.getValue(), fetchable.count(entry.getKey())));
        }
        for (BlockRequirements.ToolUse use : placing.remainingToolUses()) {
            Predicate<ItemStack> usable = PlacingTask.toolFor(use, job.getOptions().allowBreakingEnchanted());
            if (findSlot(usable) < 0) {
                for (AssignedChest chest : reachableChests()) {
                    if (chest.role() != AssignedChest.Role.SUPPLY || !level().isLoaded(chest.pos())) {
                        continue;
                    }
                    ItemStore store = ItemStores.at(level(), chest.pos());
                    if (store != null && containsMatching(store, usable)) {
                        queueVisit(ChestVisit.fetchOneEach(chest.pos(), usable));
                        break;
                    }
                }
            }
        }
        if (wanted.isEmpty()) {
            if (immediate != null && job.getOptions().skipMissing()) {
                placing.skipCurrent();
            } else if (immediate != null) {
                pauseForMaterials();
            }
            return;
        }
        boolean queued = queueFetches(wanted);
        if (!queued && !job.getOptions().skipMissing()) {
            pauseForMaterials();
        }
    }

    private static ItemCounter missingFrom(ItemCounter needs, ItemCounter available) {
        ItemCounter missing = new ItemCounter();
        for (Map.Entry<ItemStack, Long> entry : needs.entries()) {
            long count = entry.getValue() - available.count(entry.getKey());
            if (count > 0) {
                missing.add(entry.getKey(), count);
            }
        }
        return missing;
    }

    private boolean queueUnload(ItemCounter keep) {
        boolean queued = false;
        if (hasDumpableItems()) {
            for (AssignedChest chest : reachableChests()) {
                if (chest.role() == AssignedChest.Role.DUMP && level().isLoaded(chest.pos())) {
                    queueVisit(ChestVisit.dump(chest.pos()));
                    queued = true;
                    break;
                }
            }
        }
        if (tickCount >= storeBlockedUntil && hasSpareItems(keep)) {
            queued |= queueStore(keep);
        }
        return queued;
    }

    private boolean hasSpareItems(ItemCounter keep) {
        return !itemsToStore(keep).isEmpty();
    }

    private boolean storeShortfall() {
        return tickCount >= storeBlockedUntil && !itemsToStore(null).isEmpty() && queueStore(null);
    }

    private List<ItemStack> itemsToStore(@Nullable ItemCounter keep) {
        ItemCounter toStore = keep == null ? supplyShortfall() : null;
        ItemCounter remaining = keep == null ? null : keep.copy();
        List<ItemStack> result = new ArrayList<>();
        List<ItemStack> carried = new ArrayList<>(inventory.getItems());
        carried.addAll(overflow);
        for (ItemStack stack : carried) {
            if (stack.isEmpty() || isTool(stack)) {
                continue;
            }
            int count = toStore != null ? (int) toStore.remove(stack, stack.getCount()) : stack.getCount() - (int) remaining.remove(stack, stack.getCount());
            if (count > 0) {
                result.add(stack.copyWithCount(count));
            }
        }
        return result;
    }

    private boolean queueStore(@Nullable ItemCounter keep) {
        List<ItemStack> stacks = itemsToStore(keep);
        BlockPos chest = stacks.isEmpty() ? null : supplyChestWithRoom(stacks, null);
        if (chest != null) {
            queueVisit(ChestVisit.store(chest, keep));
        } else if (!stacks.isEmpty()) {
            storeBlockedUntil = tickCount + STORE_RETRY_TICKS;
        }
        return chest != null;
    }

    public boolean storeWorthVisiting(ItemStore store, @Nullable ItemCounter keep) {
        return itemsToStore(keep).stream().anyMatch(stack -> hasRoom(store, stack));
    }

    @Nullable
    private AssignedChest firstSupplyChest() {
        for (AssignedChest chest : reachableChests()) {
            if (chest.role() == AssignedChest.Role.SUPPLY && level().isLoaded(chest.pos()) && ItemStores.at(level(), chest.pos()) != null) {
                return chest;
            }
        }
        return null;
    }

    public void storeInto(ItemStore store, BlockPos chest, @Nullable ItemCounter keep) {
        ItemCounter toStore = keep == null ? supplyShortfall() : null;
        ItemCounter remaining = keep == null ? null : keep.copy();
        ItemCounter stored = new ItemCounter();
        List<ItemStack> carried = new ArrayList<>(inventory.getItems());
        carried.addAll(overflow);
        for (ItemStack stack : carried) {
            if (stack.isEmpty() || isTool(stack)) {
                continue;
            }
            moveInto(store, stack, toStore != null ? (int) toStore.remove(stack, stack.getCount())
                    : stack.getCount() - (int) remaining.remove(stack, stack.getCount()), stored);
        }
        overflow.removeIf(ItemStack::isEmpty);
        absorbOverflow();
        inventory.setChanged();
        logUnload("stores", chest, stored);
        List<ItemStack> left = itemsToStore(keep);
        BlockPos next = left.isEmpty() ? null : supplyChestWithRoom(left, chest);
        if (next != null) {
            queueVisit(ChestVisit.store(next, keep));
        }
    }

    private void pauseForMaterials() {
        ItemCounter available = new ItemCounter();
        List<Component> ignored = new ArrayList<>();
        ForemanEntity foreman = foreman();
        (foreman != null ? Crew.stock(level(), reachableChests(), Crew.loaded(getServer(), job), ignored) : collectStock(ignored))
                .forEach(stack -> available.add(stack, stack.getCount()));
        dumpStock().entries().forEach(entry -> available.add(entry.getKey(), entry.getValue()));
        List<String> missing = new ArrayList<>();
        for (Map.Entry<ItemStack, Long> entry : placing.remainingNeeds().sorted()) {
            long shortfall = entry.getValue() - available.count(entry.getKey());
            if (shortfall > 0) {
                missing.add(shortfall + " " + ItemMatch.name(entry.getKey()).getString());
            }
        }
        if (missing.isEmpty()) {
            missing.add("some materials");
        }
        String list = String.join(", ", missing.subList(0, Math.min(5, missing.size())))
                + (missing.size() > 5 ? " and " + (missing.size() - 5) + " more kinds of items" : "");
        boolean hasDumpChest = reachableChests().stream().anyMatch(chest -> chest.role() == AssignedChest.Role.DUMP && level().isLoaded(chest.pos())
                && ItemStores.at(level(), chest.pos()) != null);
        pauseJob("I'm missing " + list + " for " + job.getFile() + (hasDumpChest ? ", and my dump chests don't have them either" : "")
                + ". We'll continue once they're in a supply chest or a builder's inventory." + supplyChestProblems(),
                tickCount >= LOAD_GRACE_TICKS);
    }

    private void finishPlacing() {
        PlacingWork done = placing.getWork();
        placing.stop();
        placing = null;
        CrewWork shared = job.getCrewWork();
        if (shared.getPlacing() != done) {
            return;
        }
        job.getSkippedPositions().addAll(done.getSkipped());
        BuildPlan plan = BuildPlan.create(jobLevel(), job.getSchematic(), job.transform(), job.getOptions());
        job.setPlan(plan);
        Set<BlockPos> leftover = new LinkedHashSet<>(plan.getToClear());
        plan.getToPlace().forEach(placement -> leftover.add(placement.pos()));
        plan.getToFix().forEach(placement -> leftover.add(placement.pos()));
        plan.getToWaterlog().forEach(placement -> leftover.add(placement.pos()));
        leftover.addAll(plan.getFluidsInTheWay());
        leftover.removeAll(job.getSkippedPositions());
        boolean redo = false;
        for (BlockPos pos : leftover) {
            if (job.getRedoCounts().merge(pos, 1, Integer::sum) <= JourneymenConfig.REDO_LIMIT.get()) {
                redo = true;
            } else if (!job.getUnclearable().contains(pos)) {
                job.getUnclearable().add(pos);
            }
        }
        if (!redo) {
            finishJob();
            return;
        }
        List<BlockPos> toClear = plan.getToClear().stream().filter(this::withinRedoLimit).toList();
        if (!toClear.isEmpty() || !plan.getFluidsInTheWay().isEmpty()) {
            shared.setClearing(new ClearingWork((ServerLevel) level(), toClear, plan.getFluidsInTheWay(), plan.targetsByPosition()));
            clearing = new ClearingTask(this, job, shared.getClearing());
            work.setPhase(BuildJob.Phase.CLEARING);
            return;
        }
        shared.setPlacing(new PlacingWork((ServerLevel) level(), plan, this::withinRedoLimit, PlacingTask.rampStart(this)));
        placing = new PlacingTask(this, job, shared.getPlacing());
    }

    private void finishJob() {
        BuildJob done = job;
        ForemanEntity foreman = foreman();
        if (foreman != null) {
            foreman.finishJob(done);
            return;
        }
        announce("I finished building " + done.getFile() + ".");
        endWholeJob(done);
    }

    private void speedToolTrip() {
        if (freeSlots() < INVENTORY_SIZE / 2 && (!overflow.isEmpty() || hasDumpableItems())) {
            BlockPos dump = nextDumpChest();
            if (dump != null) {
                queueVisit(ChestVisit.dump(dump));
                return;
            }
            resetDumpTries();
        }
        if (freeSlots() < INVENTORY_SIZE / 2 && storeShortfall()) {
            return;
        }
        queueToolRestock(null);
    }

    private void restockAfterUnload() {
        if (work.getPhase() == BuildJob.Phase.CLEARING && clearing != null) {
            queueToolRestock(null);
        }
    }

    private void startDump() {
        absorbOverflow();
        if (!hasDumpableItems()) {
            boolean hasSupply = firstSupplyChest() != null;
            boolean canStore = hasSupply && tickCount >= storeBlockedUntil;
            ItemCounter keep = clearingRules() ? null : new ItemCounter();
            ItemStack excess = canStore && work.getPhase() == BuildJob.Phase.CLEARING ? firstExcessTool() : ItemStack.EMPTY;
            if (!excess.isEmpty() && supplyChestWithRoom(List.of(excess), null) != null) {
                queueToolRestock(null);
            } else if (!canStore || itemsToStore(keep).isEmpty() || !queueStore(keep)) {
                if (!excess.isEmpty()) {
                    storeBlockedUntil = tickCount + STORE_RETRY_TICKS;
                }
                pauseJob(fullOfNeededThings(hasSupply && tickCount < storeBlockedUntil));
            }
            return;
        }
        BlockPos dump = nextDumpChest();
        if (dump != null) {
            queueVisit(ChestVisit.dump(dump));
            return;
        }
        String problems = describeDumpProblems("");
        resetDumpTries();
        boolean hasDumpChest = jobChests().stream().anyMatch(chest -> chest.role() == AssignedChest.Role.DUMP);
        pauseJob(problems != null ? getJourneymanName() + "'s inventory is full. " + problems
                : hasDumpChest
                ? getJourneymanName() + "'s inventory is full and my dump chests are full too. Please empty them or give me another one."
                : getJourneymanName() + "'s inventory is full. Give me a dump chest with " + chestCommand() + ".");
    }

    private String fullOfNeededThings(boolean supplyFull) {
        long tools = inventory.getItems().stream().filter(stack -> !stack.isEmpty() && isTool(stack)).count();
        long materials = inventory.getItems().stream().filter(stack -> !stack.isEmpty() && !isTool(stack)).count();
        ToolPlan plan = work.getPhase() == BuildJob.Phase.CLEARING ? toolPlan() : null;
        int extra = plan == null ? 0 : plan.excessSlots(inventory).size();
        if (extra > 0) {
            return getJourneymanName() + "'s inventory is full: it carries " + tools + " tools, " + extra + " more than it needs, and "
                    + (firstSupplyChest() == null ? "I have no supply chest to take the extra ones back. Please give me one or take"
                    : "my supply chests have no room to take the extra ones back. Please make room in my supply chests or take")
                    + " some tools out of its inventory." + supplyChestProblems();
        }
        String things = materials == 0 ? "tools it needs" : tools == 0 ? "materials the job still needs" : "tools and materials it still needs";
        return getJourneymanName() + "'s inventory is full of " + things + " (" + tools + " tools), so it has no room for what it clears"
                + (supplyFull && materials > 0 ? " and my supply chests have no room to take any back. Please take out what it doesn't need"
                + " or make room in my supply chests." : ". Please take out what it doesn't need.") + supplyChestProblems();
    }

    @Nullable
    private BlockPos nextDumpChest() {
        for (AssignedChest chest : jobChests()) {
            if (chest.role() != AssignedChest.Role.DUMP || !triedDumpChests.add(chest.pos())) {
                continue;
            }
            if (!isUnreachable(chest.pos())) {
                return chest.pos();
            }
            dumpProblems.put(chest.pos(), ChestVisit.Problem.UNREACHABLE);
        }
        return null;
    }

    private void resetDumpTries() {
        triedDumpChests.clear();
        dumpProblems.clear();
    }

    @Nullable
    private String describeDumpProblems(String consequence) {
        if (dumpProblems.isEmpty()) {
            return null;
        }
        Map<ChestVisit.Problem, List<BlockPos>> failed = new EnumMap<>(ChestVisit.Problem.class);
        List<BlockPos> full = new ArrayList<>();
        for (BlockPos pos : triedDumpChests) {
            ChestVisit.Problem problem = dumpProblems.get(pos);
            if (problem == null) {
                full.add(pos);
            } else {
                failed.computeIfAbsent(problem, key -> new ArrayList<>()).add(pos);
            }
        }
        List<String> sentences = new ArrayList<>();
        failed.forEach((problem, positions) -> sentences.add(switch (problem) {
            case GONE -> dumpChests("My", positions) + (positions.size() == 1 ? " is gone." : " are gone.");
            case UNLOADED -> dumpChests("My", positions) + (positions.size() == 1 ? " isn't loaded." : " aren't loaded.");
            case UNREACHABLE -> dumpChests("My builders can't get to my", positions) + ".";
            case LOCKED -> dumpChests("My", positions) + (positions.size() == 1 ? " is locked." : " are locked.");
        }));
        if (!full.isEmpty()) {
            sentences.add(dumpChests("My", full) + (full.size() == 1 ? " is full." : " are full."));
        }
        String command = chestCommand();
        boolean several = failed.values().stream().mapToInt(List::size).sum() > 1;
        String fix = !full.isEmpty() || failed.size() > 1 ? "Give me another one with " + command + "."
                : switch (failed.keySet().iterator().next()) {
                    case GONE -> "Give me another one with " + command + ".";
                    case UNLOADED -> "Load " + (several ? "their" : "its") + " area or give me another one with " + command + ".";
                    case UNREACHABLE -> "Clear a way to " + (several ? "them" : "it") + " (they need to get within " + (int) ChestVisit.REACH
                            + " blocks) or give me another one with " + command + ".";
                    case LOCKED -> "Unlock " + (several ? "them" : "it") + " or give me another one with " + command + ".";
                };
        return String.join(" ", sentences) + consequence + " " + fix;
    }

    private String chestCommand() {
        ForemanEntity foreman = foreman();
        return "/journeymen foreman chest " + (foreman == null ? "<foreman>" : foreman.getJourneymanName()) + " add <pos> dump";
    }

    private static String dumpChests(String prefix, List<BlockPos> positions) {
        List<String> names = positions.stream().map(BlockPos::toShortString).toList();
        String list = names.size() == 1 ? names.get(0)
                : String.join("; ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
        return prefix + (names.size() == 1 ? " dump chest at " : " dump chests at ") + list;
    }

    private String supplyChestProblems() {
        StringBuilder problems = new StringBuilder();
        for (AssignedChest chest : jobChests()) {
            if (chest.role() != AssignedChest.Role.SUPPLY) {
                continue;
            }
            if (!level().isLoaded(chest.pos())) {
                problems.append(" My supply chest at ").append(chest.pos().toShortString()).append(" isn't loaded.");
            } else if (ItemStores.isLocked(level(), chest.pos())) {
                problems.append(" My supply chest at ").append(chest.pos().toShortString()).append(" is locked.");
            } else if (ItemStores.at(level(), chest.pos()) == null) {
                problems.append(" My supply chest at ").append(chest.pos().toShortString()).append(" is gone.");
            } else if (isUnreachable(chest.pos())) {
                problems.append(" My builders can't get to my supply chest at ").append(chest.pos().toShortString())
                        .append(": clear a way to it (they need to get within ").append((int) ChestVisit.REACH).append(" blocks of it) or move it.");
            }
        }
        return problems.toString();
    }

    private void startFetch(Predicate<ItemStack> wanted, String description) {
        boolean crew = inCrewMode();
        for (AssignedChest chest : reachableChests()) {
            if (chest.role() != AssignedChest.Role.SUPPLY || !level().isLoaded(chest.pos())) {
                continue;
            }
            ItemStore store = ItemStores.at(level(), chest.pos());
            if (store != null && containsMatching(store, wanted)) {
                if (work.getPhase() == BuildJob.Phase.CLEARING && clearing != null) {
                    queueToolRestock(wanted);
                } else {
                    queueVisit(ChestVisit.fetchOneEach(chest.pos(), wanted));
                }
                return;
            }
        }
        if (crew && crewHolds(wanted)) {
            releaseClaims();
            waitForCrew(false);
            return;
        }
        pauseJob("I need " + description + " for " + getJourneymanName() + "." + supplyChestProblems());
    }

    private static boolean containsMatching(ItemStore store, Predicate<ItemStack> wanted) {
        for (int slot = 0; slot < store.size(); slot++) {
            ItemStack stack = store.get(slot);
            if (!stack.isEmpty() && wanted.test(stack)) {
                return true;
            }
        }
        return false;
    }

    public record Progress(String text, float fraction, int done, int total) {
    }

    public Progress progress() {
        if (getJob() == null) {
            return new Progress(isFollowing() ? "following its owner" : getComeTarget() != null ? "walking to you" : "idle", 0.0F, 0, 0);
        }
        if (elsewhere) {
            return new Progress("waiting in another dimension", 0.0F, 0, 0);
        }
        if (waitingForForeman) {
            return new Progress("waiting for its foreman", 0.0F, 0, 0);
        }
        float fraction = 0.0F;
        String counts = "";
        int doneCount = 0;
        int allCount = 0;
        if (placing != null) {
            int done = placing.getPlaced();
            int all = done + placing.getRemaining();
            fraction = all == 0 ? 1.0F : (float) done / all;
            counts = " " + done + "/" + all;
            doneCount = done;
            allCount = all;
        } else if (clearing != null) {
            int done = clearing.getCleared();
            int all = done + clearing.getRemaining();
            fraction = all == 0 ? 1.0F : (float) done / all;
            counts = " " + done + "/" + all;
            doneCount = done;
            allCount = all;
        }
        String phase = switch (work.getPhase()) {
            case LOADING -> "loading";
            case WALKING -> "walking to the site";
            case CLEARING -> "clearing" + counts;
            case PLACING -> "placing" + counts;
            case PAUSED -> "paused";
        };
        if (waitTicks > 0 && work.getPhase() == BuildJob.Phase.PLACING) {
            phase = "waiting for someone to move";
        }
        if (idle && (work.getPhase() == BuildJob.Phase.CLEARING || work.getPhase() == BuildJob.Phase.PLACING)) {
            phase = "waiting for the rest of the crew";
        }
        return new Progress(phase, fraction, doneCount, allCount);
    }

    @Override
    protected void tickWork() {
        long now = level().getGameTime();
        if (job != null && lastWorkTick >= 0 && now - lastWorkTick > FROZEN_TICKS) {
            suspendWork();
        }
        lastWorkTick = now;
        if (job == null && !overflow.isEmpty() && tickCount % 20 == 0) {
            absorbOverflow();
            inventory.setChanged();
        }
        pickUpNearbyItems();
        if (finishing != null) {
            finishing.tick();
            if (!finishing.isBreaking()) {
                finishing.stop();
                finishing = null;
            }
            return;
        }
        tickJob();
    }

    @Override
    protected void tickPeriodic() {
        if (tickCount % 10 == 0) {
            BuilderScreenService.tick(this);
        }
        if (tickCount % 20 == 0) {
            ServerPlayer ownerPlayer = getOwnerPlayer();
            if (ownerPlayer != null) {
                LinkModeService.tick(ownerPlayer);
            }
        }
    }

    private void tickJob() {
        if (getJob() == null) {
            return;
        }
        MinecraftServer server = level().getServer();
        if (server != null && (!JourneymanRegistry.get(server).hasJob(job.getId()) || !job.hasMember(getUUID())
                || !job.getForeman().equals(foremanId))) {
            detachJob();
            return;
        }
        if (!level().dimension().equals(job.getDimension())) {
            if (!elsewhere) {
                elsewhere = true;
                suspendWork();
            }
            return;
        }
        ForemanEntity foreman = foreman();
        if (foreman == null || !foreman.level().dimension().equals(job.getDimension())) {
            if (!waitingForForeman) {
                waitingForForeman = true;
                suspendWork();
            }
            elsewhere = false;
            return;
        }
        if (waitingForForeman || elsewhere) {
            waitingForForeman = false;
            elsewhere = false;
            bestSiteDistance = Double.MAX_VALUE;
            ticksWithoutProgress = 0;
        }
        switch (work.getPhase()) {
            case LOADING -> {
                if (job.getSchematic() != null) {
                    work.setPhase(work.getResumePhase());
                }
            }
            case WALKING -> {
                if (!handleVisits()) {
                    walkToSite();
                }
            }
            case CLEARING -> tickClearing();
            case PLACING -> tickPlacing();
            case PAUSED -> {
                if (++pausedTicks >= PAUSE_RETRY_TICKS) {
                    pausedTicks = 0;
                    bestSiteDistance = Double.MAX_VALUE;
                    ticksWithoutProgress = 0;
                    work.setPhase(work.getResumePhase());
                }
            }
            default -> {
            }
        }
    }

    private void walkToSite() {
        BoundingBox area = job.getArea();
        double distance = PreCheck.horizontalDistance(blockPosition(), area);
        boolean stuckNearby = distance <= NEAR_SITE && ticksWithoutProgress > NEAR_SITE_STUCK_TICKS;
        if (distance <= Reach.HORIZONTAL || stuckNearby) {
            getNavigation().stop();
            BuildPlan plan = job.getPlan();
            boolean nothingToClear = plan != null && plan.getToClear().isEmpty() && plan.getFluidsInTheWay().isEmpty()
                    || job.getCrewWork().isClearingDone();
            work.setPhase(nothingToClear ? BuildJob.Phase.PLACING : BuildJob.Phase.CLEARING);
            return;
        }
        if (distance < bestSiteDistance - 0.5) {
            bestSiteDistance = distance;
            ticksWithoutProgress = 0;
        } else if (++ticksWithoutProgress > StuckRecovery.giveUpTicks(STUCK_TICKS)) {
            getNavigation().stop();
            work.pause("can't find a way to the site");
            report(getJourneymanName() + " can't find a way to the site of " + job.getFile() + ". It'll try again in a while.");
            return;
        }
        if (tickCount % 20 == 0 || getNavigation().isDone()) {
            double x = Math.max(area.minX(), Math.min(area.maxX(), getX()));
            double z = Math.max(area.minZ(), Math.min(area.maxZ(), getZ()));
            walkTowards(new Vec3(x + 0.5, getY(), z + 0.5));
        }
    }

    @Nullable
    @Override
    protected StuckRecovery.Goal workGoal() {
        if (job == null || waitingForForeman || elsewhere || work.getPhase() == BuildJob.Phase.PAUSED || work.getPhase() == BuildJob.Phase.LOADING) {
            return null;
        }
        ChestVisit visit = visits.peek();
        if (visit != null) {
            return visit.isOpen() ? null : chestGoal(visit.getChest());
        }
        if (work.getPhase() == BuildJob.Phase.WALKING) {
            return siteGoal(job, false);
        }
        Reach reach = work.getPhase() == BuildJob.Phase.CLEARING && clearing != null ? clearing.getReach()
                : work.getPhase() == BuildJob.Phase.PLACING && placing != null ? placing.getReach() : null;
        BlockPos stand = reach == null ? null : reach.walkingTo();
        return stand == null ? null : reachGoal(stand);
    }

    @Override
    protected int workDone() {
        return visitsDone + (clearing == null ? 0 : clearing.getOwnCleared()) + (placing == null ? 0 : placing.getOwnPlaced());
    }

    @Nullable
    @Override
    protected StuckRecovery.Goal unstickWorkGoal() {
        if (job == null || !level().dimension().equals(job.getDimension())) {
            return null;
        }
        ChestVisit visit = visits.peek();
        return visit != null ? chestGoal(visit.getChest()) : siteGoal(job, true);
    }

    @Override
    protected void afterEscape() {
        bestSiteDistance = Double.MAX_VALUE;
        ticksWithoutProgress = 0;
        if (clearing != null) {
            clearing.getReach().retry();
        }
        if (placing != null) {
            placing.getReach().retry();
        }
    }

    @Override
    public void hurtArmor(DamageSource source, float amount) {
        doHurtEquipment(source, amount, EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD);
    }

    @Override
    public void hurtHelmet(DamageSource source, float amount) {
        doHurtEquipment(source, amount, EquipmentSlot.HEAD);
    }

    @Override
    protected InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!level().isClientSide) {
            if (canBeCommandedBy(player) && player instanceof ServerPlayer serverPlayer) {
                LinkModeService.finish(serverPlayer, this);
                BuilderScreenService.open(serverPlayer, this, BuilderMenu.INVENTORY_TAB);
            } else {
                player.displayClientMessage(Component.literal(getJourneymanName() + " works for someone else."), true);
            }
        }
        return InteractionResult.sidedSuccess(level().isClientSide);
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
            Constants.trace("[pickup] {}.{} picks up {} {} at {}", getOwnerName(), getJourneymanName(), taken, ItemMatch.name(stack).getString(),
                    item.blockPosition().toShortString());
            if (remainder.isEmpty()) {
                item.discard();
            } else {
                item.setItem(remainder);
            }
        }
    }

    @Override
    public void remove(RemovalReason reason) {
        if (job != null) {
            releaseClaims();
        }
        super.remove(reason);
    }

    @Override
    protected void onGone(boolean died) {
        BuildJob current = getJob();
        if (current != null) {
            releaseClaims();
            MinecraftServer server = level().getServer();
            if (server != null) {
                JourneymanRegistry.get(server).leaveJob(current, getUUID());
            }
            stopWork();
            job = null;
        }
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
        if (foremanId != null) {
            tag.putUUID("Foreman", foremanId);
        }
        if (job != null || pendingWork) {
            tag.put("Work", work.save());
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
        foremanId = tag.hasUUID("Foreman") ? tag.getUUID("Foreman") : null;
        cachedForemanTick = -1;
        reconciled = false;
        job = null;
        work.reset();
        pendingWork = foremanId != null && tag.contains("Work", Tag.TAG_COMPOUND);
        if (pendingWork) {
            work.load(tag.getCompound("Work"));
        }
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
