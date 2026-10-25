package io.github.apperk274.journeymen.network;

import io.github.apperk274.journeymen.schematic.Schematic;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.Deflater;
import java.util.zip.InflaterInputStream;

public final class PreviewBlocks {

    public static final int MAX_BLOCKS = 250_000;
    public static final int PART_BYTES = 512 * 1024;
    private static final int MAX_RAW_BYTES = 64 * 1024 * 1024;

    private final Vec3i size;
    private final int[] positions;
    private final int[] states;
    private final Int2IntOpenHashMap lookup;

    private PreviewBlocks(Vec3i size, int[] positions, int[] states) {
        this.size = size;
        this.positions = positions;
        this.states = states;
        this.lookup = new Int2IntOpenHashMap(positions.length);
        this.lookup.defaultReturnValue(-1);
        for (int i = 0; i < positions.length; i++) {
            lookup.put(positions[i], states[i]);
        }
    }

    public record Encoded(boolean complete, List<byte[]> parts) {
    }

    public static Encoded encode(Schematic schematic) {
        Vec3i size = schematic.getSize();
        List<BlockState> palette = schematic.getPalette();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        int count = 0;
        int run = 0;
        int current = 0;
        for (int y = 0; y < size.getY(); y++) {
            for (int z = 0; z < size.getZ(); z++) {
                for (int x = 0; x < size.getX(); x++) {
                    int index = schematic.paletteIndex(x, y, z);
                    int value = index == Schematic.NO_BLOCK || palette.get(index).isAir() ? 0 : index + 1;
                    if (value != 0 && ++count > MAX_BLOCKS) {
                        return new Encoded(false, List.of());
                    }
                    if (value != current && run > 0) {
                        writeVarInt(raw, run);
                        writeVarInt(raw, current);
                        run = 0;
                    }
                    current = value;
                    run++;
                }
            }
        }
        if (run > 0) {
            writeVarInt(raw, run);
            writeVarInt(raw, current);
        }
        byte[] data = deflate(raw.toByteArray());
        List<byte[]> parts = new ArrayList<>();
        for (int start = 0; start < data.length; start += PART_BYTES) {
            parts.add(Arrays.copyOfRange(data, start, Math.min(data.length, start + PART_BYTES)));
        }
        return new Encoded(true, parts);
    }

    public static PreviewBlocks decode(Vec3i size, int paletteSize, List<byte[]> parts) throws IOException {
        List<InputStream> streams = new ArrayList<>();
        for (byte[] part : parts) {
            streams.add(new ByteArrayInputStream(part));
        }
        long volume = (long) size.getX() * size.getY() * size.getZ();
        if (parts.isEmpty()) {
            return new PreviewBlocks(size, new int[0], new int[0]);
        }
        byte[] raw;
        try (InputStream in = new InflaterInputStream(new SequenceInputStream(Collections.enumeration(streams)))) {
            raw = in.readNBytes(MAX_RAW_BYTES);
        }
        int[] cursor = {0};
        IntArrayList positions = new IntArrayList();
        IntArrayList states = new IntArrayList();
        long position = 0;
        while (position < volume) {
            int run = readVarInt(raw, cursor);
            int value = readVarInt(raw, cursor);
            if (run <= 0 || position + run > volume || value < 0 || value > paletteSize) {
                throw new IOException("Malformed preview data");
            }
            if (value != 0) {
                if (positions.size() + run > MAX_BLOCKS) {
                    throw new IOException("Too many preview blocks");
                }
                for (int i = 0; i < run; i++) {
                    positions.add((int) (position + i));
                    states.add(value - 1);
                }
            }
            position += run;
        }
        return new PreviewBlocks(size, positions.toIntArray(), states.toIntArray());
    }

    public int count() {
        return positions.length;
    }

    public int state(int i) {
        return states[i];
    }

    public int x(int i) {
        return positions[i] % size.getX();
    }

    public int y(int i) {
        return positions[i] / (size.getX() * size.getZ());
    }

    public int z(int i) {
        return positions[i] / size.getX() % size.getZ();
    }

    public int stateAt(int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= size.getX() || y >= size.getY() || z >= size.getZ()) {
            return -1;
        }
        return lookup.get(x + size.getX() * (z + size.getZ() * y));
    }

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater();
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 4));
            byte[] buffer = new byte[65536];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write(value & 0x7F | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    private static int readVarInt(byte[] data, int[] cursor) throws IOException {
        int value = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            if (cursor[0] >= data.length) {
                throw new IOException("Truncated preview data");
            }
            byte b = data[cursor[0]++];
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new IOException("Malformed preview data");
    }
}
