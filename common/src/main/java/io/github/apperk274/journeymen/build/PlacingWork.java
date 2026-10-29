package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.mixin.FarmBlockAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.TntBlock;
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
import java.util.function.Function;
import java.util.function.Predicate;

public final class PlacingWork {

    enum Kind {
        PLACE,
        FIX,
        FLUID,
        WATERLOG,
        DRAIN
    }

    record Entry(BuildPlan.Placement placement, Kind kind, int key, boolean withWater) {

        Entry(BuildPlan.Placement placement, Kind kind) {
            this(placement, kind, placement.pos().getY(), false);
        }

        Entry(BuildPlan.Placement placement, Kind kind, int key) {
            this(placement, kind, key, false);
        }

        BlockPos pos() {
            return placement.pos();
        }

        int weight() {
            return withWater && WaterRules.isWaterlogged(placement.target()) ? 2 : 1;
        }
    }

    private static final int LAST_KEY = 1 << 24;

    final TreeMap<Integer, List<Entry>> supports = new TreeMap<>();
    final TreeMap<Integer, List<Entry>> ramp = new TreeMap<>();
    final TreeMap<Integer, List<Entry>> dependents = new TreeMap<>();
    final TreeMap<Integer, List<Entry>> fluids = new TreeMap<>();
    final List<BlockPos> skipped = new ArrayList<>();
    final Set<BlockPos> waitsForWater = new HashSet<>();
    private final ServerLevel level;
    private final int total;
    int placed;
    private long settleUntil;

    public PlacingWork(ServerLevel level, BuildPlan plan, Predicate<BlockPos> include, @Nullable BlockPos rampStart) {
        this.level = level;
        Map<Long, int[]> rampColumns = AccessRamp.columns(level, plan, rampStart);
        Function<BlockPos, BlockState> states = plan.getSchematicStates(level);
        Function<BlockPos, BlockState> drained = at -> WaterRules.drained(states.apply(at));
        Set<BlockPos> hanging = new HashSet<>();
        int count = 0;
        for (BuildPlan.Placement placement : plan.getToPlace()) {
            if (!include.test(placement.pos()) || placedWithOtherPart(placement.target())) {
                continue;
            }
            BlockState target = placement.target();
            BlockPos pos = placement.pos();
            if (target.getBlock() instanceof LiquidBlock) {
                if (target.getFluidState().isSource()) {
                    add(fluids, new Entry(placement, Kind.FLUID));
                    count++;
                }
                continue;
            }
            BlockState placeState = StateRules.forPlacement(target);
            boolean alone = survivesAlone(pos, placeState);
            boolean needsWater = !alone && SandboxLevel.survivesAmong(level, pos, placeState, states)
                    && !SandboxLevel.survivesAmong(level, pos, placeState, drained);
            boolean ownWater = WaterRules.holdsFluid(target)
                    && (!target.hasProperty(BlockStateProperties.WATERLOGGED) || WaterRules.diesWithoutWater(target.getBlock()) || needsWater);
            boolean waterAround = !ownWater && (needsWater
                    || WaterRules.diesWithoutWater(target.getBlock()) && WaterRules.touchesWater(states, pos));
            boolean dries = driesBeforeItsWater(pos, placeState, states);
            if (dries) {
                waitsForWater.add(pos);
            }
            int[] rampStep = rampColumns.get(ChunkPos.asLong(pos.getX(), pos.getZ()));
            Entry entry = null;
            if (ownWater) {
                entry = new Entry(placement, Kind.PLACE, pos.getY(), !plan.isInWaterSource(pos));
            } else if (placeState.getBlock() instanceof TntBlock) {
                entry = new Entry(placement, Kind.PLACE, LAST_KEY + pos.getY());
            } else if (waterAround || dries || hangsOnSomething(pos, placeState, alone, hanging, states)) {
                entry = new Entry(placement, Kind.PLACE);
            } else if (rampStep != null && pos.getY() >= rampStep[1]) {
                add(ramp, new Entry(placement, Kind.PLACE, -rampStep[0] * AccessRamp.ORDER_STRIDE + pos.getY()));
                count++;
            } else {
                add(supports, new Entry(placement, Kind.PLACE));
                count++;
            }
            if (entry != null) {
                hanging.add(pos);
                add(dependents, entry);
                count += entry.weight();
            }
            if (WaterRules.isWaterlogged(target) && (entry == null || !entry.withWater()) && !plan.isInWaterSource(pos)) {
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
        for (BuildPlan.Placement placement : plan.getToDrain()) {
            if (include.test(placement.pos())) {
                add(fluids, new Entry(placement, Kind.DRAIN));
                count++;
            }
        }
        this.total = count;
    }

    private boolean driesBeforeItsWater(BlockPos pos, BlockState placeState, Function<BlockPos, BlockState> states) {
        return placeState.getBlock() instanceof FarmBlock && !states.apply(pos.above()).is(BlockTags.MAINTAINS_FARMLAND)
                && !FarmBlockAccessor.journeymen$isNearWater(level, pos)
                && FarmBlockAccessor.journeymen$isNearWater(SandboxLevel.among(level, states), pos);
    }

    public static boolean placedWithOtherPart(BlockState target) {
        return target.getOptionalValue(BlockStateProperties.DOUBLE_BLOCK_HALF).orElse(null) == DoubleBlockHalf.UPPER
                || target.getOptionalValue(BlockStateProperties.BED_PART).orElse(null) == BedPart.HEAD
                || target.getBlock() instanceof PistonHeadBlock
                || target.getBlock() instanceof MovingPistonBlock;
    }

    private boolean survivesAlone(BlockPos pos, BlockState placeState) {
        return SandboxLevel.survivesAlone(level, pos, placeState)
                && (hasTwoParts(placeState) || !SandboxLevel.shapedAlone(level, pos, placeState).isAir());
    }

    private static boolean hangsOnSomething(BlockPos pos, BlockState placeState, boolean alone, Set<BlockPos> hanging,
                                            Function<BlockPos, BlockState> states) {
        if (placeState.getBlock() instanceof FallingBlock
                && (hanging.contains(pos.below()) || FallingBlock.isFree(states.apply(pos.below())))) {
            return true;
        }
        return !alone;
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

    public long getSettleUntil() {
        return settleUntil;
    }

    public void settleFor(long until) {
        settleUntil = until;
    }

    public int getPlaced() {
        return placed;
    }

    public int getRemaining() {
        int count = 0;
        for (TreeMap<Integer, List<Entry>> layers : groups()) {
            for (List<Entry> layer : layers.values()) {
                for (Entry entry : layer) {
                    count += entry.weight();
                }
            }
        }
        return count;
    }

    public List<BlockPos> getSkipped() {
        return skipped;
    }

    public Set<BlockPos> pendingPositions() {
        Set<BlockPos> pending = new HashSet<>();
        for (TreeMap<Integer, List<Entry>> layers : groups()) {
            for (List<Entry> layer : layers.values()) {
                for (Entry entry : layer) {
                    pending.add(entry.pos());
                }
            }
        }
        return pending;
    }
}
