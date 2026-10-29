package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.build.PlacingWork.Entry;
import io.github.apperk274.journeymen.build.PlacingWork.Kind;
import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Predicate;

public final class PlacingTask {

    private static final int PLACE_DELAY = 4;
    private static final int MAX_INTERACTIONS = 32;
    private static final int MIN_BATCH = 8;
    private static final int DEFER_TICKS = 40;

    public enum Status {
        WORKING,
        NEEDS_ITEMS,
        NEEDS_TOOL,
        NEEDS_ROOM,
        MISSING,
        BLOCKED,
        WAITING,
        IDLE,
        DONE
    }

    private final BuilderEntity builder;
    private final BuildJob job;
    private final ServerLevel level;
    private final HolderLookup.Provider registries;
    private final PlacingWork work;
    private final Reach reach;
    private final List<Entry> batch = new ArrayList<>();
    private final Map<Entry, Integer> deferred = new HashMap<>();
    private final Map<Entry, List<ItemStack>> required = new IdentityHashMap<>();
    private boolean newBatch;
    @Nullable
    private TreeMap<Integer, List<Entry>> batchGroup;
    private int placed;

    @Nullable
    private Entry current;
    @Nullable
    private TreeMap<Integer, List<Entry>> currentGroup;
    @Nullable
    private Entry plantNext;
    private float cooldown;
    @Nullable
    private Status idleStatus;
    @Nullable
    private List<ItemStack> neededItems;
    @Nullable
    private Predicate<ItemStack> neededTool;
    @Nullable
    private String neededToolDescription;
    @Nullable
    private String blockedReason;
    @Nullable
    private BlockPos waitingAt;

    public PlacingTask(BuilderEntity builder, BuildJob job, PlacingWork work) {
        this.builder = builder;
        this.job = job;
        this.level = (ServerLevel) builder.level();
        this.registries = level.registryAccess();
        this.work = work;
        this.reach = new Reach(job.getArea());
    }

    public PlacingWork getWork() {
        return work;
    }

    @Nullable
    public static BlockPos rampStart(BuilderEntity builder) {
        for (AssignedChest chest : builder.jobChests()) {
            if (chest.role() == AssignedChest.Role.SUPPLY) {
                return chest.pos();
            }
        }
        return builder.blockPosition();
    }

    public Status tick() {
        if (cooldown > 0) {
            cooldown -= speed();
            return Status.WORKING;
        }
        if (current == null) {
            reach.reset();
            idleStatus = null;
            current = pickNext();
            if (current == null) {
                builder.showInHand(ItemStack.EMPTY);
                if (work.isEmpty()) {
                    return Status.DONE;
                }
                return idleStatus != null ? idleStatus : Status.IDLE;
            }
            job.getCrewWork().claim(current.pos(), builder.getUUID());
        }
        return switch (current.kind()) {
            case PLACE -> tickPlace(current);
            case FIX -> tickFix(current);
            case FLUID -> tickFluid(current);
            case WATERLOG -> tickWaterlog(current);
        };
    }

