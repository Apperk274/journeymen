package io.github.apperk274.journeymen.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class CrewWork {

    public static final int UNREACHABLE_TICKS = 6000;

    @Nullable
    private ClearingWork clearing;
    @Nullable
    private PlacingWork placing;
    private boolean clearingDone;
    private boolean clearedAny;
    private final Map<BlockPos, UUID> claims = new HashMap<>();
    private final Map<UUID, ItemCounter> reservations = new HashMap<>();
    private final Set<UUID> starving = new HashSet<>();
    private final Map<BlockPos, Long> unreachable = new HashMap<>();

    @Nullable
    public ClearingWork getClearing() {
        return clearing;
    }

    public void setClearing(ClearingWork clearing) {
        this.clearing = clearing;
        this.placing = null;
        this.clearingDone = false;
        claims.clear();
        reservations.clear();
    }

    public boolean isClearingDone() {
        return clearingDone;
    }

    public void noteCleared(boolean cleared) {
        clearedAny |= cleared;
    }

    public boolean takeClearedAny() {
        boolean any = clearedAny;
        clearedAny = false;
        return any;
    }

    public void finishClearing() {
        clearing = null;
        clearingDone = true;
        claims.clear();
        reservations.clear();
    }

    @Nullable
    public PlacingWork getPlacing() {
        return placing;
    }

    public void setPlacing(PlacingWork placing) {
        this.placing = placing;
        this.clearing = null;
        this.clearingDone = true;
        claims.clear();
        reservations.clear();
    }

    public void claim(BlockPos pos, UUID member) {
        claims.put(pos, member);
    }

    public boolean claimedByOther(BlockPos pos, UUID member) {
        UUID owner = claims.get(pos);
        return owner != null && !owner.equals(member);
    }

    public boolean hasClaimsByOthers(UUID member) {
        for (UUID owner : claims.values()) {
            if (!owner.equals(member)) {
                return true;
            }
        }
        return false;
    }

    public void unclaim(BlockPos pos, UUID member) {
        claims.remove(pos, member);
    }

    public void release(UUID member) {
        claims.values().removeIf(member::equals);
        reservations.remove(member);
        starving.remove(member);
    }

    public void keepOnly(Set<UUID> active) {
        claims.values().removeIf(owner -> !active.contains(owner));
        reservations.keySet().removeIf(owner -> !active.contains(owner));
        starving.removeIf(owner -> !active.contains(owner));
    }

    public void setStarving(UUID member, boolean waiting) {
        if (waiting) {
            starving.add(member);
        } else {
            starving.remove(member);
        }
    }

    public boolean anyStarving() {
        return !starving.isEmpty();
    }

    public boolean othersStarving(UUID member) {
        for (UUID other : starving) {
            if (!other.equals(member)) {
                return true;
            }
        }
        return false;
    }

    public void markUnreachable(BlockPos chest, long now) {
        unreachable.put(chest.immutable(), now);
    }

    public boolean isUnreachable(BlockPos chest, long now) {
        Long since = unreachable.get(chest);
        if (since == null) {
            return false;
        }
        if (now - since >= UNREACHABLE_TICKS) {
            unreachable.remove(chest);
            return false;
        }
        return true;
    }

    public ItemCounter reservationOf(UUID member) {
        return reservations.computeIfAbsent(member, key -> new ItemCounter());
    }

    public void reserve(UUID member, ItemCounter items) {
        reservations.put(member, items);
    }

    public ItemCounter reservedByOthers(UUID member) {
        ItemCounter total = new ItemCounter();
        reservations.forEach((owner, items) -> {
            if (!owner.equals(member)) {
                for (Map.Entry<ItemStack, Long> entry : items.entries()) {
                    total.add(entry.getKey(), entry.getValue());
                }
            }
        });
        return total;
    }
}
