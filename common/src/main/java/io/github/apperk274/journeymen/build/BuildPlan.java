package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final List<BlockPos> fluidsInTheWay = new ArrayList<>();
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
                    plan.classify(pos, target, actual, schematic.getBlockEntity(new BlockPos(x, y, z)));
                }
            }
        }
        return plan;
    }

    private void classify(BlockPos pos, BlockState target, BlockState actual, @Nullable CompoundTag blockEntity) {
        if (target.getBlock() instanceof LiquidBlock && !target.getFluidState().isSource()) {
            doneCount++;
            return;
        }
        if (target.isAir()) {
            if (actual.isAir()) {
                doneCount++;
            } else if (actual.getBlock() instanceof LiquidBlock) {
                fluidsInTheWay.add(pos);
            } else {
                toClear.add(pos);
            }
            return;
        }
        if (StateRules.matchesIncludingWater(target, actual)) {
            doneCount++;
            return;
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
            toWaterlog.add(new Placement(pos, target, blockEntity));
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
        if (actual.getBlock() instanceof LiquidBlock) {
            fluidsInTheWay.add(pos);
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
            requirement.items().forEach(stack -> items.add(stack, stack.getCount()));
            if (requirement.toolUse() != null) {
                toolUses.merge(requirement.toolUse(), 1, Integer::sum);
            }
        }
        for (Placement ignored : toWaterlog) {
            items.add(new ItemStack(Items.WATER_BUCKET), 1);
        }
        return new Requirements(items, toolUses, unobtainable);
    }

    public Map<BlockPos, BlockState> targetsByPosition() {
        Map<BlockPos, BlockState> targets = new HashMap<>();
        for (Placement placement : toPlace) {
            targets.put(placement.pos(), placement.target());
        }
        return targets;
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

    public List<Placement> getToFix() {
        return toFix;
    }

    public List<Placement> getToWaterlog() {
        return toWaterlog;
    }

    public List<BlockPos> getFluidsInTheWay() {
        return fluidsInTheWay;
    }

    public int getDoneCount() {
        return doneCount;
    }

    public int getTotalCount() {
        return totalCount;
    }
}