    private Status tickPlace(Entry entry) {
        BlockPos pos = entry.pos();
        BlockState target = entry.placement().target();
        BlockState actual = level.getBlockState(pos);
        if (StateRules.matches(target, actual)) {
            return finish(false);
        }
        if (actual.getBlock() instanceof LiquidBlock) {
            blockedReason = "Water or lava is in the way at " + pos.toShortString() + ". Please remove it.";
            return Status.BLOCKED;
        }
        if (!actual.isAir() && !actual.canBeReplaced()) {
            return finish(false);
        }
        BlockState placeState = StateRules.forPlacement(target);
        BlockRequirements.Requirement requirement = BlockRequirements.of(placeState, entry.placement().blockEntity(), registries);
        if (!requirement.obtainable()) {
            work.skipped.add(pos);
            return finish(false);
        }
        if (!builder.hasItems(requirement.items())) {
            neededItems = requirement.items();
            return Status.NEEDS_ITEMS;
        }
        Predicate<ItemStack> usable = requirement.toolUse() == null ? null : toolFor(requirement.toolUse(), job.getOptions().allowBreakingEnchanted());
        if (usable != null && builder.findSlot(usable) < 0) {
            neededTool = usable;
            neededToolDescription = requirement.toolUse().description() + " to make " + target.getBlock().getName().getString()
                    + " at " + pos.toShortString();
            return Status.NEEDS_TOOL;
        }
        if (!reach.approach(builder, pos)) {
            return Status.WORKING;
        }
        if (currentGroup == work.dependents && !canGoInNow(entry)) {
            return putBack();
        }
        if (!placeState.getCollisionShape(level, pos).isEmpty() && !level.isUnobstructed(placeState, pos, CollisionContext.empty())) {
            AABB box = placeState.getCollisionShape(level, pos).bounds().move(pos);
            if (box.intersects(builder.getBoundingBox())) {
                builder.stepAwayFrom(pos);
                return Status.WORKING;
            }
            if (builder.askToStepAside(box) && builder.inCrewMode()) {
                deferred.put(entry, builder.tickCount + DEFER_TICKS);
                return putBack();
            }
            waitingAt = pos;
            return Status.WAITING;
        }
        waitingAt = null;
        int toolSlot = usable == null ? -1 : builder.findSlot(usable);
        if (usable != null && toolSlot < 0) {
            return Status.WORKING;
        }
        ServerPlayer actor = builder.actor();
        if (!OwnerRights.mayPlace(actor, pos, placeState)) {
            work.skipped.add(pos);
            return finish(false);
        }
        List<ItemStack> taken = builder.takeItems(requirement.items());
        place(entry.placement(), placeState, taken, actor, toolSlot, requirement);
        return finish(true);
    }

