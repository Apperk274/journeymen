package io.github.apperk274.journeymen.schematic;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

public final class Schematic {

    public static final int NO_BLOCK = -1;

    private final Vec3i size;
    private final List<BlockState> palette;
    private final int[] blocks;
    private final Set<String> unknownBlocks;

    Schematic(Vec3i size, List<BlockState> palette, int[] blocks, Set<String> unknownBlocks) {
        this.size = size;
        this.palette = List.copyOf(palette);
        this.blocks = blocks;
        this.unknownBlocks = Set.copyOf(unknownBlocks);
    }

    public Vec3i getSize() {
        return size;
    }

    public List<BlockState> getPalette() {
        return palette;
    }

    public Set<String> getUnknownBlocks() {
        return unknownBlocks;
    }

    @Nullable
    public BlockState getBlock(BlockPos pos) {
        int index = blocks[index(size, pos.getX(), pos.getY(), pos.getZ())];
        return index == NO_BLOCK ? null : palette.get(index);
    }

    public int[] countByPaletteIndex() {
        int[] counts = new int[palette.size()];
        for (int index : blocks) {
            if (index != NO_BLOCK) {
                counts[index]++;
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
