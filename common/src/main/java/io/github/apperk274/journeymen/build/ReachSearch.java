package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.ai.BuilderNodeEvaluator;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.pathfinder.BinaryHeap;
import net.minecraft.world.level.pathfinder.Node;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

public final class ReachSearch {

    private static final int MAX_NODES = 8000;
    private static final int MARGIN = 6;
    private static final int DROP_MARGIN = 8;
    private static final double STAND_SLACK = 0.5;
    private static final float CLOSER_STEPS = 3.0F;

    public enum Outcome {
        FOUND,
        NONE,
        UNKNOWN
    }

    public record Result(Outcome outcome, int index, @Nullable BlockPos stand) {
    }

    private static final Result NONE = new Result(Outcome.NONE, -1, null);
    private static final Result UNKNOWN = new Result(Outcome.UNKNOWN, -1, null);

    private ReachSearch() {
    }

    public static boolean avoided(Predicate<BlockPos> avoid, BlockPos feet) {
        return avoid != Reach.AVOID_NOTHING && (avoid.test(feet) || avoid.test(feet.above()));
    }

    public static Result find(BuilderEntity builder, List<BlockPos> targets, BoundingBox area, Predicate<BlockPos> avoid) {
        if (targets.isEmpty()) {
            return NONE;
        }
        BlockPos start = builder.blockPosition();
        BoundingBox box = new BoundingBox(
                Math.min(area.minX(), start.getX()) - MARGIN, Math.min(area.minY(), start.getY()) - DROP_MARGIN,
                Math.min(area.minZ(), start.getZ()) - MARGIN, Math.max(area.maxX(), start.getX()) + MARGIN,
                Math.max(area.maxY(), start.getY()) + 2, Math.max(area.maxZ(), start.getZ()) + MARGIN);
        int[] goals = coverage(targets, box);
        PathNavigationRegion region = new PathNavigationRegion(builder.level(),
                new BlockPos(box.minX() - 2, box.minY() - 2, box.minZ() - 2), new BlockPos(box.maxX() + 2, box.maxY() + 2, box.maxZ() + 2));
        BuilderNodeEvaluator evaluator = new BuilderNodeEvaluator();
        evaluator.setCanPassDoors(true);
        evaluator.setCanOpenDoors(true);
        evaluator.setCanFloat(true);
        evaluator.prepare(region, builder);
        try {
            Node first = evaluator.getStart();
            if (first == null) {
                return UNKNOWN;
            }
            BinaryHeap open = new BinaryHeap();
            first.g = 0.0F;
            first.f = 0.0F;
            open.insert(first);
            Node[] neighbors = new Node[32];
            int visited = 0;
            int goal = -1;
            Node stand = null;
            double standDistance = Double.MAX_VALUE;
            float goalCost = 0.0F;
            while (!open.isEmpty()) {
                if (++visited > MAX_NODES) {
                    break;
                }
                Node node = open.pop();
                node.closed = true;
                boolean free = !avoided(avoid, node.asBlockPos());
                if (goal < 0) {
                    if (free) {
                        goal = goalAt(goals, box, node.x, node.z);
                        goalCost = node.g;
                    }
                } else if (node.g > goalCost + CLOSER_STEPS) {
                    break;
                }
                if (goal >= 0 && free) {
                    BlockPos target = targets.get(goal);
                    double distance = Mth.square(target.getX() - node.x) + Mth.square(target.getZ() - node.z);
                    if (distance < standDistance) {
                        stand = node;
                        standDistance = distance;
                    }
                }
                if (!box.isInside(node.x, node.y, node.z)) {
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
                            open.changeCost(next, cost);
                        }
                    } else if (!next.closed) {
                        next.cameFrom = node;
                        next.g = cost;
                        next.f = cost;
                        open.insert(next);
                    }
                }
            }
            if (stand != null) {
                return new Result(Outcome.FOUND, goal, stand.asBlockPos());
            }
            return open.isEmpty() ? NONE : UNKNOWN;
        } finally {
            evaluator.done();
        }
    }

    private static int[] coverage(List<BlockPos> targets, BoundingBox box) {
        int sizeX = box.getXSpan();
        int sizeZ = box.getZSpan();
        int[] goals = new int[sizeX * sizeZ];
        double[] distances = new double[goals.length];
        Arrays.fill(goals, -1);
        Arrays.fill(distances, Double.MAX_VALUE);
        int radius = (int) Math.floor(Reach.HORIZONTAL);
        double limit = (Reach.HORIZONTAL - STAND_SLACK) * (Reach.HORIZONTAL - STAND_SLACK);
        for (int i = 0; i < targets.size(); i++) {
            BlockPos target = targets.get(i);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    double distance = dx * dx + dz * dz;
                    int x = target.getX() + dx - box.minX();
                    int z = target.getZ() + dz - box.minZ();
                    if (distance > limit || x < 0 || z < 0 || x >= sizeX || z >= sizeZ) {
                        continue;
                    }
                    int index = x * sizeZ + z;
                    if (distance < distances[index]) {
                        distances[index] = distance;
                        goals[index] = i;
                    }
                }
            }
        }
        return goals;
    }

    private static int goalAt(int[] goals, BoundingBox box, int x, int z) {
        int localX = x - box.minX();
        int localZ = z - box.minZ();
        int sizeZ = box.getZSpan();
        if (localX < 0 || localZ < 0 || localX >= box.getXSpan() || localZ >= sizeZ) {
            return -1;
        }
        return goals[localX * sizeZ + localZ];
    }
}
