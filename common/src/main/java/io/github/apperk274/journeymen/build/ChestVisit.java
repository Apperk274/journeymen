package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.entity.BuilderEntity;
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

    private static final double REACH = 4.0;
    private static final int OPEN_TICKS = 10;
    private static final int GIVE_UP_TICKS = 400;

    public enum Kind {
        DUMP,
        FETCH
    }

    public enum Status {
        WORKING,
        DONE,
        FAILED
    }

    private final Kind kind;
    private final BlockPos chest;
    private final Predicate<ItemStack> wanted;
    @Nullable
    private final ItemCounter amounts;
    private boolean open;
    private int timer;
    private int walkTicks;
    private int repathTimer;
    @Nullable
    private String failure;

    private ChestVisit(Kind kind, BlockPos chest, Predicate<ItemStack> wanted, @Nullable ItemCounter amounts) {
        this.kind = kind;
        this.chest = chest;
        this.wanted = wanted;
        this.amounts = amounts;
    }

    public static ChestVisit dump(BlockPos chest) {
        return new ChestVisit(Kind.DUMP, chest, stack -> true, null);
    }

    public static ChestVisit fetch(BlockPos chest, Predicate<ItemStack> wanted) {
        return new ChestVisit(Kind.FETCH, chest, wanted, null);
    }

    public static ChestVisit fetchAmounts(BlockPos chest, ItemCounter amounts) {
        return new ChestVisit(Kind.FETCH, chest, stack -> amounts.count(stack) > 0, amounts);
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

    public Status tick(BuilderEntity builder) {
        ServerLevel level = (ServerLevel) builder.level();
        if (!level.isLoaded(chest)) {
            failure = "The " + (kind == Kind.DUMP ? "dump" : "supply") + " chest at " + chest.toShortString() + " isn't loaded.";
            return Status.FAILED;
        }
        ItemStore store = ItemStores.at(level, chest);
        if (store == null) {
            failure = "There is no chest at " + chest.toShortString() + " anymore.";
            return Status.FAILED;
        }
        builder.getLookControl().setLookAt(Vec3.atCenterOf(chest));
        if (!open) {
            if (builder.getEyePosition().distanceTo(Vec3.atCenterOf(chest)) > REACH) {
                if (++walkTicks > GIVE_UP_TICKS) {
                    failure = "I can't reach the chest at " + chest.toShortString() + ".";
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
            builder.dumpInto(store);
        } else if (amounts != null) {
            builder.fetchAmounts(store, amounts);
        } else {
            builder.fetchFrom(store, wanted);
        }
        store.setChanged();
        setOpen(level, false);
        return Status.DONE;
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
