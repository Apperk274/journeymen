package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.StuckRecovery;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

public final class ChestVisit {

    public static final double REACH = 4.0;
    public static final Predicate<ItemStack> NOTHING = stack -> false;
    private static final int OPEN_TICKS = 10;
    private static final int GIVE_UP_TICKS = 400;
    private static final double PROGRESS_STEP = 0.5;

    public enum Kind {
        DUMP,
        STORE,
        FETCH,
        RESTOCK
    }

    public enum Status {
        WORKING,
        DONE,
        FAILED
    }

    public enum Problem {
        GONE,
        UNLOADED,
        UNREACHABLE,
        LOCKED
    }

    private final Kind kind;
    private final BlockPos chest;
    private final Predicate<ItemStack> wanted;
    @Nullable
    private final ItemCounter amounts;
    private final boolean oneEach;
    private final boolean fromDump;
    private boolean open;
    private boolean started;
    private int timer;
    private double best = Double.MAX_VALUE;
    private int stalledTicks;
    private int repathTimer;
    @Nullable
    private String failure;
    @Nullable
    private Problem problem;

    private ChestVisit(Kind kind, BlockPos chest, Predicate<ItemStack> wanted, @Nullable ItemCounter amounts, boolean oneEach) {
        this(kind, chest, wanted, amounts, oneEach, kind == Kind.DUMP);
    }

    private ChestVisit(Kind kind, BlockPos chest, Predicate<ItemStack> wanted, @Nullable ItemCounter amounts, boolean oneEach, boolean fromDump) {
        this.kind = kind;
        this.chest = chest;
        this.wanted = wanted;
        this.amounts = amounts;
        this.oneEach = oneEach;
        this.fromDump = fromDump;
    }

    public static ChestVisit dump(BlockPos chest) {
        return new ChestVisit(Kind.DUMP, chest, stack -> true, null, false);
    }

    public static ChestVisit fetchOneEach(BlockPos chest, Predicate<ItemStack> wanted) {
        return new ChestVisit(Kind.FETCH, chest, wanted, null, true);
    }

    public static ChestVisit restockTools(BlockPos chest, @Nullable Predicate<ItemStack> mustHave) {
        return new ChestVisit(Kind.RESTOCK, chest, mustHave == null ? NOTHING : mustHave, null, false);
    }

    public static ChestVisit store(BlockPos chest, @Nullable ItemCounter keep) {
        return new ChestVisit(Kind.STORE, chest, stack -> true, keep, false);
    }

    public static ChestVisit fetchAmounts(BlockPos chest, ItemCounter amounts) {
        return new ChestVisit(Kind.FETCH, chest, stack -> amounts.count(stack) > 0, amounts, false);
    }

    public static ChestVisit fetchFromDump(BlockPos chest, ItemCounter amounts) {
        return new ChestVisit(Kind.FETCH, chest, stack -> amounts.count(stack) > 0, amounts, false, true);
    }

    public Kind getKind() {
        return kind;
    }

    public BlockPos getChest() {
        return chest;
    }

    @Nullable
    public String getFailure() {
        return failure;
    }

    @Nullable
    public Problem getProblem() {
        return problem;
    }

    public boolean isOpen() {
        return open;
    }

    public Status tick(BuilderEntity builder) {
        ServerLevel level = (ServerLevel) builder.level();
        if (!level.isLoaded(chest)) {
            failure = "The " + role() + " chest at " + chest.toShortString() + " isn't loaded.";
            problem = Problem.UNLOADED;
            return Status.FAILED;
        }
        if (ItemStores.isLocked(level, chest)) {
            failure = "The " + role() + " chest at " + chest.toShortString() + " is locked, so my builders can't open it. Unlock it or give me another "
                    + role() + " chest.";
            problem = Problem.LOCKED;
            return Status.FAILED;
        }
        ItemStore store = ItemStores.at(level, chest);
        if (store == null) {
            failure = "There is no chest at " + chest.toShortString() + " anymore.";
            problem = Problem.GONE;
            return Status.FAILED;
        }
        if (!started) {
            started = true;
            if (kind == Kind.FETCH && !containsWanted(store) || kind == Kind.RESTOCK && !builder.restockWorthVisiting(store, wanted)
                    || kind == Kind.STORE && !builder.storeWorthVisiting(store, amounts)) {
                return Status.DONE;
            }
        }
        builder.getLookControl().setLookAt(Vec3.atCenterOf(chest));
        if (!open) {
            double distance = builder.getEyePosition().distanceTo(Vec3.atCenterOf(chest));
            if (distance > REACH) {
                if (distance < best - PROGRESS_STEP) {
                    best = distance;
                    stalledTicks = 0;
                } else if (++stalledTicks > StuckRecovery.giveUpTicks(GIVE_UP_TICKS)) {
                    failure = "My builders can't get to the " + role() + " chest at " + chest.toShortString()
                            + ". Clear a way to it (they need to get within " + (int) REACH + " blocks of it) or move it; until then they use my other "
                            + role() + " chests.";
                    problem = Problem.UNREACHABLE;
                    return Status.FAILED;
                }
                if (--repathTimer <= 0 || builder.getNavigation().isDone()) {
                    repathTimer = 10;
                    builder.walkTowards(Vec3.atBottomCenterOf(chest));
                }
                return Status.WORKING;
            }
            builder.getNavigation().stop();
            setOpen(level, true);
            open = true;
            timer = OPEN_TICKS;
            return Status.WORKING;
        }
        if (--timer > 0) {
            return Status.WORKING;
        }
        if (kind == Kind.DUMP) {
            builder.dumpInto(store, chest);
        } else if (kind == Kind.RESTOCK) {
            builder.restockToolsAt(store, chest, wanted);
        } else if (kind == Kind.STORE) {
            builder.storeInto(store, chest, amounts);
        } else if (amounts != null) {
            builder.fetchAmounts(store, chest, amounts, fromDump);
        } else {
            builder.fetchFrom(store, chest, wanted, oneEach, fromDump);
        }
        store.setChanged();
        setOpen(level, false);
        return Status.DONE;
    }

    private String role() {
        return fromDump ? "dump" : "supply";
    }

    private boolean containsWanted(ItemStore store) {
        for (int slot = 0; slot < store.size(); slot++) {
            ItemStack stack = store.get(slot);
            if (!stack.isEmpty() && wanted.test(stack)) {
                return true;
            }
        }
        return false;
    }

    public void abort(ServerLevel level) {
        if (open) {
            setOpen(level, false);
            open = false;
        }
    }

    private void setOpen(ServerLevel level, boolean opening) {
        BlockState state = level.getBlockState(chest);
        if (state.getBlock() instanceof ChestBlock) {
            level.blockEvent(chest, state.getBlock(), 1, opening ? 1 : 0);
            level.playSound(null, chest, opening ? SoundEvents.CHEST_OPEN : SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5F,
                    level.random.nextFloat() * 0.1F + 0.9F);
        } else if (state.getBlock() instanceof BarrelBlock) {
            level.playSound(null, chest, opening ? SoundEvents.BARREL_OPEN : SoundEvents.BARREL_CLOSE, SoundSource.BLOCKS, 0.5F,
                    level.random.nextFloat() * 0.1F + 0.9F);
        }
    }
}
