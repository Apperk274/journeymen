package io.github.apperk274.journeymen.schematic;

import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class SpongeSchematicReader {

    private SpongeSchematicReader() {
    }

    static Schematic read(CompoundTag root) throws SchematicException {
        CompoundTag tag = root.contains("Schematic", Tag.TAG_COMPOUND) ? root.getCompound("Schematic") : root;
        int version = tag.getInt("Version");
        CompoundTag paletteTag;
        byte[] data;
        switch (version) {
            case 2 -> {
                paletteTag = tag.getCompound("Palette");
                data = tag.getByteArray("BlockData");
            }
            case 3 -> {
                CompoundTag blocks = tag.getCompound("Blocks");
                paletteTag = blocks.getCompound("Palette");
                data = blocks.getByteArray("Data");
            }
            default -> throw new SchematicException("Unsupported Sponge schematic version " + version + " (expected 2 or 3)");
        }
        if (!tag.contains("DataVersion", Tag.TAG_ANY_NUMERIC)) {
            throw new SchematicException("Schematic has no DataVersion");
        }
        int dataVersion = tag.getInt("DataVersion");
        int width = tag.getShort("Width") & 0xFFFF;
        int height = tag.getShort("Height") & 0xFFFF;
        int length = tag.getShort("Length") & 0xFFFF;
        int volume = Schematic.volume(width, height, length);

        Set<String> unknownBlocks = new HashSet<>();
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> paletteIndex = new HashMap<>();
        Map<Integer, Integer> remap = new HashMap<>();
        for (String key : paletteTag.getAllKeys()) {
            BlockState state = BlockStates.read(BlockStates.upgrade(BlockStates.parse(key), dataVersion), unknownBlocks);
            int target = state == null
                    ? Schematic.NO_BLOCK
                    : paletteIndex.computeIfAbsent(state, s -> {
                        palette.add(s);
                        return palette.size() - 1;
                    });
            remap.put(paletteTag.getInt(key), target);
        }

        int[] blocks = new int[volume];
        Arrays.fill(blocks, Schematic.NO_BLOCK);
        int offset = 0;
        for (int i = 0; i < volume; i++) {
            if (offset >= data.length) {
                throw new SchematicException("Block data ends early at block " + i + " of " + volume);
            }
            int value = 0;
            int shift = 0;
            byte b;
            do {
                if (offset >= data.length || shift > 28) {
                    throw new SchematicException("Malformed block data at block " + i);
                }
                b = data[offset++];
                value |= (b & 0x7F) << shift;
                shift += 7;
            } while ((b & 0x80) != 0);
            Integer target = remap.get(value);
            if (target == null) {
                throw new SchematicException("Block data refers to palette id " + value + ", which is not in the palette");
            }
            blocks[i] = target;
        }

        return new Schematic(new Vec3i(width, height, length), palette, blocks, unknownBlocks);
    }
}
