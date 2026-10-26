package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.entity.ai.BuilderNodeEvaluator;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.pathfinder.BinaryHeap;
import net.minecraft.world.level.pathfinder.Node;

public final class SitePath {

    public static final int MAX_NODES = 16000;
    private static final int MARGIN = 32;

    public enum Outcome {
        FOUND,
        NONE,
        UNKNOWN
    }

    public record Result(Outcome outcome, int visited, long nanos) {
    }

    private SitePath() {
    }

    public static Result find(Mob mob, BoundingBox area, double within) {
        long started = System.nanoTime();
        BlockPos from = mob.blockPosition();
        if (PreCheck.horizontalDistance(from, area) <= within) {
            return new Result(Outcome.FOUND, 0, System.nanoTime() - started);
        }
        Level level = mob.level();
        BoundingBox box = new BoundingBox(
                Math.min(area.minX(), from.getX()) - MARGIN, level.getMinBuildHeight(), Math.min(area.minZ(), from.getZ()) - MARGIN,
                Math.max(area.maxX(), from.getX()) + MARGIN, level.getMaxBuildHeight() - 1, Math.max(area.maxZ(), from.getZ()) + MARGIN);
        PathNavigationRegion region = new PathNavigationRegion(level,
                new BlockPos(box.minX() - 1, box.minY(), box.minZ() - 1), new BlockPos(box.maxX() + 1, box.maxY(), box.maxZ() + 1));
        BuilderNodeEvaluator evaluator = new BuilderNodeEvaluator();
        evaluator.setCanPassDoors(true);
        evaluator.setCanOpenDoors(true);
        evaluator.setCanFloat(true);
        evaluator.prepare(region, mob);
        Long2BooleanOpenHashMap loaded = new Long2BooleanOpenHashMap();
        int visited = 0;
        boolean bounded = false;
        try {
            Node first = evaluator.getStart();
            if (first == null) {
                return new Result(Outcome.UNKNOWN, 0, System.nanoTime() - started);
            }
            BinaryHeap open = new BinaryHeap();
            first.g = 0.0F;
            first.h = remaining(first, area, within);
            first.f = first.h;
            open.insert(first);
            Node[] neighbors = new Node[32];
            while (!open.isEmpty()) {
                if (visited >= MAX_NODES) {
                    return new Result(Outcome.UNKNOWN, visited, System.nanoTime() - started);
                }
                visited++;
                Node node = open.pop();
                node.closed = true;
                if (PreCheck.horizontalDistance(node.asBlockPos(), area) <= within) {
                    return new Result(Outcome.FOUND, visited, System.nanoTime() - started);
                }
                if (!known(level, box, loaded, node.x, node.z)) {
                    bounded = true;
                    continue;
                }
                int count = evaluator.getNeighbors(neighbors, node);
                for (int i = 0; i < count; i++) {
                    Node next = neighbors[i];
                    float cost = node.g + node.distanceTo(next) + next.costMalus;
                    if (next.inOpenSet()) {
                        if (cost < next.g) {
                            next.cameFrom = node;
                            next.g = cost;
                            open.changeCost(next, cost + next.h);
                        }
                    } else if (!next.closed) {
                        next.cameFrom = node;
                        next.g = cost;
                        next.h = remaining(next, area, within);
                        next.f = cost + next.h;
                        open.insert(next);
                    }
                }
            }
            return new Result(bounded ? Outcome.UNKNOWN : Outcome.NONE, visited, System.nanoTime() - started);
        } finally {
            evaluator.done();
        }
    }

    private static float remaining(Node node, BoundingBox area, double within) {
        return (float) Math.max(0.0, PreCheck.horizontalDistance(node.asBlockPos(), area) - within);
    }

    private static boolean known(Level level, BoundingBox box, Long2BooleanOpenHashMap loaded, int x, int z) {
        if (x - 1 < box.minX() || x + 1 > box.maxX() || z - 1 < box.minZ() || z + 1 > box.maxZ()) {
            return false;
        }
        for (int cx = (x - 1) >> 4; cx <= (x + 1) >> 4; cx++) {
            for (int cz = (z - 1) >> 4; cz <= (z + 1) >> 4; cz++) {
                int chunkX = cx;
                int chunkZ = cz;
                if (!loaded.computeIfAbsent(ChunkPos.asLong(cx, cz), key -> level.hasChunk(chunkX, chunkZ))) {
                    return false;
                }
            }
        }
        return true;
    }
}
