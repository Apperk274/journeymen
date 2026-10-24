package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.Constants;
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
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.BambooSaplingBlock;
import net.minecraft.world.level.block.BambooStalkBlock;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BaseTorchBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.BigDripleafBlock;
import net.minecraft.world.level.block.BigDripleafStemBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BushBlock;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.CarpetBlock;
import net.minecraft.world.level.block.ChorusFlowerBlock;
import net.minecraft.world.level.block.ChorusPlantBlock;
import net.minecraft.world.level.block.CocoaBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.GrowingPlantBlock;
import net.minecraft.world.level.block.HangingRootsBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.RedStoneWireBlock;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.SporeBlossomBlock;
import net.minecraft.world.level.block.SugarCaneBlock;
import net.minecraft.world.level.block.TripWireBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.VineBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.entity.DecoratedPotBlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

public final class PlacingTask {

    private static final int PLACE_DELAY = 4;
    private static final int MAX_INTERACTIONS = 32;
    private static final List<Class<?>> NEEDS_SUPPORT = List.of(
            FaceAttachedHorizontalDirectionalBlock.class, BaseTorchBlock.class, BaseRailBlock.class, RedStoneWireBlock.class,
            DiodeBlock.class, CarpetBlock.class, BasePressurePlateBlock.class, LadderBlock.class, VineBlock.class, SignBlock.class,
            AbstractBannerBlock.class, LanternBlock.class, BushBlock.class, DoorBlock.class, TripWireBlock.class,
            TripWireHookBlock.class, SnowLayerBlock.class, CocoaBlock.class, MultifaceBlock.class, PointedDripstoneBlock.class,
            AmethystClusterBlock.class, CandleBlock.class, HangingRootsBlock.class, SporeBlossomBlock.class, GrowingPlantBlock.class,
            CactusBlock.class, SugarCaneBlock.class, BambooStalkBlock.class, BambooSaplingBlock.class, ChorusPlantBlock.class,
            ChorusFlowerBlock.class, BigDripleafBlock.class, BigDripleafStemBlock.class, BellBlock.class, ScaffoldingBlock.class);

    public enum Status {
        WORKING,
        NEEDS_ITEMS,
        NEEDS_TOOL,
        BLOCKED,
        WAITING,
        DONE
    }

    private enum Kind {
        PLACE,
        FIX,
        FLUID,
        WATERLOG
    }

    private record Entry(BuildPlan.Placement placement, Kind kind) {

        BlockPos pos() {
            return placement.pos();
        }
    }

    private final BuilderEntity builder;
    private final ServerLevel level;
    private final HolderLookup.Provider registries;
    private final TreeMap<Integer, List<Entry>> supports = new TreeMap<>();
    private final TreeMap<Integer, List<Entry>> dependents = new TreeMap<>();
    private final TreeMap<Integer, List<Entry>> fluids = new TreeMap<>();
    private final Reach reach = new Reach();
    private final List<BlockPos> skipped = new ArrayList<>();
    private final int total;
    private int placed;

    @Nullable
    private Entry current;
    @Nullable
    private TreeMap<Integer, List<Entry>> currentGroup;
    @Nullable
    private Entry plantNext;
    private float cooldown;
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

    public PlacingTask(BuilderEntity builder, BuildPlan plan, Predicate<BlockPos> include) {
        this.builder = builder;
        this.level = (ServerLevel) builder.level();
        this.registries = level.registryAccess();
        int count = 0;
        for (BuildPlan.Placement placement : plan.getToPlace()) {
            if (!include.test(placement.pos()) || placedWithOtherPart(placement.target())) {
                continue;
            }
            BlockState target = placement.target();
            if (target.getBlock() instanceof LiquidBlock) {
                if (target.getFluidState().isSource()) {
                    add(fluids, new Entry(placement, Kind.FLUID));
                    count++;
                }
                continue;
            }
            add(needsSupport(target) ? dependents : supports, new Entry(placement, Kind.PLACE));
            count++;
            if (target.getOptionalValue(BlockStateProperties.WATERLOGGED).orElse(false)) {
                add(fluids, new Entry(placement, Kind.WATERLOG));
                count++;
            }
        }
        for (BuildPlan.Placement placement : plan.getToFix()) {
            if (include.test(placement.pos())) {
                add(dependents, new Entry(placement, Kind.FIX));
                count++;
            }
        }
        for (BuildPlan.Placement placement : plan.getToWaterlog()) {
            if (include.test(placement.pos())) {
                add(fluids, new Entry(placement, Kind.WATERLOG));
                count++;
            }
        }
        this.total = count;
    }

