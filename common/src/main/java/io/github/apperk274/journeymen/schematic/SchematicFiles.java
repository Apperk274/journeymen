package io.github.apperk274.journeymen.schematic;

import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipException;

public final class SchematicFiles {

    private SchematicFiles() {
    }

    public static Path directory() {
        return Services.PLATFORM.getGameDirectory().resolve("schematics").toAbsolutePath().normalize();
    }

    public static List<String> list() throws IOException {
        Path directory = directory();
        Files.createDirectories(directory);
        try (Stream<Path> files = Files.walk(directory, 8)) {
            return files.filter(Files::isRegularFile)
                    .map(file -> directory.relativize(file).toString().replace('\\', '/'))
                    .filter(SchematicFiles::isSupported)
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        }
    }

    public static Schematic load(String name) throws IOException {
        if (!isSupported(name)) {
            throw new SchematicException("Unsupported file type: " + name + " (expected .schem or .nbt)");
        }
        Path directory = directory();
        Path file = directory.resolve(name).normalize();
        if (!file.startsWith(directory)) {
            throw new SchematicException("Schematics must be inside " + directory);
        }
        if (!Files.isRegularFile(file)) {
            throw new SchematicException("No schematic named " + name + " in " + directory);
        }
        CompoundTag root;
        try {
            root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
        } catch (ZipException e) {
            throw new SchematicException(name + " is not a gzip-compressed NBT file");
        }
        return name.toLowerCase(Locale.ROOT).endsWith(".schem")
                ? SpongeSchematicReader.read(root)
                : StructureNbtReader.read(root);
    }

    private static boolean isSupported(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".schem") || lower.endsWith(".nbt");
    }
}