    private void place(BuildPlan.Placement placement, BlockState placeState, List<ItemStack> taken, ServerPlayer actor, int toolSlot,
                       BlockRequirements.Requirement requirement) {
        BlockPos pos = placement.pos();
        if (job.getCrewWork().claimedByOther(pos, builder.getUUID())) {
            Constants.LOG.warn("[crew] {}.{} places at {}, which another builder claimed", builder.getOwnerName(), builder.getJourneymanName(),
                    pos.toShortString());
        }
        BlockRequirements.ToolUse toolUse = requirement.toolUse();
        BlockState state = requirement.madeFrom() != null ? requirement.madeFrom() : placeState;
        if (!PlacingWork.hasTwoParts(state)) {
            state = Block.updateFromNeighbourShapes(state, level, pos);
        }
        if (state.isAir()) {
            taken.forEach(builder::giveItem);
            work.skipped.add(pos);
            return;
        }
        ItemStack base = taken.isEmpty() ? ItemStack.EMPTY : taken.get(0);
        builder.showInHand(base);
        builder.getLookControl().setLookAt(Vec3.atCenterOf(pos));
        actor.setYRot(builder.getYRot());
        level.setBlock(pos, state, Block.UPDATE_ALL);
        if (base.getItem() instanceof BlockItem) {
            BlockItem.updateCustomBlockEntityTag(level, actor, pos, base);
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null && !base.isEmpty()) {
            blockEntity.applyComponentsFromItemStack(base);
            blockEntity.setChanged();
        }
        state.getBlock().setPlacedBy(level, pos, state, actor, base);
        if (placement.blockEntity() != null && (taken.size() > 1 || blockEntity instanceof SignBlockEntity)) {
            applyContents(pos, placement.blockEntity(), taken.subList(1, taken.size()));
        }
        SoundType sound = state.getSoundType();
        level.playSound(null, pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        level.gameEvent(GameEvent.BLOCK_PLACE, pos, GameEvent.Context.of(builder, state));
        if (toolUse != null) {
            ItemStack tool = builder.getInventory().getItem(toolSlot);
            builder.showInHand(tool);
            tool.hurtAndBreak(1, builder, EquipmentSlot.MAINHAND);
            builder.getInventory().setChanged();
            level.setBlock(pos, placeState, Block.UPDATE_ALL);
            level.playSound(null, pos, toolUse == BlockRequirements.ToolUse.HOE ? SoundEvents.HOE_TILL : SoundEvents.SHOVEL_FLATTEN,
                    SoundSource.BLOCKS, 1.0F, 1.0F);
            if (toolUse == BlockRequirements.ToolUse.HOE) {
                plantNext = findEntry(work.dependents, pos.above());
            }
        }
        builder.swing(InteractionHand.MAIN_HAND);
        placed++;
        work.placed++;
    }

    private void applyContents(BlockPos pos, CompoundTag tag, List<ItemStack> extras) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            extras.forEach(builder::giveItem);
            return;
        }
        BlockEntity template;
        try {
            template = BlockEntity.loadStatic(pos, level.getBlockState(pos), tag, registries);
        } catch (RuntimeException e) {
            Constants.LOG.warn("Could not read block entity data at {}", pos, e);
            template = null;
        }
        List<ItemStack> unused = new ArrayList<>();
        if (blockEntity instanceof SignBlockEntity sign) {
            if (template instanceof SignBlockEntity source) {
                sign.setText(SafeText.sign(source.getFrontText()), true);
                sign.setText(SafeText.sign(source.getBackText()), false);
            }
        } else if (blockEntity instanceof DecoratedPotBlockEntity pot) {
            pot.setTheItem(extras.get(0));
            unused.addAll(extras.subList(1, extras.size()));
        } else if (blockEntity instanceof LecternBlockEntity) {
            LecternBlock.tryPlaceBook(builder, level, pos, level.getBlockState(pos), extras.get(0).copy());
            unused.addAll(extras.subList(1, extras.size()));
        } else if (blockEntity instanceof JukeboxBlockEntity jukebox) {
            jukebox.setTheItem(extras.get(0));
            unused.addAll(extras.subList(1, extras.size()));
        } else if (blockEntity instanceof ChiseledBookShelfBlockEntity shelf && template instanceof ChiseledBookShelfBlockEntity source) {
            int next = 0;
            for (int slot = 0; slot < source.getContainerSize() && next < extras.size(); slot++) {
                if (!source.getItem(slot).isEmpty()) {
                    shelf.setItem(slot, extras.get(next++));
                }
            }
            unused.addAll(extras.subList(next, extras.size()));
        } else {
            unused.addAll(extras);
        }
        unused.forEach(builder::giveItem);
        blockEntity.setChanged();
    }

    private Status tickFix(Entry entry) {
        BlockPos pos = entry.pos();
        BlockState target = entry.placement().target();
        BlockState actual = level.getBlockState(pos);
        if (StateRules.matches(target, actual) || !StateRules.fixableByHand(target, actual)) {
            return finish(false);
        }
        if (!reach.approach(builder, pos)) {
            return Status.WORKING;
        }
        ServerPlayer actor = builder.actor();
        if (!level.mayInteract(actor, pos)) {
            work.skipped.add(pos);
            return finish(false);
        }
        builder.showInHand(ItemStack.EMPTY);
        builder.getLookControl().setLookAt(Vec3.atCenterOf(pos));
        target.getOptionalValue(BlockStateProperties.HORIZONTAL_FACING).ifPresent(facing -> actor.setYRot(facing.toYRot()));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        for (int i = 0; i < MAX_INTERACTIONS && !StateRules.matches(target, level.getBlockState(pos)); i++) {
            level.getBlockState(pos).useWithoutItem(level, actor, hit);
        }
        builder.swing(InteractionHand.MAIN_HAND);
        placed++;
        work.placed++;
        return finish(true);
    }

    private Status tickFluid(Entry entry) {
        BlockPos pos = entry.pos();
        BlockState target = entry.placement().target();
        BlockState actual = level.getBlockState(pos);
        if (actual.getFluidState().isSource() && actual.getFluidState().getType().isSame(target.getFluidState().getType())) {
            return finish(false);
        }
        if (!actual.isAir() && !actual.canBeReplaced()) {
            return finish(false);
        }
        boolean water = target.getFluidState().is(FluidTags.WATER);
        if (water && level.dimensionType().ultraWarm()) {
            work.skipped.add(pos);
            return finish(false);
        }
        if (!reach.approach(builder, pos)) {
            return Status.WORKING;
        }
        ItemStack bucket = new ItemStack(target.getFluidState().getType().getBucket());
        if (!builder.hasItems(List.of(bucket))) {
            neededItems = List.of(bucket);
            return Status.NEEDS_ITEMS;
        }
        ServerPlayer actor = builder.actor();
        if (!OwnerRights.mayPlace(actor, pos, target)) {
            work.skipped.add(pos);
            return finish(false);
        }
        ItemStack container = builder.takeItems(List.of(bucket)).get(0);
        builder.showInHand(container);
        builder.getLookControl().setLookAt(Vec3.atCenterOf(pos));
        level.setBlock(pos, target, Block.UPDATE_ALL);
        level.playSound(null, pos, water ? SoundEvents.BUCKET_EMPTY : SoundEvents.BUCKET_EMPTY_LAVA, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.gameEvent(builder, GameEvent.FLUID_PLACE, pos);
        builder.giveItem(emptied(container, target.getFluidState().getType()));
        builder.swing(InteractionHand.MAIN_HAND);
        placed++;
        work.placed++;
        return finish(true);
    }

    private Status tickWaterlog(Entry entry) {
        BlockPos pos = entry.pos();
        BlockState target = entry.placement().target();
        BlockState actual = level.getBlockState(pos);
        if (!actual.hasProperty(BlockStateProperties.WATERLOGGED) || actual.getValue(BlockStateProperties.WATERLOGGED)
                || !StateRules.matches(target, actual)) {
            return finish(false);
        }
        if (level.dimensionType().ultraWarm()) {
            work.skipped.add(pos);
            return finish(false);
        }
        if (!reach.approach(builder, pos)) {
            return Status.WORKING;
        }
        ItemStack bucket = new ItemStack(Items.WATER_BUCKET);
        if (!builder.hasItems(List.of(bucket))) {
            neededItems = List.of(bucket);
            return Status.NEEDS_ITEMS;
        }
        ServerPlayer actor = builder.actor();
        if (!level.mayInteract(actor, pos)) {
            work.skipped.add(pos);
            return finish(false);
        }
        ItemStack container = builder.takeItems(List.of(bucket)).get(0);
        builder.showInHand(container);
        builder.getLookControl().setLookAt(Vec3.atCenterOf(pos));
        level.setBlock(pos, actual.setValue(BlockStateProperties.WATERLOGGED, true), Block.UPDATE_ALL);
        level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        level.playSound(null, pos, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.gameEvent(builder, GameEvent.FLUID_PLACE, pos);
        builder.giveItem(emptied(container, Fluids.WATER));
        builder.swing(InteractionHand.MAIN_HAND);
        placed++;
        work.placed++;
        return finish(true);
    }

    private static ItemStack emptied(ItemStack container, Fluid fluid) {
        Fluid source = fluid instanceof FlowingFluid flowing ? flowing.getSource() : fluid;
        ItemStack empty = Services.COMPAT.drainBucket(container, source);
        return empty != null ? empty : ItemStack.EMPTY;
    }

    public void skipCurrent() {
        if (current != null) {
            work.skipped.add(current.pos());
            finish(false);
        }
    }

    private Status putBack() {
        if (current != null && !batch.contains(current)) {
            job.getCrewWork().unclaim(current.pos(), builder.getUUID());
        }
        current = null;
        currentGroup = null;
        waitingAt = null;
        reach.reset();
        return Status.WORKING;
    }

    private Status finish(boolean acted) {
        if (current != null && currentGroup != null) {
            PlacingWork.remove(currentGroup, current);
        }
        if (current != null) {
            batch.remove(current);
            job.getCrewWork().unclaim(current.pos(), builder.getUUID());
        }
        current = null;
        currentGroup = null;
        waitingAt = null;
        reach.reset();
        if (acted) {
            cooldown = PLACE_DELAY;
        }
        return Status.WORKING;
    }

    @Nullable
    private Entry findEntry(TreeMap<Integer, List<Entry>> layers, BlockPos pos) {
        List<Entry> layer = layers.get(pos.getY());
        if (layer != null) {
            for (Entry entry : layer) {
                if (entry.kind() == Kind.PLACE && entry.pos().equals(pos)) {
                    return entry;
                }
            }
        }
        return null;
    }

    @Nullable
    private Entry pickNext() {
        if (plantNext != null) {
            Entry entry = plantNext;
            plantNext = null;
            List<Entry> layer = work.dependents.get(entry.pos().getY());
            if (layer != null && layer.contains(entry) && canGoInNow(entry)
                    && !job.getCrewWork().claimedByOther(entry.pos(), builder.getUUID())) {
                currentGroup = work.dependents;
                return entry;
            }
        }
        if (builder.inCrewMode()) {
            return crewPick();
        }
        job.getCrewWork().keepOnly(builder.activeCrew());
        batch.clear();
        batchGroup = null;
        for (TreeMap<Integer, List<Entry>> group : List.of(work.supports, work.ramp)) {
            if (!group.isEmpty()) {
                currentGroup = group;
                return reach.choose(builder, carriedFirst(group.firstEntry().getValue()), Entry::pos);
            }
        }
        if (!work.dependents.isEmpty()) {
            for (List<Entry> layer : work.dependents.values()) {
                List<Entry> ready = layer.stream().filter(this::canGoInNow).toList();
                if (!ready.isEmpty()) {
                    currentGroup = work.dependents;
                    return reach.choose(builder, carriedFirst(ready), Entry::pos);
                }
            }
            skipStrandedDependents();
        }
        if (!work.fluids.isEmpty()) {
            currentGroup = work.fluids;
            return reach.choose(builder, carriedFirst(work.fluids.firstEntry().getValue()), Entry::pos);
        }
        return null;
    }

    private List<Entry> carriedFirst(List<Entry> entries) {
        ItemCounter carried = builder.countInventory();
        List<Entry> covered = entries.stream().filter(entry -> covers(itemsFor(entry), carried)).toList();
        return covered.isEmpty() ? entries : covered;
    }

    private void skipStrandedDependents() {
        for (List<Entry> layer : work.dependents.values()) {
            for (Entry entry : layer) {
                if (!StateRules.matches(entry.placement().target(), level.getBlockState(entry.pos()))) {
                    work.skipped.add(entry.pos());
                }
            }
        }
        work.dependents.clear();
    }

    @Nullable
    private Entry crewPick() {
        CrewWork crew = job.getCrewWork();
        UUID me = builder.getUUID();
        if (batchGroup != null) {
            TreeMap<Integer, List<Entry>> group = batchGroup;
            batch.removeIf(entry -> {
                List<Entry> layer = group.get(entry.key());
                return layer == null || !layer.contains(entry);
            });
            deferred.values().removeIf(until -> until <= builder.tickCount);
            List<Entry> ready = batch.stream().filter(entry -> !deferred.containsKey(entry)
                    && (group != work.dependents || canGoInNow(entry))).toList();
            if (!ready.isEmpty()) {
                currentGroup = group;
                return reach.choose(builder, ready, Entry::pos);
            }
            if (batch.stream().anyMatch(deferred::containsKey)) {
                return null;
            }
        }
        batch.clear();
        batchGroup = null;
        crew.keepOnly(builder.activeCrew());
        crew.release(me);
        for (TreeMap<Integer, List<Entry>> group : List.of(work.supports, work.ramp)) {
            if (!group.isEmpty()) {
                return claim(group, columnFirst(group, crew, me));
            }
        }
        if (!work.dependents.isEmpty()) {
            boolean othersHold = false;
            for (List<Entry> layer : work.dependents.values()) {
                List<Entry> ready = new ArrayList<>();
                for (Entry entry : layer) {
                    if (crew.claimedByOther(entry.pos(), me)) {
                        othersHold = true;
                    } else if (canGoInNow(entry)) {
                        ready.add(entry);
                    }
                }
                if (!ready.isEmpty()) {
                    return claim(work.dependents, ready);
                }
            }
            if (othersHold) {
                return null;
            }
            skipStrandedDependents();
        }
        for (List<Entry> layer : work.fluids.values()) {
            List<Entry> free = layer.stream().filter(entry -> !crew.claimedByOther(entry.pos(), me)).toList();
            if (!free.isEmpty()) {
                return claim(work.fluids, free);
            }
        }
        return null;
    }

    private static List<Entry> columnFirst(TreeMap<Integer, List<Entry>> group, CrewWork crew, UUID me) {
        Map<Long, Integer> first = new HashMap<>();
        for (List<Entry> layer : group.values()) {
            for (Entry entry : layer) {
                first.putIfAbsent(ChunkPos.asLong(entry.pos().getX(), entry.pos().getZ()), entry.key());
            }
        }
        for (List<Entry> layer : group.values()) {
            List<Entry> eligible = new ArrayList<>();
            for (Entry entry : layer) {
                if (!crew.claimedByOther(entry.pos(), me) && first.get(ChunkPos.asLong(entry.pos().getX(), entry.pos().getZ())) == entry.key()) {
                    eligible.add(entry);
                }
            }
            if (!eligible.isEmpty()) {
                return eligible;
            }
        }
        return List.of();
    }

    @Nullable
    private Entry claim(TreeMap<Integer, List<Entry>> group, List<Entry> candidates) {
        if (candidates.isEmpty()) {
            return null;
        }
        CrewWork crew = job.getCrewWork();
        UUID me = builder.getUUID();
        ItemCounter carried = builder.countInventory();
        ItemCounter fetchable = builder.fetchableStock();
        ItemCounter othersHold = builder.heldByOtherMembers();
        List<Entry> suppliable = new ArrayList<>();
        List<Entry> preferred = new ArrayList<>();
        Entry missing = null;
        for (Entry entry : candidates) {
            List<ItemStack> items = itemsFor(entry);
            if (covers(items, carried)) {
                if (toolReady(entry)) {
                    preferred.add(entry);
                    suppliable.add(entry);
                }
            } else if (covers(items, carried, fetchable)) {
                if (toolReady(entry)) {
                    suppliable.add(entry);
                }
            } else if (!covers(items, carried, fetchable, othersHold) && missing == null) {
                missing = entry;
            }
        }
        if (suppliable.isEmpty()) {
            if (missing != null) {
                if (job.getOptions().skipMissing()) {
                    work.skipped.add(missing.pos());
                    PlacingWork.remove(group, missing);
                    idleStatus = Status.WORKING;
                } else {
                    neededItems = itemsFor(missing);
                    idleStatus = Status.MISSING;
                }
            } else {
                crew.setStarving(me, true);
            }
            return null;
        }
        crew.setStarving(me, false);
        Entry first = reach.choose(builder, preferred.isEmpty() ? suppliable : preferred, Entry::pos);
        if (first == null) {
            return null;
        }
        ItemCounter need = new ItemCounter();
        itemsFor(first).forEach(stack -> need.add(stack, stack.getCount()));
        int freeSlots = builder.freeSlots();
        if (need.slotsBeyond(carried) > freeSlots) {
            idleStatus = Status.NEEDS_ROOM;
            return null;
        }
        batch.add(first);
        List<Entry> rest = new ArrayList<>(suppliable);
        rest.remove(first);
        rest.sort(Comparator.comparingDouble(entry -> entry.pos().distSqr(first.pos())));
        ItemCounter total = need;
        int members = builder.activeCrew().size();
        List<Entry> layer = group.get(first.key());
        int share = layer == null ? candidates.size() : layer.size();
        int cap = Math.max(MIN_BATCH, (share + members - 1) / members);
        for (Entry entry : rest) {
            if (batch.size() >= cap) {
                break;
            }
            ItemCounter more = total.copy();
            itemsFor(entry).forEach(stack -> more.add(stack, stack.getCount()));
            if (more.slotsBeyond(carried) > freeSlots || !covers(more, carried, fetchable)) {
                continue;
            }
            total = more;
            batch.add(entry);
        }
        batch.forEach(entry -> crew.claim(entry.pos(), me));
        ItemCounter reserved = shortfall(total, carried);
        crew.reserve(me, reserved);
        if (Services.PLATFORM.isDevelopmentEnvironment()) {
            Constants.LOG.info("[crew] {}.{} claims {} blocks and reserves {}", builder.getOwnerName(), builder.getJourneymanName(), batch.size(),
                    describe(reserved));
        }
        batchGroup = group;
        currentGroup = group;
        newBatch = true;
        return first;
    }

    private boolean toolReady(Entry entry) {
        if (entry.kind() != Kind.PLACE) {
            return true;
        }
        BlockDrops.Made made = BlockDrops.made(entry.placement().target().getBlock());
        if (made == null || StateRules.matches(entry.placement().target(), level.getBlockState(entry.pos()))) {
            return true;
        }
        Predicate<ItemStack> usable = toolFor(made.toolUse(), job.getOptions().allowBreakingEnchanted());
        return builder.findSlot(usable) >= 0 || builder.supplyHas(usable) || !builder.crewHolds(usable);
    }

    private static boolean covers(List<ItemStack> items, ItemCounter... stocks) {
        ItemCounter need = new ItemCounter();
        items.forEach(stack -> need.add(stack, stack.getCount()));
        return covers(need, stocks);
    }

    private static boolean covers(ItemCounter need, ItemCounter... stocks) {
        for (Map.Entry<ItemStack, Long> entry : need.entries()) {
            long have = 0;
            for (ItemCounter stock : stocks) {
                have += stock.count(entry.getKey());
            }
            if (have < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private static String describe(ItemCounter items) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<ItemStack, Long> entry : items.sorted()) {
            parts.add(entry.getValue() + " " + ItemMatch.name(entry.getKey()).getString());
        }
        return parts.isEmpty() ? "nothing" : String.join(", ", parts);
    }

    private static ItemCounter shortfall(ItemCounter need, ItemCounter carried) {
        ItemCounter missing = new ItemCounter();
        for (Map.Entry<ItemStack, Long> entry : need.entries()) {
            missing.add(entry.getKey(), entry.getValue() - carried.count(entry.getKey()));
        }
        return missing;
    }

    public ItemCounter batchNeeds() {
        ItemCounter needs = new ItemCounter();
        if (current != null && !batch.contains(current)) {
            itemsFor(current).forEach(stack -> needs.add(stack, stack.getCount()));
        }
        for (Entry entry : batch) {
            itemsFor(entry).forEach(stack -> needs.add(stack, stack.getCount()));
        }
        return needs;
    }

    public Set<BlockRequirements.ToolUse> batchToolUses() {
        Set<BlockRequirements.ToolUse> uses = EnumSet.noneOf(BlockRequirements.ToolUse.class);
        for (Entry entry : batch) {
            BlockDrops.Made made = entry.kind() == Kind.PLACE ? BlockDrops.made(entry.placement().target().getBlock()) : null;
            if (made != null) {
                uses.add(made.toolUse());
            }
        }
        return uses;
    }

    public boolean takeNewBatch() {
        boolean claimed = newBatch;
        newBatch = false;
        return claimed;
    }

    public void releaseClaims() {
        current = null;
        currentGroup = null;
        waitingAt = null;
        batch.clear();
        batchGroup = null;
        reach.reset();
        job.getCrewWork().release(builder.getUUID());
    }

    private boolean canGoInNow(Entry entry) {
        if (entry.kind() == Kind.FIX) {
            return true;
        }
        BlockState placeState = StateRules.forPlacement(entry.placement().target());
        if (!placeState.canSurvive(level, entry.pos())
                || !PlacingWork.hasTwoParts(placeState) && SandboxLevel.shapedHere(level, entry.pos(), placeState).isAir()) {
            return false;
        }
        return !(placeState.getBlock() instanceof FallingBlock) || !FallingBlock.isFree(level.getBlockState(entry.pos().below()));
    }

    public ItemCounter upcomingNeeds(int slots, ItemCounter carried) {
        return upcomingNeeds(slots, carried, false);
    }

    public ItemCounter upcomingUnclaimed(int slots, ItemCounter carried) {
        return upcomingNeeds(slots, carried, true);
    }

    private ItemCounter upcomingNeeds(int slots, ItemCounter carried, boolean unclaimedOnly) {
        Window window = new Window(slots, carried);
        Entry first = unclaimedOnly ? null : current;
        if (first != null && !window.add(itemsFor(first))) {
            return window.needs;
        }
        CrewWork crew = job.getCrewWork();
        for (TreeMap<Integer, List<Entry>> layers : work.groups()) {
            for (List<Entry> layer : layers.values()) {
                for (Entry entry : layer) {
                    if (entry == first || unclaimedOnly && (batch.contains(entry) || crew.claimedByOther(entry.pos(), builder.getUUID()))) {
                        continue;
                    }
                    if (!window.add(itemsFor(entry))) {
                        return window.needs;
                    }
                }
            }
        }
        return window.needs;
    }

    public static Predicate<ItemStack> toolFor(BlockRequirements.ToolUse use, boolean allowBreakingEnchanted) {
        return stack -> Services.COMPAT.canDo(stack, use) && ToolCheck.usableDurability(stack, allowBreakingEnchanted) >= 1;
    }

    public Set<BlockRequirements.ToolUse> remainingToolUses() {
        Set<BlockRequirements.ToolUse> uses = EnumSet.noneOf(BlockRequirements.ToolUse.class);
        for (TreeMap<Integer, List<Entry>> layers : List.of(work.supports, work.ramp, work.dependents)) {
            for (List<Entry> layer : layers.values()) {
                for (Entry entry : layer) {
                    BlockDrops.Made made = entry.kind() == Kind.PLACE ? BlockDrops.made(entry.placement().target().getBlock()) : null;
                    if (made != null) {
                        uses.add(made.toolUse());
                    }
                }
            }
        }
        return uses;
    }

    public ItemCounter remainingNeeds() {
        ItemCounter needs = new ItemCounter();
        for (TreeMap<Integer, List<Entry>> layers : work.groups()) {
            for (List<Entry> layer : layers.values()) {
                for (Entry entry : layer) {
                    itemsFor(entry).forEach(stack -> needs.add(stack, stack.getCount()));
                }
            }
        }
        return needs;
    }

    private List<ItemStack> itemsFor(Entry entry) {
        BlockState target = entry.placement().target();
        return switch (entry.kind()) {
            case PLACE -> {
                if (StateRules.matches(target, level.getBlockState(entry.pos()))) {
                    yield List.of();
                }
                yield required.computeIfAbsent(entry,
                        key -> BlockRequirements.of(StateRules.forPlacement(target), key.placement().blockEntity(), registries).items());
            }
            case FLUID -> List.of(new ItemStack(target.getFluidState().getType().getBucket()));
            case WATERLOG -> List.of(new ItemStack(Items.WATER_BUCKET));
            case FIX -> List.of();
        };
    }

    public void stop() {
        builder.showInHand(ItemStack.EMPTY);
    }

    private static float speed() {
        return JourneymenConfig.SPEED_MULTIPLIER.get().floatValue();
    }

    public Reach getReach() {
        return reach;
    }

    public int getTotal() {
        return work.getTotal();
    }

    public int getPlaced() {
        return work.getPlaced();
    }

    public int getOwnPlaced() {
        return placed;
    }

    public int getRemaining() {
        return work.getRemaining();
    }

    public List<BlockPos> getSkipped() {
        return work.getSkipped();
    }

    @Nullable
    public List<ItemStack> getNeededItems() {
        return neededItems;
    }

    @Nullable
    public Predicate<ItemStack> getNeededTool() {
        return neededTool;
    }

    @Nullable
    public String getNeededToolDescription() {
        return neededToolDescription;
    }

    @Nullable
    public String getBlockedReason() {
        return blockedReason;
    }

    @Nullable
    public BlockPos getWaitingAt() {
        return waitingAt;
    }

    private static final class Window {

        private final ItemCounter needs = new ItemCounter();
        private final int slots;
        private final ItemCounter carried;
        private int usedSlots;

        private Window(int slots, ItemCounter carried) {
            this.slots = slots;
            this.carried = carried;
        }

        private boolean add(List<ItemStack> items) {
            for (ItemStack stack : items) {
                long before = needs.count(stack);
                int carriedSlots = ItemCounter.slotsFor(stack, carried.count(stack));
                usedSlots += Math.max(0, ItemCounter.slotsFor(stack, before + stack.getCount()) - carriedSlots)
                        - Math.max(0, ItemCounter.slotsFor(stack, before) - carriedSlots);
                if (usedSlots > slots) {
                    return false;
                }
                needs.add(stack, stack.getCount());
            }
            return true;
        }
    }
}
