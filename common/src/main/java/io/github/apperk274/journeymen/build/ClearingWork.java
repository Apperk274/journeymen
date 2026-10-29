package io.github.apperk274.journeymen.build;

import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Predicate;

public final class ClearingWork {

    private static final int BUCKET_SHIFT = 3;

    final TreeMap<Integer, Layer> remaining = new TreeMap<>(Comparator.reverseOrder());
    private final Long2ObjectOpenHashMap<IntArrayList> columns = new Long2ObjectOpenHashMap<>();
    final Map<BlockPos, BlockState> targets;
    final Map<BlockState, Integer> requiredRemaining = new HashMap<>();
    private final Map<BlockPos, BlockState> planned = new HashMap<>();
    private final Map<BlockRequirements.ToolUse, Integer> toolUses;
    private final Map<BlockState, Integer> remainingByState = new LinkedHashMap<>();
    private final Map<BlockState, BlockPos> examples = new HashMap<>();
    final List<BlockPos> skipped = new ArrayList<>();
    final Map<BlockPos, Long> fluidSince = new HashMap<>();
    private final int total;
    private int count;
    int cleared;

    static final class Layer {

        private final Set<BlockPos> positions = new LinkedHashSet<>();
        private final Long2ObjectOpenHashMap<Set<BlockPos>> buckets = new Long2ObjectOpenHashMap<>();
        private int minBucketX = Integer.MAX_VALUE;
        private int minBucketZ = Integer.MAX_VALUE;
        private int maxBucketX = Integer.MIN_VALUE;
        private int maxBucketZ = Integer.MIN_VALUE;

        int size() {
            return positions.size();
        }

        private void add(BlockPos pos) {
            if (!positions.add(pos)) {
                return;
            }
            int bucketX = pos.getX() >> BUCKET_SHIFT;
            int bucketZ = pos.getZ() >> BUCKET_SHIFT;
            buckets.computeIfAbsent(ChunkPos.asLong(bucketX, bucketZ), key -> new LinkedHashSet<>()).add(pos);
            minBucketX = Math.min(minBucketX, bucketX);
            minBucketZ = Math.min(minBucketZ, bucketZ);
            maxBucketX = Math.max(maxBucketX, bucketX);
            maxBucketZ = Math.max(maxBucketZ, bucketZ);
        }

        private boolean remove(BlockPos pos) {
            if (!positions.remove(pos)) {
                return false;
            }
            long key = ChunkPos.asLong(pos.getX() >> BUCKET_SHIFT, pos.getZ() >> BUCKET_SHIFT);
            Set<BlockPos> bucket = buckets.get(key);
            if (bucket != null) {
                bucket.remove(pos);
                if (bucket.isEmpty()) {
                    buckets.remove(key);
                }
            }
            return true;
        }

        List<BlockPos> nearest(BlockPos origin, int limit, Predicate<BlockPos> eligible) {
            List<BlockPos> found = new ArrayList<>();
            if (positions.isEmpty()) {
                return found;
            }
            int originX = origin.getX() >> BUCKET_SHIFT;
            int originZ = origin.getZ() >> BUCKET_SHIFT;
            int maxRing = Math.max(Math.max(Math.abs(originX - minBucketX), Math.abs(originX - maxBucketX)),
                    Math.max(Math.abs(originZ - minBucketZ), Math.abs(originZ - maxBucketZ)));
            int lastRing = maxRing;
            for (int ring = 0; ring <= lastRing; ring++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    int step = Math.abs(dx) == ring ? 1 : 2 * ring;
                    for (int dz = -ring; dz <= ring; dz += step) {
                        Set<BlockPos> bucket = buckets.get(ChunkPos.asLong(originX + dx, originZ + dz));
                        if (bucket == null) {
                            continue;
                        }
                        for (BlockPos pos : bucket) {
                            if (eligible.test(pos)) {
                                found.add(pos);
                            }
                        }
                    }
                }
                if (found.size() >= limit && lastRing == maxRing) {
                    lastRing = Math.min(maxRing, ring + 1);
                }
            }
            if (found.size() > limit) {
                found.sort(Comparator.comparingDouble(pos -> pos.distToCenterSqr(origin.getX() + 0.5, pos.getY() + 0.5, origin.getZ() + 0.5)));
                return new ArrayList<>(found.subList(0, limit));
            }
            return found;
        }
    }

    public ClearingWork(ServerLevel level, List<BlockPos> toClear, List<BlockPos> fluids, Map<BlockPos, BlockState> targets,
                        Map<BlockRequirements.ToolUse, Integer> toolUses) {
        this.targets = targets;
        this.toolUses = new EnumMap<>(BlockRequirements.ToolUse.class);
        this.toolUses.putAll(toolUses);
        for (BlockPos pos : fluids) {
            add(pos);
        }
        for (BlockPos pos : toClear) {
            add(pos);
            BlockState state = level.getBlockState(pos);
            if (planned.putIfAbsent(pos.immutable(), state) != null) {
                continue;
            }
            remainingByState.merge(state, 1, Integer::sum);
            examples.putIfAbsent(state, pos.immutable());
            if (ToolChoice.needsTool(state, level, pos)) {
                requiredRemaining.merge(state, 1, Integer::sum);
            }
        }
        this.total = toClear.size();
    }

    private void add(BlockPos pos) {
        Layer layer = remaining.computeIfAbsent(pos.getY(), y -> new Layer());
        int before = layer.size();
        layer.add(pos.immutable());
        if (layer.size() == before) {
            return;
        }
        count++;
        IntArrayList column = columns.computeIfAbsent(ChunkPos.asLong(pos.getX(), pos.getZ()), key -> new IntArrayList());
        int index = 0;
        while (index < column.size() && column.getInt(index) > pos.getY()) {
            index++;
        }
        column.add(index, pos.getY());
    }

    void remove(BlockPos pos) {
        Layer layer = remaining.get(pos.getY());
        if (layer == null || !layer.remove(pos)) {
            return;
        }
        count--;
        BlockState state = planned.remove(pos);
        if (state != null) {
            remainingByState.computeIfPresent(state, (key, left) -> left > 1 ? left - 1 : null);
            requiredRemaining.computeIfPresent(state, (key, left) -> left > 1 ? left - 1 : null);
        }
        if (layer.size() == 0) {
            remaining.remove(pos.getY());
        }
        long key = ChunkPos.asLong(pos.getX(), pos.getZ());
        IntArrayList column = columns.get(key);
        if (column != null) {
            column.rem(pos.getY());
            if (column.isEmpty()) {
                columns.remove(key);
            }
        }
    }

    boolean isColumnTop(BlockPos pos) {
        IntArrayList column = columns.get(ChunkPos.asLong(pos.getX(), pos.getZ()));
        return column != null && !column.isEmpty() && column.getInt(0) == pos.getY();
    }

    public Map<BlockState, Integer> remainingByState() {
        return remainingByState;
    }

    public Map<BlockRequirements.ToolUse, Integer> toolUses() {
        return toolUses;
    }

    public int countedLeft() {
        return remainingByState.values().stream().mapToInt(Integer::intValue).sum();
    }

    public int requiredLeft() {
        return requiredRemaining.values().stream().mapToInt(Integer::intValue).sum();
    }

    public BlockPos example(BlockState state) {
        return examples.get(state);
    }

    boolean contains(BlockPos pos) {
        Layer layer = remaining.get(pos.getY());
        return layer != null && layer.positions.contains(pos);
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
        return count;
    }

    public List<BlockPos> getSkipped() {
        return skipped;
    }
}
