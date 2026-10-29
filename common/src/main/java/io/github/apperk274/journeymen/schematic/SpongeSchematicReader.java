package io.github.apperk274.journeymen.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
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

    private static final int VERSION_1_DATA_VERSION = 1631;

    private SpongeSchematicReader() {
    }

    static Schematic read(CompoundTag root) throws SchematicException {
        CompoundTag tag = root.contains("Schematic", Tag.TAG_COMPOUND) ? root.getCompound("Schematic") : root;
        int version = tag.getInt("Version");
        CompoundTag paletteTag;
        byte[] data;
        ListTag blockEntityList;
        Vec3i pasteOffset;
        switch (version) {
            case 1, 2 -> {
                paletteTag = tag.getCompound("Palette");
                data = tag.getByteArray("BlockData");
                blockEntityList = tag.getList(tag.contains("BlockEntities", Tag.TAG_LIST) ? "BlockEntities" : "TileEntities", Tag.TAG_COMPOUND);
                CompoundTag metadata = tag.getCompound("Metadata");
                pasteOffset = new Vec3i(metadata.getInt("WEOffsetX"), metadata.getInt("WEOffsetY"), metadata.getInt("WEOffsetZ"));
            }
            case 3 -> {
                CompoundTag blocks = tag.getCompound("Blocks");
                paletteTag = blocks.getCompound("Palette");
                data = blocks.getByteArray("Data");
                blockEntityList = blocks.getList("BlockEntities", Tag.TAG_COMPOUND);
                int[] offset = tag.getIntArray("Offset");
                pasteOffset = offset.length == 3 ? new Vec3i(offset[0], offset[1], offset[2]) : Vec3i.ZERO;
            }
            default -> throw new SchematicException("Unsupported Sponge schematic version " + version + " (expected 1, 2 or 3)");
        }
        int dataVersion;
        if (tag.contains("DataVersion", Tag.TAG_ANY_NUMERIC)) {
            dataVersion = tag.getInt("DataVersion");
        } else if (version == 1) {
            dataVersion = VERSION_1_DATA_VERSION;
        } else {
            throw new SchematicException("Schematic has no DataVersion");
        }
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

        Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();
        for (int i = 0; i < blockEntityList.size(); i++) {
            CompoundTag entry = blockEntityList.getCompound(i);
            int[] pos = entry.getIntArray("Pos");
            if (pos.length != 3 || pos[0] < 0 || pos[1] < 0 || pos[2] < 0 || pos[0] >= width || pos[1] >= height || pos[2] >= length) {
                continue;
            }
            CompoundTag blockEntity;
            if (version == 3) {
                blockEntity = entry.getCompound("Data").copy();
            } else {
                blockEntity = entry.copy();
                blockEntity.remove("Pos");
                blockEntity.remove("Id");
            }
            blockEntity.putString("id", entry.getString("Id"));
            blockEntities.put(new BlockPos(pos[0], pos[1], pos[2]), BlockStates.upgradeBlockEntity(blockEntity, dataVersion));
        }

        return new Schematic(new Vec3i(width, height, length), pasteOffset, palette, blocks, blockEntities, unknownBlocks);
    }
}
