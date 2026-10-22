package io.github.apperk274.journeymen.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Schematic {

    public static final int NO_BLOCK = -1;

    private final Vec3i size;
    private final Vec3i pasteOffset;
    private final List<BlockState> palette;
    private final int[] blocks;
    private final Map<BlockPos, CompoundTag> blockEntities;
    private final Set<String> unknownBlocks;

    Schematic(Vec3i size, Vec3i pasteOffset, List<BlockState> palette, int[] blocks, Map<BlockPos, CompoundTag> blockEntities, Set<String> unknownBlocks) {
        this.size = size;
        this.pasteOffset = pasteOffset;
        this.palette = List.copyOf(palette);
        this.blocks = blocks;
        this.blockEntities = Map.copyOf(blockEntities);
        this.unknownBlocks = Set.copyOf(unknownBlocks);
    }

    public Vec3i getSize() {
        return size;
    }

    public Vec3i getPasteOffset() {
        return pasteOffset;
    }

    @Nullable
    public CompoundTag getBlockEntity(BlockPos pos) {
        CompoundTag tag = blockEntities.get(pos);
        return tag == null ? null : tag.copy();
    }

    public List<BlockState> getPalette() {
        return palette;
    }

    public Set<String> getUnknownBlocks() {
        return unknownBlocks;
    }

    @Nullable
    public BlockState getBlock(BlockPos pos) {
        return getBlock(pos.getX(), pos.getY(), pos.getZ());
    }

    @Nullable
    public BlockState getBlock(int x, int y, int z) {
        int index = blocks[index(size, x, y, z)];
        return index == NO_BLOCK ? null : palette.get(index);
    }

    public Set<BlockPos> getBlockEntityPositions() {
        return blockEntities.keySet();
    }

    public int[] countByPaletteIndex(boolean skipBlockEntities) {
        int[] counts = new int[palette.size()];
        for (int index : blocks) {
            if (index != NO_BLOCK) {
                counts[index]++;
            }
        }
        if (skipBlockEntities) {
            for (BlockPos pos : blockEntities.keySet()) {
                int index = blocks[index(size, pos.getX(), pos.getY(), pos.getZ())];
                if (index != NO_BLOCK) {
                    counts[index]--;
                }
            }
        }
        return counts;
    }

    static int index(Vec3i size, int x, int y, int z) {
        return x + z * size.getX() + y * size.getX() * size.getZ();
    }

    static int volume(int width, int height, int length) throws SchematicException {
        if (width < 0 || height < 0 || length < 0) {
            throw new SchematicException("Schematic has a negative size: " + width + "x" + height + "x" + length);
        }
        long volume = (long) width * height * length;
        if (volume > Integer.MAX_VALUE) {
            throw new SchematicException("Schematic is too large: " + width + "x" + height + "x" + length);
        }
        return (int) volume;
    }
}
