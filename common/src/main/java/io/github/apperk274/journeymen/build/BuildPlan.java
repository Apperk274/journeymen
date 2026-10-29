package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public final class BuildPlan {

    public record Placement(BlockPos pos, BlockState target, @Nullable CompoundTag blockEntity) {
    }

    public record Requirements(ItemCounter items, Map<BlockRequirements.ToolUse, Integer> toolUses, Map<Block, Integer> unobtainable) {
    }

    private final BoundingBox area;
    private final List<BlockPos> toClear = new ArrayList<>();
    private final List<Placement> toPlace = new ArrayList<>();
    private final List<Placement> toFix = new ArrayList<>();
    private final List<Placement> toWaterlog = new ArrayList<>();
    private final List<Placement> toDrain = new ArrayList<>();
    private final List<Placement> flowingTargets = new ArrayList<>();
    private final List<BlockPos> fluidsInTheWay = new ArrayList<>();
    private final List<BlockPos> flowingInTheWay = new ArrayList<>();
    private final Set<BlockPos> inWaterSource = new HashSet<>();
    @Nullable
    private Function<BlockPos, BlockState> schematicStates;
    private final Map<Block, Integer> unplaceable = new LinkedHashMap<>();
    private final boolean skipUnplaceable;
    private int doneCount;
    private int totalCount;

    private BuildPlan(BoundingBox area, boolean skipUnplaceable) {
        this.area = area;
        this.skipUnplaceable = skipUnplaceable;
    }

    public static boolean isLoaded(ServerLevel level, BoundingBox area) {
        for (int chunkX = SectionPos.blockToSectionCoord(area.minX()); chunkX <= SectionPos.blockToSectionCoord(area.maxX()); chunkX++) {
            for (int chunkZ = SectionPos.blockToSectionCoord(area.minZ()); chunkZ <= SectionPos.blockToSectionCoord(area.maxZ()); chunkZ++) {
                if (!level.hasChunk(chunkX, chunkZ)) {
                    return false;
                }
            }
        }
        return true;
    }

    public static BuildPlan create(ServerLevel level, Schematic schematic, PlacementTransform transform, BuildOptions options) {
        BuildPlan plan = new BuildPlan(transform.area(schematic.getSize()), options.skipUnplaceable());
        int width = schematic.getSize().getX();
        int height = schematic.getSize().getY();
        int length = schematic.getSize().getZ();
        Function<BlockPos, BlockState> neighbours = neighbours(level, schematic, transform);
        plan.schematicStates = neighbours;
        for (int y = 0; y < height; y++) {
            for (int z = 0; z < length; z++) {
                for (int x = 0; x < width; x++) {
                    BlockState schematicState = schematic.getBlock(x, y, z);
                    if (schematicState == null) {
                        continue;
                    }
                    plan.totalCount++;
                    BlockPos pos = transform.toWorld(x, y, z);
                    BlockState target = transform.transform(schematicState);
                    BlockState actual = level.getBlockState(pos);
                    plan.classify(level, pos, target, actual, schematic.getBlockEntity(x, y, z),
                            BlockDrops.unmadeFrom(target, pos, neighbours));
                }
            }
        }
        return plan;
    }

    public static Function<BlockPos, BlockState> neighbours(@Nullable ServerLevel level, Schematic schematic, PlacementTransform transform) {
        Vec3i size = schematic.getSize();
        return at -> {
            BlockPos local = transform.toSchematic(at);
            if (local.getX() >= 0 && local.getY() >= 0 && local.getZ() >= 0
                    && local.getX() < size.getX() && local.getY() < size.getY() && local.getZ() < size.getZ()) {
                BlockState state = schematic.getBlock(local);
                if (state != null) {
                    return transform.transform(state);
                }
            }
            return level != null && level.isLoaded(at) ? level.getBlockState(at) : Blocks.AIR.defaultBlockState();
        };
    }

    public static BuildPlan of(ServerLevel level, BoundingBox area, List<Placement> targets, boolean skipUnplaceable, Map<BlockPos, BlockState> unmade) {
        BuildPlan plan = new BuildPlan(area, skipUnplaceable);
        for (Placement target : targets) {
            plan.totalCount++;
            plan.classify(level, target.pos(), target.target(), level.getBlockState(target.pos()), target.blockEntity(), unmade.get(target.pos()));
        }
        return plan;
    }

    private void classify(ServerLevel level, BlockPos pos, BlockState target, BlockState actual, @Nullable CompoundTag blockEntity,
                          @Nullable BlockState unmadeFrom) {
        if (target.getBlock() instanceof LiquidBlock && !target.getFluidState().isSource()) {
            if (actual.isAir() || actual.getBlock() instanceof LiquidBlock) {
                doneCount++;
            } else {
                toClear.add(pos);
                flowingTargets.add(new Placement(pos, target, null));
            }
            return;
        }
        if (target.isAir()) {
            if (actual.isAir()) {
                doneCount++;
            } else if (actual.getBlock() instanceof LiquidBlock) {
                (actual.getFluidState().isSource() ? fluidsInTheWay : flowingInTheWay).add(pos);
            } else {
                toClear.add(pos);
            }
            return;
        }
        if (StateRules.matchesIncludingWater(target, actual)
                || target.is(actual.getBlock()) && StateRules.matchesIncludingWater(SandboxLevel.shapedHere(level, pos, target), actual)) {
            doneCount++;
            return;
        }
        if (unmadeFrom != null) {
            if (actual.is(unmadeFrom.getBlock())) {
                doneCount++;
                return;
            }
            target = unmadeFrom;
        }
        BlockState grownFrom = BlockDrops.grownFrom(target);
        if (grownFrom != null) {
            if (actual.is(grownFrom.getBlock())) {
                doneCount++;
                return;
            }
            target = grownFrom;
        }
        if (StateRules.matches(target, actual)) {
            (WaterRules.isWaterlogged(target) ? toWaterlog : toDrain).add(new Placement(pos, target, blockEntity));
            return;
        }
        if (StateRules.fixableByHand(target, actual)) {
            toFix.add(new Placement(pos, target, blockEntity));
            return;
        }
        if (skipUnplaceable && !BlockRequirements.of(target, null, null).obtainable()) {
            unplaceable.merge(target.getBlock(), 1, Integer::sum);
            totalCount--;
            return;
        }
        if (WaterRules.isWaterSource(actual)) {
            inWaterSource.add(pos);
        } else if (!actual.isAir() && !actual.canBeReplaced()) {
            toClear.add(pos);
        }
        toPlace.add(new Placement(pos, target, blockEntity));
    }

    public Requirements requirements(HolderLookup.Provider registries) {
        ItemCounter items = new ItemCounter();
        Map<BlockRequirements.ToolUse, Integer> toolUses = new EnumMap<>(BlockRequirements.ToolUse.class);
        Map<Block, Integer> unobtainable = new LinkedHashMap<>();
        for (Placement placement : toPlace) {
            BlockRequirements.Requirement requirement = BlockRequirements.of(placement.target(), placement.blockEntity(), registries);
            if (!requirement.obtainable()) {
                unobtainable.merge(placement.target().getBlock(), 1, Integer::sum);
                continue;
            }
            itemsInWater(requirement.items(), placement.target(), inWaterSource.contains(placement.pos()))
                    .forEach(stack -> items.add(stack, stack.getCount()));
            if (requirement.toolUse() != null) {
                toolUses.merge(requirement.toolUse(), 1, Integer::sum);
            }
        }
        for (Placement ignored : toWaterlog) {
            items.add(new ItemStack(Items.WATER_BUCKET), 1);
        }
        for (Placement ignored : toDrain) {
            items.add(WaterRules.emptyBucket(), 1);
        }
        return new Requirements(items, toolUses, unobtainable);
    }

    public static List<ItemStack> itemsInWater(List<ItemStack> items, BlockState target, boolean inWaterSource) {
        if (!inWaterSource || target.getBlock() instanceof LiquidBlock) {
            return items;
        }
        if (WaterRules.holdsFluid(target) && target.getFluidState().getType().isSame(Fluids.WATER)) {
            List<ItemStack> result = new ArrayList<>(items);
            for (int i = result.size() - 1; i >= 0; i--) {
                if (ItemMatch.bucketFluid(result.get(i)) != null) {
                    result.remove(i);
                    break;
                }
            }
            return result;
        }
        if (target.hasProperty(BlockStateProperties.WATERLOGGED)) {
            List<ItemStack> result = new ArrayList<>(items);
            result.add(WaterRules.emptyBucket());
            return result;
        }
        return items;
    }

    public Map<BlockPos, BlockState> targetsByPosition() {
        Map<BlockPos, BlockState> targets = new HashMap<>();
        for (Placement placement : toPlace) {
            targets.put(placement.pos(), placement.target());
        }
        for (Placement placement : flowingTargets) {
            targets.put(placement.pos(), placement.target());
        }
        return targets;
    }

    public Function<BlockPos, BlockState> getSchematicStates(ServerLevel level) {
        return schematicStates != null ? schematicStates : level::getBlockState;
    }

    public Map<Block, Integer> getUnplaceable() {
        return unplaceable;
    }

    public BoundingBox getArea() {
        return area;
    }

    public List<BlockPos> getToClear() {
        return toClear;
    }

    public List<Placement> getToPlace() {
        return toPlace;
    }

    public int getPlaceCount() {
        int count = 0;
        for (Placement placement : toPlace) {
            if (!PlacingWork.placedWithOtherPart(placement.target())) {
                count++;
            }
        }
        return count;
    }

    public int getAdjustCount() {
        int count = toFix.size() + toWaterlog.size() + toDrain.size();
        for (Placement placement : toPlace) {
            BlockState target = placement.target();
            if (!(target.getBlock() instanceof LiquidBlock) && !PlacingWork.placedWithOtherPart(target)
                    && WaterRules.isWaterlogged(target) && !inWaterSource.contains(placement.pos())) {
                count++;
            }
        }
        return count;
    }

    public List<Placement> getToFix() {
        return toFix;
    }

    public List<Placement> getToWaterlog() {
        return toWaterlog;
    }

    public boolean isInWaterSource(BlockPos pos) {
        return inWaterSource.contains(pos);
    }

    public List<Placement> getToDrain() {
        return toDrain;
    }

    public List<BlockPos> getFluidsInTheWay() {
        return fluidsInTheWay;
    }

    public List<BlockPos> getFlowingInTheWay() {
        return flowingInTheWay;
    }

    public int getDoneCount() {
        return doneCount;
    }

    public int getTotalCount() {
        return totalCount;
    }
}
