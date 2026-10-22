package io.github.apperk274.journeymen.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class BuilderRegistry extends SavedData {

    private static final String DATA_NAME = "journeymen_builders";
    private static final Factory<BuilderRegistry> FACTORY = new Factory<>(BuilderRegistry::new, BuilderRegistry::load, null);

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    public static BuilderRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public Optional<Entry> get(UUID id) {
        return Optional.ofNullable(entries.get(id));
    }

    public Collection<Entry> all() {
        return Collections.unmodifiableCollection(entries.values());
    }

    public List<Entry> ownedBy(UUID owner) {
        return entries.values().stream()
                .filter(entry -> entry.owner.equals(owner))
                .sorted(Comparator.comparing(entry -> entry.name.toLowerCase(Locale.ROOT)))
                .toList();
    }

    public Optional<Entry> find(UUID owner, String name) {
        return entries.values().stream()
                .filter(entry -> entry.owner.equals(owner) && entry.name.equalsIgnoreCase(name))
                .findFirst();
    }

    public List<Entry> findByOwnerName(String ownerName, String name) {
        return entries.values().stream()
                .filter(entry -> entry.ownerName.equalsIgnoreCase(ownerName) && entry.name.equalsIgnoreCase(name))
                .toList();
    }

    public boolean isNameTaken(UUID owner, String name, @Nullable UUID except) {
        return find(owner, name).filter(entry -> !entry.id.equals(except)).isPresent();
    }

    public void put(UUID id, UUID owner, String ownerName, String name, ResourceKey<Level> dimension, BlockPos pos) {
        Entry entry = entries.get(id);
        if (entry != null && entry.owner.equals(owner) && entry.ownerName.equals(ownerName) && entry.name.equals(name)
                && entry.dimension.equals(dimension) && entry.pos.equals(pos)) {
            return;
        }
        entries.put(id, new Entry(id, owner, ownerName, name, dimension, pos.immutable()));
        setDirty();
    }

    public void remove(UUID id) {
        if (entries.remove(id) != null) {
            setDirty();
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Entry entry : entries.values()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putUUID("Id", entry.id);
            entryTag.putUUID("Owner", entry.owner);
            entryTag.putString("OwnerName", entry.ownerName);
            entryTag.putString("Name", entry.name);
            entryTag.putString("Dimension", entry.dimension.location().toString());
            entryTag.putIntArray("Pos", new int[]{entry.pos.getX(), entry.pos.getY(), entry.pos.getZ()});
            list.add(entryTag);
        }
        tag.put("Builders", list);
        return tag;
    }

    private static BuilderRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        BuilderRegistry registry = new BuilderRegistry();
        ListTag list = tag.getList("Builders", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            ResourceLocation dimension = ResourceLocation.tryParse(entryTag.getString("Dimension"));
            int[] pos = entryTag.getIntArray("Pos");
            if (!entryTag.hasUUID("Id") || !entryTag.hasUUID("Owner") || dimension == null || pos.length != 3) {
                continue;
            }
            Entry entry = new Entry(entryTag.getUUID("Id"), entryTag.getUUID("Owner"), entryTag.getString("OwnerName"),
                    entryTag.getString("Name"), ResourceKey.create(Registries.DIMENSION, dimension), new BlockPos(pos[0], pos[1], pos[2]));
            registry.entries.put(entry.id, entry);
        }
        return registry;
    }

    public List<String> namesOf(UUID owner) {
        List<String> names = new ArrayList<>();
        for (Entry entry : ownedBy(owner)) {
            names.add(entry.name);
        }
        return names;
    }

    public record Entry(UUID id, UUID owner, String ownerName, String name, ResourceKey<Level> dimension, BlockPos pos) {

        public String qualifiedName() {
            return ownerName + "." + name;
        }
    }
}
