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

final class LitematicaReader {

    private static final int DEFAULT_DATA_VERSION = 1139;

    private LitematicaReader() {
    }

    private record Region(String name, CompoundTag tag, BlockPos min, Vec3i size) {
    }

    static Schematic read(CompoundTag root) throws SchematicException {
        if (!root.contains("Version", Tag.TAG_ANY_NUMERIC) || root.getInt("Version") < 1) {
            throw new SchematicException("Litematic has no valid Version");
        }
        int dataVersion = root.contains("MinecraftDataVersion", Tag.TAG_ANY_NUMERIC) ? root.getInt("MinecraftDataVersion") : DEFAULT_DATA_VERSION;
        CompoundTag regionsTag = root.getCompound("Regions");
        List<Region> regions = new ArrayList<>();
        for (String name : regionsTag.getAllKeys().stream().sorted().toList()) {
            if (!regionsTag.contains(name, Tag.TAG_COMPOUND)) {
                continue;
            }
            CompoundTag regionTag = regionsTag.getCompound(name);
            BlockPos position = readPos(regionTag.getCompound("Position"));
            BlockPos size = readPos(regionTag.getCompound("Size"));
            if (size.getX() == 0 || size.getY() == 0 || size.getZ() == 0) {
                throw new SchematicException("Region " + name + " has an empty size");
            }
            if (size.getX() == Integer.MIN_VALUE || size.getY() == Integer.MIN_VALUE || size.getZ() == Integer.MIN_VALUE) {
                throw new SchematicException("Region " + name + " is too large");
            }
            BlockPos min = new BlockPos(minCorner(position.getX(), size.getX()), minCorner(position.getY(), size.getY()), minCorner(position.getZ(), size.getZ()));
            regions.add(new Region(name, regionTag, min, new Vec3i(Math.abs(size.getX()), Math.abs(size.getY()), Math.abs(size.getZ()))));
        }
        if (regions.isEmpty()) {
            throw new SchematicException("Litematic has no regions");
        }

        long minX = Long.MAX_VALUE, minY = Long.MAX_VALUE, minZ = Long.MAX_VALUE;
        long maxX = Long.MIN_VALUE, maxY = Long.MIN_VALUE, maxZ = Long.MIN_VALUE;
        for (Region region : regions) {
            minX = Math.min(minX, region.min().getX());
            minY = Math.min(minY, region.min().getY());
            minZ = Math.min(minZ, region.min().getZ());
            maxX = Math.max(maxX, (long) region.min().getX() + region.size().getX());
            maxY = Math.max(maxY, (long) region.min().getY() + region.size().getY());
            maxZ = Math.max(maxZ, (long) region.min().getZ() + region.size().getZ());
        }
        if (maxX - minX > Integer.MAX_VALUE || maxY - minY > Integer.MAX_VALUE || maxZ - minZ > Integer.MAX_VALUE) {
            throw new SchematicException("Litematic is too large");
        }
        int width = (int) (maxX - minX);
        int height = (int) (maxY - minY);
        int length = (int) (maxZ - minZ);
        int volume = Schematic.volume(width, height, length);
        Vec3i size = new Vec3i(width, height, length);

        Set<String> unknownBlocks = new HashSet<>();
        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> paletteIndex = new HashMap<>();
        int[] blocks = new int[volume];
        Arrays.fill(blocks, Schematic.NO_BLOCK);
        Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();

        for (Region region : regions) {
            CompoundTag regionTag = region.tag();
            ListTag paletteTag = regionTag.getList("BlockStatePalette", Tag.TAG_COMPOUND);
            if (paletteTag.isEmpty()) {
                throw new SchematicException("Region " + region.name() + " has no block palette");
            }
            int[] remap = new int[paletteTag.size()];
            for (int i = 0; i < paletteTag.size(); i++) {
                BlockState state = BlockStates.read(BlockStates.upgrade(paletteTag.getCompound(i), dataVersion), unknownBlocks);
                remap[i] = state == null
                        ? Schematic.NO_BLOCK
                        : paletteIndex.computeIfAbsent(state, s -> {
                            palette.add(s);
                            return palette.size() - 1;
                        });
            }

            int sizeX = region.size().getX();
            int sizeY = region.size().getY();
            int sizeZ = region.size().getZ();
            int bits = Math.max(2, Integer.SIZE - Integer.numberOfLeadingZeros(paletteTag.size() - 1));
            long[] data = regionTag.getLongArray("BlockStates");
            if (!BlockStates.fitsPacked(data, bits, (long) sizeX * sizeY * sizeZ)) {
                throw new SchematicException("Block data of region " + region.name() + " ends early");
            }
            int offsetX = (int) (region.min().getX() - minX);
            int offsetY = (int) (region.min().getY() - minY);
            int offsetZ = (int) (region.min().getZ() - minZ);
            long index = 0;
            for (int y = 0; y < sizeY; y++) {
                for (int z = 0; z < sizeZ; z++) {
                    for (int x = 0; x < sizeX; x++) {
                        int value = BlockStates.unpack(data, bits, index++);
                        if (value >= remap.length) {
                            throw new SchematicException("Region " + region.name() + " refers to palette id " + value + ", which is not in the palette");
                        }
                        if (remap[value] != Schematic.NO_BLOCK) {
                            blocks[Schematic.index(size, offsetX + x, offsetY + y, offsetZ + z)] = remap[value];
                        }
                    }
                }
            }
            blockEntities.keySet().removeIf(pos -> writes(remap, data, bits, region.size(), pos.getX() - offsetX, pos.getY() - offsetY, pos.getZ() - offsetZ));

            ListTag blockEntityList = regionTag.getList("TileEntities", Tag.TAG_COMPOUND);
            for (int i = 0; i < blockEntityList.size(); i++) {
                CompoundTag entry = blockEntityList.getCompound(i);
                CompoundTag blockEntity = entry.contains("TileNBT", Tag.TAG_COMPOUND) ? entry.getCompound("TileNBT").copy() : entry.copy();
                int x = entry.getInt("x");
                int y = entry.getInt("y");
                int z = entry.getInt("z");
                if (!blockEntity.contains("id", Tag.TAG_STRING) || !writes(remap, data, bits, region.size(), x, y, z)) {
                    continue;
                }
                blockEntity.remove("x");
                blockEntity.remove("y");
                blockEntity.remove("z");
                blockEntities.put(new BlockPos(offsetX + x, offsetY + y, offsetZ + z), BlockStates.upgradeBlockEntity(blockEntity, dataVersion));
            }
        }

        return new Schematic(size, new Vec3i((int) minX, (int) minY, (int) minZ), palette, blocks, blockEntities, unknownBlocks);
    }

    private static boolean writes(int[] remap, long[] data, int bits, Vec3i size, int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= size.getX() || y >= size.getY() || z >= size.getZ()) {
            return false;
        }
        return remap[BlockStates.unpack(data, bits, ((long) y * size.getZ() + z) * size.getX() + x)] != Schematic.NO_BLOCK;
    }

    private static BlockPos readPos(CompoundTag tag) {
        return new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
    }

    private static int minCorner(int position, int size) throws SchematicException {
        long corner = size > 0 ? position : (long) position + size + 1;
        if (corner < Integer.MIN_VALUE || corner > Integer.MAX_VALUE) {
            throw new SchematicException("Litematic region lies outside the coordinate range");
        }
        return (int) corner;
    }
}
