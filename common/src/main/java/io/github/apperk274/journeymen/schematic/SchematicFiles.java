package io.github.apperk274.journeymen.schematic;

import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.Util;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtAccounterException;
import net.minecraft.nbt.NbtException;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.ReportedNbtException;
import net.minecraft.nbt.Tag;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipException;

public final class SchematicFiles {

    public static final String FORMATS = ".schem, .litematic, .schematic or .nbt";
    private static final long MAX_NBT_BYTES = 128L * 1024 * 1024;
    private static final int MAX_DEPTH = 8;
    private static final long LIST_CACHE_MILLIS = 2000;
    @Nullable
    private static volatile Listing listing;

    private SchematicFiles() {
    }

    public static Path directory() {
        return Services.PLATFORM.getGameDirectory().resolve("schematics").toAbsolutePath().normalize();
    }

    public static List<String> list() throws IOException {
        Listing cached = listing;
        long now = Util.getMillis();
        if (cached != null && now - cached.time() >= 0 && now - cached.time() < LIST_CACHE_MILLIS) {
            return cached.names();
        }
        Path directory = directory();
        Files.createDirectories(directory);
        List<String> names = new ArrayList<>();
        Files.walkFileTree(directory, EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                String name = directory.relativize(file).toString().replace('\\', '/');
                if (isSupported(name) && (attributes.isRegularFile() || attributes.isSymbolicLink() && Files.isRegularFile(file))) {
                    names.add(name);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, @Nullable IOException e) {
                return FileVisitResult.CONTINUE;
            }
        });
        names.sort(String.CASE_INSENSITIVE_ORDER);
        List<String> result = List.copyOf(names);
        listing = new Listing(now, result);
        return result;
    }

    private record Listing(long time, List<String> names) {
    }

    public static void copy(String from, String to) throws IOException {
        Path target = resolve(to);
        Files.createDirectories(target.getParent());
        Files.copy(resolve(from), target, StandardCopyOption.REPLACE_EXISTING);
        listing = null;
    }

    private static Path resolve(String name) throws SchematicException {
        if (!isSupported(name)) {
            throw new SchematicException("Unsupported file type: " + name + " (expected " + FORMATS + ")");
        }
        Path directory = directory();
        Path file = directory.resolve(name).normalize();
        if (!file.startsWith(directory)) {
            throw new SchematicException("Schematics must be inside the schematics folder.");
        }
        return file;
    }

    public static Schematic load(String name) throws IOException {
        Path file = resolve(name);
        if (!Files.isRegularFile(file)) {
            throw new SchematicException("There is no schematic named " + name + " in the schematics folder.");
        }
        CompoundTag root;
        byte[] bytes;
        try {
            if (Files.size(file) > MAX_NBT_BYTES) {
                throw new SchematicException(tooLarge(name));
            }
            bytes = Files.readAllBytes(file);
            root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.create(MAX_NBT_BYTES));
        } catch (ZipException e) {
            throw new SchematicException(name + " is not a gzip-compressed NBT file");
        } catch (SchematicException e) {
            throw e;
        } catch (NbtAccounterException e) {
            throw new SchematicException(tooLarge(name));
        } catch (NbtException | ReportedNbtException e) {
            throw new SchematicException(name + " is not a valid NBT file.");
        } catch (IOException e) {
            throw new SchematicException("Could not read " + name + " (" + e.getClass().getSimpleName() + ").");
        }
        Schematic schematic = read(name, root);
        schematic.setFileHash(hash(bytes));
        return schematic;
    }

    private static String tooLarge(String name) {
        return name + " is too large to read (more than " + MAX_NBT_BYTES / (1024 * 1024) + " MiB of NBT data).";
    }

    private static Schematic read(String name, CompoundTag root) throws SchematicException {
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

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isSupported(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".schem") || lower.endsWith(".litematic") || lower.endsWith(".schematic") || lower.endsWith(".nbt");
    }
}