    private static boolean placedWithOtherPart(BlockState target) {
        return target.getOptionalValue(BlockStateProperties.DOUBLE_BLOCK_HALF).orElse(null) == DoubleBlockHalf.UPPER
                || target.getOptionalValue(BlockStateProperties.BED_PART).orElse(null) == BedPart.HEAD
                || target.getBlock() instanceof PistonHeadBlock
                || target.getBlock() instanceof MovingPistonBlock;
    }

    private static boolean needsSupport(BlockState state) {
        Block block = state.getBlock();
        for (Class<?> type : NEEDS_SUPPORT) {
            if (type.isInstance(block)) {
                return true;
            }
        }
        return false;
    }

    private static void add(TreeMap<Integer, List<Entry>> layers, Entry entry) {
        layers.computeIfAbsent(entry.pos().getY(), y -> new ArrayList<>()).add(entry);
    }

    public Status tick() {
        if (cooldown > 0) {
            cooldown -= speed();
            return Status.WORKING;
        }
        if (current == null) {
            current = pickNext();
            reach.reset();
            if (current == null) {
                builder.showInHand(ItemStack.EMPTY);
                return Status.DONE;
            }
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
        if (!reach.approach(builder, pos)) {
            return Status.WORKING;
        }
        BlockState placeState = StateRules.forPlacement(target);
        if (!placeState.getCollisionShape(level, pos).isEmpty() && !level.isUnobstructed(placeState, pos, CollisionContext.empty())) {
            if (placeState.getCollisionShape(level, pos).bounds().move(pos).intersects(builder.getBoundingBox())) {
                builder.stepAwayFrom(pos);
                return Status.WORKING;
            }
            waitingAt = pos;
            return Status.WAITING;
        }
        waitingAt = null;
        BlockRequirements.Requirement requirement = BlockRequirements.of(placeState, entry.placement().blockEntity(), registries);
        if (!requirement.obtainable()) {
            skipped.add(pos);
            return finish(false);
        }
        if (!builder.hasItems(requirement.items())) {
            neededItems = requirement.items();
            return Status.NEEDS_ITEMS;
        }
        int toolSlot = -1;
        if (requirement.toolUse() != null) {
            Predicate<ItemStack> usable = toolFor(requirement.toolUse(),
                    builder.getJob() != null && builder.getJob().getOptions().allowBreakingEnchanted());
            toolSlot = builder.findSlot(usable);
            if (toolSlot < 0) {
                neededTool = usable;
                neededToolDescription = requirement.toolUse().description() + " to make " + target.getBlock().getName().getString()
                        + " at " + pos.toShortString();
                return Status.NEEDS_TOOL;
            }
        }
        ServerPlayer actor = builder.actor();
        if (!level.mayInteract(actor, pos) || !Services.PLATFORM.mayPlace(actor, pos, placeState)) {
            skipped.add(pos);
            return finish(false);
        }
        List<ItemStack> taken = builder.takeItems(requirement.items());
        place(entry.placement(), placeState, taken, actor, toolSlot, requirement);
        return finish(true);
    }

    private void place(BuildPlan.Placement placement, BlockState placeState, List<ItemStack> taken, ServerPlayer actor, int toolSlot,
                       BlockRequirements.Requirement requirement) {
        BlockPos pos = placement.pos();
        BlockRequirements.ToolUse toolUse = requirement.toolUse();
        BlockState state = requirement.madeFrom() != null ? requirement.madeFrom() : placeState;
        boolean twoParts = state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) || state.hasProperty(BlockStateProperties.BED_PART);
        if (!twoParts) {
            state = Block.updateFromNeighbourShapes(state, level, pos);
        }
        if (state.isAir()) {
            taken.forEach(builder::giveItem);
            skipped.add(pos);
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
        if (placement.blockEntity() != null && taken.size() > 1) {
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
                plantNext = findEntry(dependents, pos.above());
            }
        }
        builder.swing(InteractionHand.MAIN_HAND);
        placed++;
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
                sign.setText(source.getFrontText(), true);
                sign.setText(source.getBackText(), false);
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
            skipped.add(pos);
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
            skipped.add(pos);
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
        if (!level.mayInteract(actor, pos) || !Services.PLATFORM.mayPlace(actor, pos, target)) {
            skipped.add(pos);
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
            skipped.add(pos);
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
            skipped.add(pos);
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
        return finish(true);
    }

    private static ItemStack emptied(ItemStack container, Fluid fluid) {
        Fluid source = fluid instanceof FlowingFluid flowing ? flowing.getSource() : fluid;
        ItemStack empty = Services.COMPAT.drainBucket(container, source);
        return empty != null ? empty : ItemStack.EMPTY;
    }

    private Status finish(boolean acted) {
        if (current != null && currentGroup != null) {
            List<Entry> layer = currentGroup.get(current.pos().getY());
            if (layer != null) {
                layer.remove(current);
                if (layer.isEmpty()) {
                    currentGroup.remove(current.pos().getY());
                }
            }
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
            List<Entry> layer = dependents.get(entry.pos().getY());
            if (layer != null && layer.contains(entry)) {
                currentGroup = dependents;
                return entry;
            }
        }
        if (!supports.isEmpty()) {
            currentGroup = supports;
            return nearest(supports.firstEntry().getValue(), entry -> true);
        }
        if (!dependents.isEmpty()) {
            currentGroup = dependents;
            for (List<Entry> layer : dependents.values()) {
                Entry entry = nearest(layer, this::canGoInNow);
                if (entry != null) {
                    return entry;
                }
            }
            return dependents.firstEntry().getValue().get(0);
        }
        if (!fluids.isEmpty()) {
            currentGroup = fluids;
            return nearest(fluids.firstEntry().getValue(), entry -> true);
        }
        return null;
    }

    private boolean canGoInNow(Entry entry) {
        return entry.kind() == Kind.FIX || StateRules.forPlacement(entry.placement().target()).canSurvive(level, entry.pos());
    }

    @Nullable
    private Entry nearest(List<Entry> layer, Predicate<Entry> allowed) {
        Entry nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (Entry entry : layer) {
            if (!allowed.test(entry)) {
                continue;
            }
            double distance = Reach.horizontalDistanceSqr(builder, entry.pos());
            if (distance < nearestDistance) {
                nearest = entry;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    public ItemCounter upcomingNeeds(int slots) {
        ItemCounter needs = new ItemCounter();
        int usedSlots = 0;
        for (TreeMap<Integer, List<Entry>> layers : List.of(supports, dependents, fluids)) {
            for (List<Entry> layer : layers.values()) {
                for (Entry entry : layer) {
                    for (ItemStack stack : itemsFor(entry)) {
                        if (needs.count(stack) % stack.getMaxStackSize() == 0) {
                            usedSlots++;
                        }
                        if (usedSlots > slots) {
                            return needs;
                        }
                        needs.add(stack, stack.getCount());
                    }
                }
            }
        }
        return needs;
    }

    public static Predicate<ItemStack> toolFor(BlockRequirements.ToolUse use, boolean allowBreakingEnchanted) {
        return stack -> Services.COMPAT.canDo(stack, use) && ToolCheck.usableDurability(stack, allowBreakingEnchanted) >= 1;
    }

    public Set<BlockRequirements.ToolUse> remainingToolUses() {
        Set<BlockRequirements.ToolUse> uses = EnumSet.noneOf(BlockRequirements.ToolUse.class);
        for (TreeMap<Integer, List<Entry>> layers : List.of(supports, dependents)) {
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
        for (TreeMap<Integer, List<Entry>> layers : List.of(supports, dependents, fluids)) {
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
                yield BlockRequirements.of(StateRules.forPlacement(target), entry.placement().blockEntity(), registries).items();
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

    public int getTotal() {
        return total;
    }

    public int getPlaced() {
        return placed;
    }

    public int getRemaining() {
        int count = 0;
        for (TreeMap<Integer, List<Entry>> layers : List.of(supports, dependents, fluids)) {
            for (List<Entry> layer : layers.values()) {
                count += layer.size();
            }
        }
        return count;
    }

    public List<BlockPos> getSkipped() {
        return skipped;
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
}
