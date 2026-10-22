package io.github.apperk274.journeymen.schematic;

import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class StructureNbtReader {

    private StructureNbtReader() {
    }

    static Schematic read(CompoundTag root) throws SchematicException {
        CompoundTag tag = DataFixTypes.STRUCTURE.updateToCurrentVersion(DataFixers.getDataFixer(), root, NbtUtils.getDataVersion(root, 500));
        ListTag sizeTag = tag.getList("size", Tag.TAG_INT);
        if (sizeTag.size() != 3) {
            throw new SchematicException("Structure has no valid size");
        }
        int width = sizeTag.getInt(0);
        int height = sizeTag.getInt(1);
        int length = sizeTag.getInt(2);
        int volume = Schematic.volume(width, height, length);

        ListTag paletteTag = tag.contains("palettes", Tag.TAG_LIST)
                ? tag.getList("palettes", Tag.TAG_LIST).getList(0)
                : tag.getList("palette", Tag.TAG_COMPOUND);

        Set<String> unknownBlocks = new HashSet<>();
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> paletteIndex = new HashMap<>();
        int[] remap = new int[paletteTag.size()];
        for (int i = 0; i < paletteTag.size(); i++) {
            BlockState state = BlockStates.read(paletteTag.getCompound(i), unknownBlocks);
            remap[i] = state == null
                    ? Schematic.NO_BLOCK
                    : paletteIndex.computeIfAbsent(state, s -> {
                        palette.add(s);
                        return palette.size() - 1;
                    });
        }

        Vec3i size = new Vec3i(width, height, length);
        int[] blocks = new int[volume];
        Arrays.fill(blocks, Schematic.NO_BLOCK);
        ListTag blocksTag = tag.getList("blocks", Tag.TAG_COMPOUND);
        for (int i = 0; i < blocksTag.size(); i++) {
            CompoundTag block = blocksTag.getCompound(i);
            ListTag pos = block.getList("pos", Tag.TAG_INT);
            if (pos.size() != 3) {
                throw new SchematicException("Block " + i + " has no valid position");
            }
            int x = pos.getInt(0);
            int y = pos.getInt(1);
            int z = pos.getInt(2);
            if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length) {
                throw new SchematicException("Block " + i + " lies outside the structure");
            }
            int state = block.getInt("state");
            if (state < 0 || state >= remap.length) {
                throw new SchematicException("Block " + i + " refers to palette id " + state + ", which is not in the palette");
            }
            blocks[Schematic.index(size, x, y, z)] = remap[state];
        }

        return new Schematic(size, palette, blocks, unknownBlocks);
    }
}
