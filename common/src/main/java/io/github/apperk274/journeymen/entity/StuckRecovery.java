package io.github.apperk274.journeymen.entity;

import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.build.BuildJob;
import io.github.apperk274.journeymen.build.BuildPlan;
import io.github.apperk274.journeymen.build.ChestVisit;
import io.github.apperk274.journeymen.build.PreCheck;
import io.github.apperk274.journeymen.build.Reach;
import io.github.apperk274.journeymen.build.StateRules;
import io.github.apperk274.journeymen.config.JourneymenConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class StuckRecovery {

    public static final double FOLLOW_DISTANCE = 12.0;
    private static final double PROGRESS_STEP = 0.5;
    private static final double OWNER_MOVED = 2.0;
    private static final int COOLDOWN_TICKS = 600;
    private static final int RETRY_TICKS = 200;
    private static final int RADIUS = 3;
    private static final int REPEAT_WINDOW_TICKS = 12000;
    private static final int REPEAT_NOTICE_COUNT = 3;

    public enum Kind {
        CHEST,
        SITE,
        OWNER,
        SPOT
    }

    public record Goal(Kind kind, Object key, BlockPos target, double distance, String description) {
    }

    public record Escape(long time, BlockPos from, BlockPos to, String reason) {

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("Time", time);
            tag.putIntArray("From", new int[]{from.getX(), from.getY(), from.getZ()});
            tag.putIntArray("To", new int[]{to.getX(), to.getY(), to.getZ()});
            tag.putString("Reason", reason);
            return tag;
        }

        @Nullable
        static Escape load(CompoundTag tag) {
            int[] from = tag.getIntArray("From");
            int[] to = tag.getIntArray("To");
            if (from.length != 3 || to.length != 3) {
                return null;
            }
            return new Escape(tag.getLong("Time"), new BlockPos(from[0], from[1], from[2]), new BlockPos(to[0], to[1], to[2]),
                    tag.getString("Reason"));
        }
    }

    private final JourneymanEntity builder;
    @Nullable
    private Object goalKey;
    @Nullable
    private BlockPos goalTarget;
    @Nullable
    private String goalDescription;
    private double best;
    private int stuckTicks;
    private int lastWork;
    private long nextAttempt;
    private boolean reportedNoSpot;
    private final Deque<Long> recent = new ArrayDeque<>();
    private long lastRepeatNotice = Long.MIN_VALUE;
    @Nullable
    private Escape last;

    StuckRecovery(JourneymanEntity builder) {
        this.builder = builder;
    }

    public static int limitTicks() {
        return JourneymenConfig.STUCK_TELEPORT_SECONDS.get() * 20;
    }

    public static int giveUpTicks(int ticks) {
        int limit = limitTicks();
        return limit > 0 ? Math.max(ticks, limit + 200) : ticks;
    }

    void tick(@Nullable Goal goal, int work, boolean hold) {
        if (work != lastWork) {
            lastWork = work;
            resetTimer();
        }
        if (goal == null) {
            goalKey = null;
            goalTarget = null;
            if (!hold) {
                resetTimer();
            }
            return;
        }
        boolean sameGoal = goal.key().equals(goalKey)
                && (goal.kind() != Kind.OWNER || goalTarget != null && goalTarget.closerThan(goal.target(), OWNER_MOVED));
        goalDescription = goal.description();
        if (!sameGoal) {
            goalKey = goal.key();
            goalTarget = goal.target();
            best = goal.distance();
        } else if (goal.distance() < best - PROGRESS_STEP) {
            best = goal.distance();
            resetTimer();
        }
        stuckTicks++;
        int limit = limitTicks();
        if (limit > 0 && stuckTicks >= limit && builder.level().getGameTime() >= nextAttempt) {
            escape(goal, false);
        }
    }

    private void resetTimer() {
        stuckTicks = 0;
        reportedNoSpot = false;
    }

    public int getStuckTicks() {
        return stuckTicks;
    }

    @Nullable
    public String getGoalDescription() {
        return goalDescription;
    }

    @Nullable
    public Escape getLast() {
        return last;
    }

    @Nullable
    String escape(Goal goal, boolean manual) {
        ServerLevel level = (ServerLevel) builder.level();
        long now = level.getGameTime();
        BlockPos from = builder.blockPosition();
        BlockPos to = findSpot(level, goal, manual);
        if (to == null) {
            nextAttempt = now + RETRY_TICKS;
            String problem = "There is no safe spot near " + goal.description() + " for " + builder.getJourneymanName() + " to get out to.";
            if (!manual && !reportedNoSpot) {
                reportedNoSpot = true;
                Constants.LOG.info("[{}.{}] Stuck at {} on the way to {}, but found no safe spot to get out to.", builder.getOwnerName(),
                        builder.getJourneymanName(), from.toShortString(), goal.description());
            }
            return problem;
        }
        teleport(level, to);
        resetTimer();
        goalKey = null;
        goalTarget = null;
        nextAttempt = now + COOLDOWN_TICKS;
        last = new Escape(now, from, to, goal.description());
        Constants.LOG.info("[{}.{}] {} at {} on the way to {}; teleported to {}.", builder.getOwnerName(), builder.getJourneymanName(),
                manual ? "Unstuck by command" : "Stuck for " + JourneymenConfig.STUCK_TELEPORT_SECONDS.get() + " s",
                from.toShortString(), goal.description(), to.toShortString());
        recent.addLast(now);
        while (!recent.isEmpty() && now - recent.peekFirst() > REPEAT_WINDOW_TICKS) {
            recent.pollFirst();
        }
        long reminderTicks = JourneymenConfig.REMINDER_INTERVAL_SECONDS.get() * 20L;
        if (!manual && recent.size() >= REPEAT_NOTICE_COUNT && (lastRepeatNotice == Long.MIN_VALUE || now - lastRepeatNotice >= reminderTicks)) {
            lastRepeatNotice = now;
            builder.reportRepeatedlyStuck(recent.size() + " times in the last " + REPEAT_WINDOW_TICKS / 1200 + " minutes", from, goal.description());
        }
        builder.escaped();
        return null;
    }

    private void teleport(ServerLevel level, BlockPos to) {
        level.gameEvent(GameEvent.TELEPORT, builder.position(), GameEvent.Context.of(builder));
        level.playSound(null, builder.getX(), builder.getY(), builder.getZ(), SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.NEUTRAL, 1.0F, 1.0F);
        builder.getNavigation().stop();
        builder.teleportTo(to.getX() + 0.5, to.getY(), to.getZ() + 0.5);
        builder.resetFallDistance();
        level.broadcastEntityEvent(builder, EntityEvent.TELEPORT);
        level.playSound(null, builder.getX(), builder.getY(), builder.getZ(), SoundEvents.CHORUS_FRUIT_TELEPORT, SoundSource.NEUTRAL, 1.0F, 1.0F);
    }

    @Nullable
    private BlockPos findSpot(ServerLevel level, Goal goal, boolean manual) {
        BlockPos target = goal.target();
        if (!level.isLoaded(target)) {
            return null;
        }
        BuildJob job = builder.getJob();
        BoundingBox area = job == null ? null : job.getArea();
        if (goal.kind() == Kind.SITE) {
            target = new BlockPos(target.getX(), level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, target.getX(), target.getZ()), target.getZ());
        }
        int below = goal.kind() == Kind.CHEST || goal.kind() == Kind.SITE ? 3 : 1;
        int above = goal.kind() == Kind.CHEST || goal.kind() == Kind.SITE ? 2 : 1;
        BlockPos center = target;
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (goal.kind() == Kind.OWNER && Math.abs(dx) < 2 && Math.abs(dz) < 2) {
                    continue;
                }
                for (int dy = -below; dy <= above; dy++) {
                    candidates.add(center.offset(dx, dy, dz));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(center)).thenComparingInt(pos -> Math.abs(pos.getY() - center.getY())));
        double current = builder.blockPosition().distSqr(center);
        BuildPlan plan = job == null ? null : job.getPlan();
        Map<BlockPos, BlockState> planned = plan == null ? Map.of() : plan.targetsByPosition();
        Set<BlockPos> toClear = plan == null ? Set.of() : new HashSet<>(plan.getToClear());
        BlockPos inside = null;
        for (BlockPos pos : candidates) {
            if ((manual ? pos.equals(builder.blockPosition()) : pos.distSqr(center) >= current - 1.0) || !fits(level, goal, target, pos)) {
                continue;
            }
            boolean inArea = area != null && (area.isInside(pos) || area.isInside(pos.above()) || area.isInside(pos.below()));
            if (!inArea) {
                return pos;
            }
            if (inside == null && goal.kind() != Kind.SITE && !planned.containsKey(pos) && !planned.containsKey(pos.above())
                    && !toClear.contains(pos.below()) && (!planned.containsKey(pos.below()) || StateRules.matches(planned.get(pos.below()), level.getBlockState(pos.below())))) {
                inside = pos;
            }
        }
        return inside;
    }

    private boolean fits(ServerLevel level, Goal goal, BlockPos target, BlockPos pos) {
        if (!level.isLoaded(pos) || !level.isPositionEntityTicking(pos)) {
            return false;
        }
        if (WalkNodeEvaluator.getPathTypeStatic(builder, pos) != PathType.WALKABLE
                || level.getBlockState(pos.below()).getBlock() instanceof LeavesBlock) {
            return false;
        }
        Vec3 feet = Vec3.atBottomCenterOf(pos);
        AABB box = builder.getDimensions(builder.getPose()).makeBoundingBox(feet);
        if (!level.noCollision(builder, box)) {
            return false;
        }
        Vec3 eye = feet.add(0.0, builder.getEyeHeight(), 0.0);
        return switch (goal.kind()) {
            case CHEST -> eye.distanceTo(Vec3.atCenterOf(target)) <= ChestVisit.REACH - 0.25 && sees(level, eye, Vec3.atCenterOf(target), target);
            case SITE -> {
                BuildJob job = builder.getJob();
                double distance = job == null ? 0.0 : PreCheck.horizontalDistance(pos, job.getArea());
                yield distance >= 1.0 && distance <= Reach.HORIZONTAL - 0.5 && sees(level, eye, Vec3.atCenterOf(target), null);
            }
            case OWNER, SPOT -> sees(level, eye, Vec3.atCenterOf(target.above()), null);
        };
    }

    private boolean sees(ServerLevel level, Vec3 from, Vec3 to, @Nullable BlockPos allowed) {
        BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, builder));
        if (hit.getType() == HitResult.Type.MISS) {
            return true;
        }
        if (allowed == null) {
            return false;
        }
        BlockPos hitPos = hit.getBlockPos();
        return hitPos.equals(allowed) || hitPos.distManhattan(allowed) == 1
                && level.getBlockState(hitPos).getBlock() == level.getBlockState(allowed).getBlock();
    }

    void save(CompoundTag tag) {
        if (last != null) {
            tag.put("LastUnstuck", last.save());
        }
    }

    void load(CompoundTag tag) {
        last = tag.contains("LastUnstuck") ? Escape.load(tag.getCompound("LastUnstuck")) : null;
    }
}
