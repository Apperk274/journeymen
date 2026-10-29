package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.entity.JourneymanEntity;
import io.github.apperk274.journeymen.entity.ai.BuilderNodeEvaluator;
import it.unimi.dsi.fastutil.longs.Long2BooleanOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.pathfinder.BinaryHeap;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import org.jetbrains.annotations.Nullable;

public final class PostSearch {

    private static final int MAX_NODES = 16000;
    private static final int MARGIN = 10;
    private static final int LEVELS = 8;
    private static final int SITE_LEVEL_SLACK = 2;

    private PostSearch() {
    }

    @Nullable
    public static BlockPos find(JourneymanEntity mob, BoundingBox area, double maxDistance) {
        Level level = mob.level();
        BlockPos from = mob.blockPosition();
        BoundingBox box = new BoundingBox(
                Math.min(area.minX(), from.getX()) - MARGIN, Math.max(level.getMinBuildHeight(), Math.min(area.minY(), from.getY()) - LEVELS),
                Math.min(area.minZ(), from.getZ()) - MARGIN, Math.max(area.maxX(), from.getX()) + MARGIN,
                Math.min(level.getMaxBuildHeight() - 1, Math.max(area.maxY(), from.getY()) + LEVELS), Math.max(area.maxZ(), from.getZ()) + MARGIN);
        PathNavigationRegion region = new PathNavigationRegion(level,
                new BlockPos(box.minX() - 1, box.minY(), box.minZ() - 1), new BlockPos(box.maxX() + 1, box.maxY(), box.maxZ() + 1));
        BuilderNodeEvaluator evaluator = new BuilderNodeEvaluator();
        evaluator.setCanPassDoors(true);
        evaluator.setCanOpenDoors(true);
        evaluator.setCanFloat(true);
        evaluator.prepare(region, mob);
        Long2BooleanOpenHashMap loaded = new Long2BooleanOpenHashMap();
        BlockPos fallback = null;
        try {
            Node first = evaluator.getStart();
            if (first == null) {
                return null;
            }
            BinaryHeap open = new BinaryHeap();
            first.g = 0.0F;
            first.f = 0.0F;
            open.insert(first);
            Node[] neighbors = new Node[32];
            int visited = 0;
            while (!open.isEmpty() && visited++ < MAX_NODES) {
                Node node = open.pop();
                node.closed = true;
                BlockPos pos = node.asBlockPos();
                double distance = PreCheck.horizontalDistance(pos, area);
                if (distance >= 1.0 && distance <= maxDistance && node.type == PathType.WALKABLE
                        && !(level.getBlockState(pos.below()).getBlock() instanceof LeavesBlock) && !mob.isInJobArea(pos)) {
                    if (pos.getY() >= area.minY() - SITE_LEVEL_SLACK && pos.getY() <= area.maxY() + SITE_LEVEL_SLACK) {
                        return pos;
                    }
                    if (fallback == null) {
                        fallback = pos;
                    }
                }
                if (!box.isInside(node.x, node.y, node.z) || !loaded(level, loaded, node.x, node.z)) {
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
            return fallback;
        } finally {
            evaluator.done();
        }
    }

    private static boolean loaded(Level level, Long2BooleanOpenHashMap loaded, int x, int z) {
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
