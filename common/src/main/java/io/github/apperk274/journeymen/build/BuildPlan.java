package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class BuildPlan {

    public record Placement(BlockPos pos, BlockState target, @Nullable CompoundTag blockEntity) {
    }

    private final BoundingBox area;
    private final List<BlockPos> toClear = new ArrayList<>();
    private final List<Placement> toPlace = new ArrayList<>();
    private final List<Placement> toFix = new ArrayList<>();
    private final List<Placement> toWaterlog = new ArrayList<>();
    private final List<BlockPos> fluidsInTheWay = new ArrayList<>();
    private int doneCount;
    private int totalCount;

    private BuildPlan(BoundingBox area) {
        this.area = area;
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

    public static BuildPlan create(ServerLevel level, Schematic schematic, PlacementTransform transform) {
        BuildPlan plan = new BuildPlan(transform.area(schematic.getSize()));
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
        if (StateRules.matches(target, actual)) {
            toWaterlog.add(new Placement(pos, target, blockEntity));
            return;
        }
        if (StateRules.fixableByHand(target, actual)) {
            toFix.add(new Placement(pos, target, blockEntity));
            return;
        }
        if (actual.getBlock() instanceof LiquidBlock) {
            fluidsInTheWay.add(pos);
        } else if (!actual.isAir() && !actual.canBeReplaced()) {
            toClear.add(pos);
        }
        toPlace.add(new Placement(pos, target, blockEntity));
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
