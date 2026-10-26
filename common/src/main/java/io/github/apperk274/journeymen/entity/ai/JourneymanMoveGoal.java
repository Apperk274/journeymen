package io.github.apperk274.journeymen.entity.ai;

import io.github.apperk274.journeymen.entity.JourneymanEntity;
import io.github.apperk274.journeymen.entity.StuckRecovery;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.EnumSet;

public class JourneymanMoveGoal extends Goal {

    private static final double FOLLOW_START_DISTANCE = 6.0;
    private static final double FOLLOW_STOP_DISTANCE = 3.0;
    private static final double ARRIVE_DISTANCE = 1.5;
    private static final int REPATH_INTERVAL = 10;
    private static final int GIVE_UP_TICKS = 600;

    private final JourneymanEntity builder;
    private int repathTimer;
    private int ticksWithoutProgress;
    private double bestDistance;

    public JourneymanMoveGoal(JourneymanEntity builder) {
        this.builder = builder;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        Vec3 target = target();
        return !builder.isWorking() && target != null && builder.position().distanceTo(target) > startDistance();
    }

    @Override
    public boolean canContinueToUse() {
        Vec3 target = target();
        return !builder.isWorking() && target != null && builder.position().distanceTo(target) > stopDistance()
                && ticksWithoutProgress < StuckRecovery.giveUpTicks(GIVE_UP_TICKS);
    }

    @Override
    public void start() {
        repathTimer = 0;
        ticksWithoutProgress = 0;
        bestDistance = Double.MAX_VALUE;
    }

    @Override
    public void stop() {
        builder.getNavigation().stop();
        builder.clearComeTarget();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        Vec3 target = target();
        if (target == null) {
            return;
        }
        builder.getLookControl().setLookAt(target.x, target.y + 1.0, target.z);
        double distance = builder.position().distanceTo(target);
        if (distance < bestDistance - 0.5) {
            bestDistance = distance;
            ticksWithoutProgress = 0;
        } else {
            ticksWithoutProgress++;
        }
        if (--repathTimer <= 0) {
            repathTimer = REPATH_INTERVAL;
            builder.walkTowards(target);
        }
    }

    @Nullable
    private Vec3 target() {
        BlockPos comeTarget = builder.getComeTarget();
        if (comeTarget != null) {
            return Vec3.atBottomCenterOf(comeTarget);
        }
        if (builder.isFollowing()) {
            ServerPlayer owner = builder.getOwnerPlayer();
            if (owner != null && owner.level() == builder.level() && !owner.isSpectator()) {
                return owner.position();
            }
        }
        return null;
    }

    private double startDistance() {
        return builder.getComeTarget() != null ? ARRIVE_DISTANCE : FOLLOW_START_DISTANCE;
    }

    private double stopDistance() {
        return builder.getComeTarget() != null ? ARRIVE_DISTANCE : FOLLOW_STOP_DISTANCE;
    }
}
