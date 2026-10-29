package io.github.apperk274.journeymen.schematic;

import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtAccounterException;
import net.minecraft.nbt.NbtException;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ReportedNbtException;
import net.minecraft.nbt.Tag;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import java.util.zip.ZipException;

public final class SchematicFiles {

    public static final String FORMATS = ".schem, .litematic, .schematic or .nbt";
    private static final long MAX_NBT_BYTES = 128L * 1024 * 1024;

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
            throw new SchematicException("Unsupported file type: " + name + " (expected " + FORMATS + ")");
        }
        Path directory = directory();
        Path file = directory.resolve(name).normalize();
        if (!file.startsWith(directory)) {
            throw new SchematicException("Schematics must be inside the schematics folder.");
        }
        if (!Files.isRegularFile(file)) {
            throw new SchematicException("There is no schematic named " + name + " in the schematics folder.");
        }
        CompoundTag root;
        try {
            root = NbtIo.readCompressed(file, NbtAccounter.create(MAX_NBT_BYTES));
        } catch (ZipException e) {
            throw new SchematicException(name + " is not a gzip-compressed NBT file");
        } catch (NbtAccounterException e) {
            throw new SchematicException(name + " is too large to read (more than " + MAX_NBT_BYTES / (1024 * 1024) + " MiB of NBT data).");
        } catch (NbtException | ReportedNbtException e) {
            throw new SchematicException(name + " is not a valid NBT file.");
        } catch (IOException e) {
            throw new SchematicException("Could not read " + name + " (" + e.getClass().getSimpleName() + ").");
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".schem")) {
            return SpongeSchematicReader.read(root);
        }
        if (lower.endsWith(".litematic")) {
            return LitematicaReader.read(root);
        }
        if (lower.endsWith(".schematic")) {
            return root.contains("Materials", Tag.TAG_STRING) ? McEditSchematicReader.read(root) : SpongeSchematicReader.read(root);
        }
        return StructureNbtReader.read(root);
    }

    private static boolean isSupported(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".schem") || lower.endsWith(".litematic") || lower.endsWith(".schematic") || lower.endsWith(".nbt");
    }
}
