package io.github.apperk274.journeymen.schematic;

import com.mojang.serialization.Dynamic;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.Set;

final class BlockStates {

    private BlockStates() {
    }

    static CompoundTag parse(String state) throws SchematicException {
        CompoundTag tag = new CompoundTag();
        int bracket = state.indexOf('[');
        if (bracket < 0) {
            tag.putString("Name", state);
            return tag;
        }
        if (!state.endsWith("]")) {
            throw new SchematicException("Malformed block state: " + state);
        }
        tag.putString("Name", state.substring(0, bracket));
        CompoundTag properties = new CompoundTag();
        String body = state.substring(bracket + 1, state.length() - 1);
        if (!body.isEmpty()) {
            for (String property : body.split(",")) {
                int equals = property.indexOf('=');
                if (equals < 0) {
                    throw new SchematicException("Malformed block state: " + state);
                }
                properties.putString(property.substring(0, equals).trim(), property.substring(equals + 1).trim());
            }
        }
        tag.put("Properties", properties);
        return tag;
    }

    static CompoundTag upgrade(CompoundTag stateTag, int dataVersion) {
        int current = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        if (dataVersion >= current) {
            return stateTag;
        }
        return (CompoundTag) DataFixers.getDataFixer()
                .update(References.BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE, stateTag), dataVersion, current)
                .getValue();
    }

    static CompoundTag upgradeBlockEntity(CompoundTag blockEntityTag, int dataVersion) {
        int current = SharedConstants.getCurrentVersion().getDataVersion().getVersion();
        if (dataVersion >= current) {
            return blockEntityTag;
        }
        return (CompoundTag) DataFixers.getDataFixer()
                .update(References.BLOCK_ENTITY, new Dynamic<>(NbtOps.INSTANCE, blockEntityTag), dataVersion, current)
                .getValue();
    }

    static int unpack(long[] data, int bits, long index) {
        long start = index * bits;
        int first = (int) (start >> 6);
        int last = (int) ((start + bits - 1) >> 6);
        int shift = (int) (start & 63);
        long mask = (1L << bits) - 1;
        if (first == last) {
            return (int) (data[first] >>> shift & mask);
        }
        return (int) ((data[first] >>> shift | data[last] << (64 - shift)) & mask);
    }

    static boolean fitsPacked(long[] data, int bits, long count) {
        return data.length >= (count * bits + 63) >> 6;
    }

    @Nullable
    static BlockState read(CompoundTag stateTag, Set<String> unknownBlocks) {
        String name = stateTag.getString("Name");
        ResourceLocation id = ResourceLocation.tryParse(name);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            unknownBlocks.add(name);
            return null;
        }
        return NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), stateTag);
    }
}
