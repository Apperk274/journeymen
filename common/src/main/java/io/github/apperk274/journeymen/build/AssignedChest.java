package io.github.apperk274.journeymen.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public record AssignedChest(BlockPos pos, Role role, @Nullable BlockPos pair, ResourceKey<Level> dimension) {

    public AssignedChest(BlockPos pos, Role role, ResourceKey<Level> dimension) {
        this(pos, role, null, dimension);
    }

    public enum Role {
        SUPPLY,
        DUMP;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        @Nullable
        public static Role byId(String id) {
            for (Role role : values()) {
                if (role.id().equals(id)) {
                    return role;
                }
            }
            return null;
        }
    }

    public boolean isIn(ResourceKey<Level> other) {
        return dimension.equals(other);
    }

    public AssignedChest withPlace(BlockPos newPos, @Nullable BlockPos newPair) {
        return new AssignedChest(newPos, role, newPair, dimension);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("Pos", new int[]{pos.getX(), pos.getY(), pos.getZ()});
        tag.putString("Role", role.id());
        if (pair != null) {
            tag.putIntArray("Pair", new int[]{pair.getX(), pair.getY(), pair.getZ()});
        }
        tag.putString("Dimension", dimension.location().toString());
        return tag;
    }

    @Nullable
    public static AssignedChest load(CompoundTag tag, ResourceKey<Level> fallbackDimension) {
        int[] pos = tag.getIntArray("Pos");
        int[] pair = tag.getIntArray("Pair");
        Role role = Role.byId(tag.getString("Role"));
        ResourceLocation dimension = tag.contains("Dimension", Tag.TAG_STRING) ? ResourceLocation.tryParse(tag.getString("Dimension")) : null;
        return pos.length == 3 && role != null
                ? new AssignedChest(new BlockPos(pos[0], pos[1], pos[2]), role, pair.length == 3 ? new BlockPos(pair[0], pair[1], pair[2]) : null,
                dimension == null ? fallbackDimension : ResourceKey.create(Registries.DIMENSION, dimension))
                : null;
    }

    @Override
    public String toString() {
        return "AssignedChest[pos=" + pos + ", role=" + role + ", pair=" + pair + ", dimension=" + dimension.location() + "]";
    }
}
