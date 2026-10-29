package io.github.apperk274.journeymen.build;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public record AssignedChest(BlockPos pos, Role role, @Nullable BlockPos pair) {

    public AssignedChest(BlockPos pos, Role role) {
        this(pos, role, null);
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

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putIntArray("Pos", new int[]{pos.getX(), pos.getY(), pos.getZ()});
        tag.putString("Role", role.id());
        if (pair != null) {
            tag.putIntArray("Pair", new int[]{pair.getX(), pair.getY(), pair.getZ()});
        }
        return tag;
    }

    @Nullable
    public static AssignedChest load(CompoundTag tag) {
        int[] pos = tag.getIntArray("Pos");
        int[] pair = tag.getIntArray("Pair");
        Role role = Role.byId(tag.getString("Role"));
        return pos.length == 3 && role != null
                ? new AssignedChest(new BlockPos(pos[0], pos[1], pos[2]), role, pair.length == 3 ? new BlockPos(pair[0], pair[1], pair[2]) : null)
                : null;
    }
}
