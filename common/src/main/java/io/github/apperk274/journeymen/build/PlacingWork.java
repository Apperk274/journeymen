package io.github.apperk274.journeymen.build;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.piston.MovingPistonBlock;
import net.minecraft.world.level.block.piston.PistonHeadBlock;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

public final class PlacingWork {

    enum Kind {
        PLACE,
        FIX,
        FLUID,
        WATERLOG
    }

    record Entry(BuildPlan.Placement placement, Kind kind, int key) {

        Entry(BuildPlan.Placement placement, Kind kind) {
            this(placement, kind, placement.pos().getY());
        }

        BlockPos pos() {
            return placement.pos();
        }
    }

    final TreeMap<Integer, List<Entry>> supports = new TreeMap<>();
    final TreeMap<Integer, List<Entry>> ramp = new TreeMap<>();
    final TreeMap<Integer, List<Entry>> dependents = new TreeMap<>();
    final TreeMap<Integer, List<Entry>> fluids = new TreeMap<>();
    final List<BlockPos> skipped = new ArrayList<>();
    private final ServerLevel level;
    private final int total;
    int placed;

    public PlacingWork(ServerLevel level, BuildPlan plan, Predicate<BlockPos> include, @Nullable BlockPos rampStart) {
        this.level = level;
        Map<Long, int[]> rampColumns = AccessRamp.columns(level, plan, rampStart);
        Set<BlockPos> hanging = new HashSet<>();
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
            int[] rampStep = rampColumns.get(ChunkPos.asLong(placement.pos().getX(), placement.pos().getZ()));
            if (hangsOnSomething(placement.pos(), target, hanging)) {
                hanging.add(placement.pos());
                add(dependents, new Entry(placement, Kind.PLACE));
            } else if (rampStep != null && placement.pos().getY() >= rampStep[1]) {
                add(ramp, new Entry(placement, Kind.PLACE, -rampStep[0] * AccessRamp.ORDER_STRIDE + placement.pos().getY()));
            } else {
                add(supports, new Entry(placement, Kind.PLACE));
            }
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

    static boolean placedWithOtherPart(BlockState target) {
        return target.getOptionalValue(BlockStateProperties.DOUBLE_BLOCK_HALF).orElse(null) == DoubleBlockHalf.UPPER
                || target.getOptionalValue(BlockStateProperties.BED_PART).orElse(null) == BedPart.HEAD
                || target.getBlock() instanceof PistonHeadBlock
                || target.getBlock() instanceof MovingPistonBlock;
    }

    private boolean hangsOnSomething(BlockPos pos, BlockState target, Set<BlockPos> hanging) {
        BlockState placeState = StateRules.forPlacement(target);
        if (placeState.getBlock() instanceof FallingBlock && hanging.contains(pos.below())) {
            return true;
        }
        return !SandboxLevel.survivesAlone(level, pos, placeState)
                || !hasTwoParts(placeState) && SandboxLevel.shapedAlone(level, pos, placeState).isAir();
    }

    static boolean hasTwoParts(BlockState state) {
        return state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) || state.hasProperty(BlockStateProperties.BED_PART);
    }

    private static void add(TreeMap<Integer, List<Entry>> layers, Entry entry) {
        layers.computeIfAbsent(entry.key(), key -> new ArrayList<>()).add(entry);
    }

    List<TreeMap<Integer, List<Entry>>> groups() {
        return List.of(supports, ramp, dependents, fluids);
    }

    static void remove(TreeMap<Integer, List<Entry>> group, Entry entry) {
        List<Entry> layer = group.get(entry.key());
        if (layer != null) {
            layer.remove(entry);
            if (layer.isEmpty()) {
                group.remove(entry.key());
            }
        }
    }

    @Nullable
    TreeMap<Integer, List<Entry>> groupOf(Entry entry) {
        for (TreeMap<Integer, List<Entry>> group : groups()) {
            List<Entry> layer = group.get(entry.key());
            if (layer != null && layer.contains(entry)) {
                return group;
            }
        }
        return null;
    }

    public boolean isEmpty() {
        return supports.isEmpty() && ramp.isEmpty() && dependents.isEmpty() && fluids.isEmpty();
    }

    public int getTotal() {
        return total;
    }

    public int getPlaced() {
        return placed;
    }

    public int getRemaining() {
        int count = 0;
        for (TreeMap<Integer, List<Entry>> layers : groups()) {
            for (List<Entry> layer : layers.values()) {
                count += layer.size();
            }
        }
        return count;
    }

    public List<BlockPos> getSkipped() {
        return skipped;
    }
}
