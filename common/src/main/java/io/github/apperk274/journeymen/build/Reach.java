package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.entity.BuilderEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class Reach {

    public static final double HORIZONTAL = 4.5;
    private static final int GIVE_UP_TICKS = 60;
    private static final int REPATH_INTERVAL = 10;

    private double bestDistance = Double.MAX_VALUE;
    private int ticksWithoutProgress;
    private int repathTimer;
    private boolean workFromHere;

    public boolean approach(BuilderEntity builder, BlockPos pos) {
        if (workFromHere || horizontalDistanceSqr(builder, pos) <= HORIZONTAL * HORIZONTAL) {
            return true;
        }
        double distance = Math.sqrt(horizontalDistanceSqr(builder, pos));
        if (distance < bestDistance - 0.25) {
            bestDistance = distance;
            ticksWithoutProgress = 0;
        } else if (++ticksWithoutProgress > GIVE_UP_TICKS) {
            workFromHere = true;
            builder.getNavigation().stop();
            return true;
        }
        if (--repathTimer <= 0 || builder.getNavigation().isDone()) {
            repathTimer = REPATH_INTERVAL;
            builder.walkTowards(new Vec3(pos.getX() + 0.5, Math.min(pos.getY(), builder.getY()), pos.getZ() + 0.5));
        }
        return false;
    }

    public void reset() {
        bestDistance = Double.MAX_VALUE;
        ticksWithoutProgress = 0;
        repathTimer = 0;
        workFromHere = false;
    }

    public static double horizontalDistanceSqr(Entity entity, BlockPos pos) {
        double dx = pos.getX() + 0.5 - entity.getX();
        double dz = pos.getZ() + 0.5 - entity.getZ();
        return dx * dx + dz * dz;
    }
}
