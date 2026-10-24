package io.github.apperk274.journeymen.schematic;

import com.mojang.serialization.Dynamic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.BlockStateData;
import net.minecraft.util.datafix.fixes.References;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class McEditSchematicReader {

    private static final int OLDEST_DATA_VERSION = 99;
    private static final int LAST_LEGACY_DATA_VERSION = 1343;
    private static final int FLATTENED_DATA_VERSION = 1452;
    private static final int CHUNK_HEIGHT = 256;

    private McEditSchematicReader() {
    }

    static Schematic read(CompoundTag tag) throws SchematicException {
        String materials = tag.getString("Materials");
        if (!materials.equals("Alpha")) {
            throw new SchematicException("Unsupported MCEdit schematic materials \"" + materials + "\" (expected Alpha)");
        }
        int width = tag.getShort("Width") & 0xFFFF;
        int height = tag.getShort("Height") & 0xFFFF;
        int length = tag.getShort("Length") & 0xFFFF;
        int volume = Schematic.volume(width, height, length);
        Vec3i size = new Vec3i(width, height, length);
        byte[] ids = tag.getByteArray("Blocks");
        byte[] metadata = tag.getByteArray("Data");
        byte[] addIds = tag.getByteArray("AddBlocks");
        if (ids.length < volume || metadata.length < volume) {
            throw new SchematicException("Block data ends early: " + Math.min(ids.length, metadata.length) + " of " + volume + " blocks");
        }
        Vec3i pasteOffset = new Vec3i(tag.getInt("WEOffsetX"), tag.getInt("WEOffsetY"), tag.getInt("WEOffsetZ"));

        ListTag tileEntities = tag.getList("TileEntities", Tag.TAG_COMPOUND);
        int sourceVersion = LAST_LEGACY_DATA_VERSION;
        for (int i = 0; i < tileEntities.size(); i++) {
            String id = tileEntities.getCompound(i).getString("id");
            if (!id.isEmpty() && id.indexOf(':') < 0) {
                sourceVersion = OLDEST_DATA_VERSION;
            }
        }

        Set<String> unknownBlocks = new HashSet<>();
        int[] legacyIds = new int[volume];
        Boolean[] knownIds = new Boolean[256];
        for (int i = 0; i < volume; i++) {
            int id = ids[i] & 0xFF;
            if ((i >> 1) < addIds.length) {
                id |= ((i & 1) == 0 ? addIds[i >> 1] & 0x0F : (addIds[i >> 1] & 0xF0) >> 4) << 8;
            }
            if (id > 255 || !known(id, knownIds)) {
                unknownBlocks.add("legacy block id " + id);
                legacyIds[i] = -1;
            } else {
                legacyIds[i] = id;
            }
        }

        List<BlockState> palette = new ArrayList<>();
        Map<BlockState, Integer> paletteIndex = new HashMap<>();
        Map<CompoundTag, Integer> stateIndex = new HashMap<>();
        int[] blocks = new int[volume];
        Arrays.fill(blocks, Schematic.NO_BLOCK);
        Map<BlockPos, CompoundTag> blockEntities = new HashMap<>();

        for (int band = 0; band * CHUNK_HEIGHT < height; band++) {
            for (int chunkX = 0; chunkX * 16 < width; chunkX++) {
                for (int chunkZ = 0; chunkZ * 16 < length; chunkZ++) {
                    CompoundTag level = upgradeChunk(chunk(size, band, chunkX, chunkZ, legacyIds, metadata, tileEntities), sourceVersion);
                    ListTag sections = level.getList("Sections", Tag.TAG_COMPOUND);
                    for (int s = 0; s < sections.size(); s++) {
                        CompoundTag section = sections.getCompound(s);
                        ListTag sectionPalette = section.getList("Palette", Tag.TAG_COMPOUND);
                        long[] data = section.getLongArray("BlockStates");
                        int bits = data.length / 64;
                        if (sectionPalette.isEmpty() || bits == 0) {
                            continue;
                        }
                        int[] remap = new int[sectionPalette.size()];
                        for (int i = 0; i < remap.length; i++) {
                            CompoundTag stateTag = sectionPalette.getCompound(i);
                            Integer known = stateIndex.get(stateTag);
                            if (known == null) {
                                BlockState state = BlockStates.read(BlockStates.upgrade(stateTag, FLATTENED_DATA_VERSION), unknownBlocks);
                                known = state == null
                                        ? Schematic.NO_BLOCK
                                        : paletteIndex.computeIfAbsent(state, st -> {
                                            palette.add(st);
                                            return palette.size() - 1;
                                        });
                                stateIndex.put(stateTag, known);
                            }
                            remap[i] = known;
                        }
                        int baseY = band * CHUNK_HEIGHT + section.getByte("Y") * 16;
                        for (int i = 0; i < 4096; i++) {
                            int x = chunkX * 16 + (i & 15);
                            int y = baseY + (i >> 8);
                            int z = chunkZ * 16 + (i >> 4 & 15);
                            if (x >= width || y >= height || z >= length) {
                                continue;
                            }
                            int index = Schematic.index(size, x, y, z);
                            int value = BlockStates.unpack(data, bits, i);
                            if (legacyIds[index] >= 0 && value < remap.length) {
                                blocks[index] = remap[value];
                            }
                        }
                    }
                    ListTag upgradedEntities = level.getList("TileEntities", Tag.TAG_COMPOUND);
                    for (int i = 0; i < upgradedEntities.size(); i++) {
                        CompoundTag blockEntity = upgradedEntities.getCompound(i).copy();
                        int x = blockEntity.getInt("x");
                        int y = blockEntity.getInt("y") + band * CHUNK_HEIGHT;
                        int z = blockEntity.getInt("z");
                        if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= length || blocks[Schematic.index(size, x, y, z)] == Schematic.NO_BLOCK) {
                            continue;
                        }
                        blockEntity.remove("x");
                        blockEntity.remove("y");
                        blockEntity.remove("z");
                        blockEntities.put(new BlockPos(x, y, z), BlockStates.upgradeBlockEntity(blockEntity, FLATTENED_DATA_VERSION));
                    }
                }
            }
        }

        return new Schematic(size, pasteOffset, palette, blocks, blockEntities, unknownBlocks);
    }

    private static boolean known(int id, Boolean[] knownIds) {
        if (knownIds[id] == null) {
            knownIds[id] = id == 0 || !BlockStateData.getTag(id << 4).get("Name").asString("").equals("minecraft:air");
        }
        return knownIds[id];
    }

    private static CompoundTag chunk(Vec3i size, int band, int chunkX, int chunkZ, int[] legacyIds, byte[] metadata, ListTag tileEntities) {
        ListTag sections = new ListTag();
        for (int sectionY = 0; sectionY < 16; sectionY++) {
            int baseY = band * CHUNK_HEIGHT + sectionY * 16;
            if (baseY >= size.getY()) {
                break;
            }
            byte[] blocks = new byte[4096];
            byte[] data = new byte[2048];
            for (int i = 0; i < 4096; i++) {
                int x = chunkX * 16 + (i & 15);
                int y = baseY + (i >> 8);
                int z = chunkZ * 16 + (i >> 4 & 15);
                if (x >= size.getX() || y >= size.getY() || z >= size.getZ()) {
                    continue;
                }
                int index = Schematic.index(size, x, y, z);
                if (legacyIds[index] <= 0) {
                    continue;
                }
                blocks[i] = (byte) legacyIds[index];
                int meta = metadata[index] & 0x0F;
                data[i >> 1] |= (byte) ((i & 1) == 0 ? meta : meta << 4);
            }
            CompoundTag section = new CompoundTag();
            section.putByte("Y", (byte) sectionY);
            section.putByteArray("Blocks", blocks);
            section.putByteArray("Data", data);
            sections.add(section);
        }

        ListTag chunkEntities = new ListTag();
        for (int i = 0; i < tileEntities.size(); i++) {
            CompoundTag blockEntity = tileEntities.getCompound(i);
            int x = blockEntity.getInt("x");
            int y = blockEntity.getInt("y") - band * CHUNK_HEIGHT;
            int z = blockEntity.getInt("z");
            if (x >> 4 == chunkX && z >> 4 == chunkZ && y >= 0 && y < CHUNK_HEIGHT && x >= 0 && z >= 0) {
                CompoundTag copy = blockEntity.copy();
                copy.putInt("y", y);
                chunkEntities.add(copy);
            }
        }

        CompoundTag level = new CompoundTag();
        level.putInt("xPos", chunkX);
        level.putInt("zPos", chunkZ);
        level.put("Sections", sections);
        level.put("TileEntities", chunkEntities);
        CompoundTag chunk = new CompoundTag();
        chunk.put("Level", level);
        return chunk;
    }

    private static CompoundTag upgradeChunk(CompoundTag chunk, int sourceVersion) {
        CompoundTag upgraded = (CompoundTag) DataFixers.getDataFixer()
                .update(References.CHUNK, new Dynamic<>(NbtOps.INSTANCE, chunk), sourceVersion, FLATTENED_DATA_VERSION)
                .getValue();
        return upgraded.getCompound("Level");
    }
}
