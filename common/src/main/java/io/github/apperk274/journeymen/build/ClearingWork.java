package io.github.apperk274.journeymen.build;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

public final class ClearingWork {

    final TreeMap<Integer, List<BlockPos>> remaining = new TreeMap<>(Comparator.reverseOrder());
    final Map<BlockPos, BlockState> targets;
    final Map<BlockState, Integer> requiredRemaining = new HashMap<>();
    final Set<BlockState> statesToClear = new HashSet<>();
    final List<BlockPos> skipped = new ArrayList<>();
    private final int total;
    int cleared;

    public ClearingWork(ServerLevel level, List<BlockPos> toClear, List<BlockPos> fluids, Map<BlockPos, BlockState> targets) {
        this.targets = targets;
        for (BlockPos pos : fluids) {
            remaining.computeIfAbsent(pos.getY(), y -> new ArrayList<>()).add(pos);
        }
        for (BlockPos pos : toClear) {
            remaining.computeIfAbsent(pos.getY(), y -> new ArrayList<>()).add(pos);
            BlockState state = level.getBlockState(pos);
            statesToClear.add(state);
            if (ToolChoice.needsTool(state, level, pos)) {
                requiredRemaining.merge(state, 1, Integer::sum);
            }
        }
        this.total = toClear.size();
    }

    void remove(BlockPos pos) {
        List<BlockPos> layer = remaining.get(pos.getY());
        if (layer != null) {
            layer.remove(pos);
            if (layer.isEmpty()) {
                remaining.remove(pos.getY());
            }
        }
    }

    boolean contains(BlockPos pos) {
        List<BlockPos> layer = remaining.get(pos.getY());
        return layer != null && layer.contains(pos);
    }

    public boolean isEmpty() {
        return remaining.isEmpty();
    }

    public int getTotal() {
        return total;
    }

    public int getCleared() {
        return cleared;
    }

    public int getRemaining() {
        int count = 0;
        for (List<BlockPos> layer : remaining.values()) {
            count += layer.size();
        }
        return count;
    }

    public List<BlockPos> getSkipped() {
        return skipped;
    }
}
