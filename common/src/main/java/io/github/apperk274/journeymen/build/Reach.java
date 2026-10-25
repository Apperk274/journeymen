package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.StuckRecovery;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

public final class Reach {

    public static final double HORIZONTAL = 4.5;
    private static final int GIVE_UP_TICKS = 60;
    private static final int REPATH_INTERVAL = 10;
    private static final int STRANDED_RECHECK_TICKS = 100;
    private static final int MAX_STRANDED_RECHECK_TICKS = 600;

    private final BoundingBox area;
    private double bestDistance = Double.MAX_VALUE;
    private int ticksWithoutProgress;
    private int repathTimer;
    private boolean workFromHere;
    private boolean walking;
    private int lastApproach;
    @Nullable
    private BlockPos stand;
    @Nullable
    private BlockPos strandedAt;
    private int strandedTick;
    private int strandedWait = STRANDED_RECHECK_TICKS;

    public Reach(BoundingBox area) {
        this.area = area;
    }

    @Nullable
    public <T> T choose(BuilderEntity builder, List<T> candidates, Function<T, BlockPos> position) {
        T nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (T candidate : candidates) {
            double distance = horizontalDistanceSqr(builder, position.apply(candidate));
            if (distance < nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        if (nearest == null || nearestDistance <= HORIZONTAL * HORIZONTAL) {
            return nearest;
        }
        if (strandedAt != null && builder.blockPosition().closerThan(strandedAt, 2.0)
                && builder.tickCount - strandedTick < strandedWait) {
            workFromHere = true;
            return nearest;
        }
        List<BlockPos> positions = new ArrayList<>(candidates.size());
        candidates.forEach(candidate -> positions.add(position.apply(candidate)));
        ReachSearch.Result result = ReachSearch.find(builder, positions, area);
        switch (result.outcome()) {
            case FOUND -> {
                stand = result.stand();
                return candidates.get(result.index());
            }
            case NONE -> {
                strand(builder);
                workFromHere = true;
                return nearest;
            }
            default -> {
                return nearest;
            }
        }
    }

    public boolean approach(BuilderEntity builder, BlockPos pos) {
        if (workFromHere) {
            return true;
        }
        if (horizontalDistanceSqr(builder, pos) <= HORIZONTAL * HORIZONTAL) {
            if (walking) {
                walking = false;
                strandedAt = null;
            }
            return true;
        }
        if (builder.tickCount - lastApproach > 1) {
            retry();
        }
        lastApproach = builder.tickCount;
        walking = true;
        double distance = remaining(builder, pos);
        if (distance < bestDistance - 0.25) {
            bestDistance = distance;
            ticksWithoutProgress = 0;
        } else if (++ticksWithoutProgress > (stand != null ? StuckRecovery.giveUpTicks(GIVE_UP_TICKS) : GIVE_UP_TICKS)) {
            if (stand != null) {
                stand = null;
                bestDistance = Double.MAX_VALUE;
                ticksWithoutProgress = 0;
                repathTimer = 0;
                return false;
            }
            strand(builder);
            workFromHere = true;
            walking = false;
            builder.getNavigation().stop();
            return true;
        }
        if (--repathTimer <= 0 || builder.getNavigation().isDone()) {
            repathTimer = REPATH_INTERVAL;
            if (stand != null && builder.blockPosition().equals(stand)) {
                builder.getMoveControl().setWantedPosition(stand.getX() + 0.5, stand.getY(), stand.getZ() + 0.5, 1.0);
            } else if (stand != null) {
                builder.getNavigation().moveTo(builder.getNavigation().createPath(stand, 0), 1.0);
            } else {
                builder.walkTowards(new Vec3(pos.getX() + 0.5, Math.min(pos.getY(), builder.getY()), pos.getZ() + 0.5));
            }
        }
        return false;
    }

    private double remaining(BuilderEntity builder, BlockPos pos) {
        Path path = builder.getNavigation().getPath();
        if (stand != null && path != null && !path.isDone()) {
            return path.getNodeCount() - path.getNextNodeIndex();
        }
        return Math.sqrt(horizontalDistanceSqr(builder, stand != null ? stand : pos));
    }

    private void strand(BuilderEntity builder) {
        BlockPos here = builder.blockPosition();
        boolean again = strandedAt != null && here.closerThan(strandedAt, 2.0);
        strandedWait = again ? Math.min(strandedWait * 2, MAX_STRANDED_RECHECK_TICKS) : STRANDED_RECHECK_TICKS;
        strandedAt = here;
        strandedTick = builder.tickCount;
    }

    @Nullable
    public BlockPos walkingTo() {
        return walking ? stand : null;
    }

    public void retry() {
        bestDistance = Double.MAX_VALUE;
        ticksWithoutProgress = 0;
        repathTimer = 0;
    }

    public void reset() {
        retry();
        workFromHere = false;
        walking = false;
        stand = null;
    }

    public static double horizontalDistanceSqr(Entity entity, BlockPos pos) {
        double dx = pos.getX() + 0.5 - entity.getX();
        double dz = pos.getZ() + 0.5 - entity.getZ();
        return dx * dx + dz * dz;
    }
}
