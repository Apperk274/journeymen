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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.Path;
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
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class StuckRecovery {

    public static final double FOLLOW_DISTANCE = 12.0;
    private static final double PROGRESS_STEP = 0.5;
    private static final double OWNER_MOVED = 2.0;
    private static final int COOLDOWN_TICKS = 600;
    private static final int RETRY_TICKS = 200;
    private static final int RADIUS = 3;
    private static final int REPEAT_WINDOW_TICKS = 12000;
    private static final int REPEAT_NOTICE_COUNT = 3;
    private static final int TRACE_NODES = 4;

    public enum Kind {
        CHEST,
        SITE,
        OWNER,
        SPOT,
        ASIDE
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

    boolean escapeFrom(BlockPos cell) {
        if (limitTicks() <= 0 || builder.level().getGameTime() < nextAttempt || !(builder.level() instanceof ServerLevel)) {
            return false;
        }
        return escape(new Goal(Kind.ASIDE, cell, cell, 0.0, "out of the way at " + cell.toShortString()), false) == null;
    }

    @Nullable
    public BlockPos findSafeSpot(Goal goal) {
        return builder.level() instanceof ServerLevel level ? findSpot(level, goal, true) : null;
    }

    private void traceStuck(ServerLevel level, BlockPos from) {
        Path path = builder.getNavigation().getPath();
        List<String> nodes = new ArrayList<>();
        if (path != null) {
            for (int i = path.getNextNodeIndex(); i < Math.min(path.getNodeCount(), path.getNextNodeIndex() + TRACE_NODES); i++) {
                Node node = path.getNode(i);
                nodes.add(node.asBlockPos().toShortString() + " " + node.type + " in " + level.getBlockState(node.asBlockPos()));
            }
        }
        Constants.trace("[reach] {}.{} stuck at {} ({}) in {} on {}; next path nodes: {}", builder.getOwnerName(), builder.getJourneymanName(),
                from.toShortString(), String.format(Locale.ROOT, "%.2f, %.2f, %.2f", builder.getX(), builder.getY(), builder.getZ()),
                level.getBlockState(from), level.getBlockState(from.below()), nodes.isEmpty() ? "none" : String.join("; ", nodes));
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
        if (!manual && goal.kind() != Kind.ASIDE) {
            traceStuck(level, from);
        }
        teleport(level, to);
        resetTimer();
        goalKey = null;
        goalTarget = null;
        nextAttempt = now + COOLDOWN_TICKS;
        last = new Escape(now, from, to, goal.description());
        if (goal.kind() == Kind.ASIDE) {
            Constants.LOG.info("[{}.{}] Couldn't walk {} from {}; teleported to {}.", builder.getOwnerName(), builder.getJourneymanName(),
                    goal.description(), from.toShortString(), to.toShortString());
        } else {
            Constants.LOG.info("[{}.{}] {} at {} on the way to {}; teleported to {}.", builder.getOwnerName(), builder.getJourneymanName(),
                    manual ? "Unstuck by command" : "Stuck for " + JourneymenConfig.STUCK_TELEPORT_SECONDS.get() + " s",
                    from.toShortString(), goal.description(), to.toShortString());
        }
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
        builder.stopRiding();
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
        boolean site = goal.kind() == Kind.SITE && area != null;
        BlockPos center = target;
        BlockPos here = builder.blockPosition();
        boolean wide = goal.kind() == Kind.CHEST || goal.kind() == Kind.SITE || goal.kind() == Kind.ASIDE;
        int below = wide ? 3 : 1;
        int above = wide ? 2 : 1;
        Set<Integer> levels = new TreeSet<>();
        for (int dy = -below; dy <= above; dy++) {
            levels.add(center.getY() + dy);
        }
        if (site) {
            for (int dy = -below; dy <= above; dy++) {
                levels.add(here.getY() + dy);
            }
            for (int y = area.minY(); y <= area.maxY() + 1; y++) {
                levels.add(y);
            }
        }
        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -RADIUS; dx <= RADIUS; dx++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                if (goal.kind() == Kind.OWNER && Math.abs(dx) < 2 && Math.abs(dz) < 2) {
                    continue;
                }
                for (int y : levels) {
                    if (y >= level.getMinBuildHeight() && y < level.getMaxBuildHeight()) {
                        candidates.add(new BlockPos(center.getX() + dx, y, center.getZ() + dz));
                    }
                }
            }
        }
        if (site) {
            candidates.sort(Comparator.comparingInt((BlockPos pos) -> pos.getY() >= area.minY() && pos.getY() <= area.maxY() + 1 ? 0 : 1)
                    .thenComparingInt(pos -> Math.abs(pos.getY() - area.minY()))
                    .thenComparingDouble(pos -> Mth.square(pos.getX() - center.getX()) + Mth.square(pos.getZ() - center.getZ())));
        } else {
            candidates.sort(Comparator.comparingDouble((BlockPos pos) -> pos.distSqr(center)).thenComparingInt(pos -> Math.abs(pos.getY() - center.getY())));
        }
        boolean anyDistance = manual || goal.kind() == Kind.ASIDE;
        double current = site ? PreCheck.horizontalDistance(here, area) : here.distSqr(center);
        BuildPlan plan = job == null ? null : job.getPlan();
        Map<BlockPos, BlockState> planned = plan == null ? Map.of() : plan.targetsByPosition();
        Set<BlockPos> toClear = plan == null ? Set.of() : new HashSet<>(plan.getToClear());
        List<BoundingBox> otherAreas = new ArrayList<>();
        for (BuildJob other : JourneymanRegistry.get(level.getServer()).jobs()) {
            if (other != job && other.getDimension().equals(level.dimension())) {
                otherAreas.add(other.getArea());
            }
        }
        BlockPos inside = null;
        for (BlockPos pos : candidates) {
            double distance = site ? PreCheck.horizontalDistance(pos, area) : pos.distSqr(center);
            if ((anyDistance ? pos.equals(here) : distance >= current - 1.0) || !fits(level, goal, target, pos)
                    || otherAreas.stream().anyMatch(other -> other.isInside(pos) || other.isInside(pos.above()) || other.isInside(pos.below()))) {
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
        BlockPos below = pos.below();
        if (level.getBlockState(below).getCollisionShape(level, below).isEmpty() || level.getBlockState(pos).isCollisionShapeFullBlock(level, pos)) {
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
                if (job == null) {
                    yield false;
                }
                BoundingBox area = job.getArea();
                double distance = PreCheck.horizontalDistance(pos, area);
                BlockPos near = new BlockPos(Mth.clamp(pos.getX(), area.minX(), area.maxX()), Mth.clamp(pos.getY(), area.minY(), area.maxY()),
                        Mth.clamp(pos.getZ(), area.minZ(), area.maxZ()));
                yield distance >= 1.0 && distance <= Reach.HORIZONTAL - 0.5 && sees(level, eye, Vec3.atCenterOf(near), near);
            }
            case OWNER, SPOT -> sees(level, eye, Vec3.atCenterOf(target.above()), null);
            case ASIDE -> !box.intersects(new AABB(target))
                    && Mth.square(pos.getX() - target.getX()) + Mth.square(pos.getZ() - target.getZ()) <= Mth.square(Reach.HORIZONTAL - 0.5);
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
