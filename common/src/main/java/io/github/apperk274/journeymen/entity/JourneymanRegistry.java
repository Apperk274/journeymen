package io.github.apperk274.journeymen.entity;

import io.github.apperk274.journeymen.build.BuildJob;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class JourneymanRegistry extends SavedData {

    private static final String DATA_NAME = "journeymen_builders";
    private static final Factory<JourneymanRegistry> FACTORY = new Factory<>(JourneymanRegistry::new, JourneymanRegistry::load, null);
    private static final int MAX_NOTICES = 20;

    public enum Kind {
        FOREMAN("foreman"),
        BUILDER("builder");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        static Kind byName(String name) {
            for (Kind kind : values()) {
                if (kind.name().equals(name)) {
                    return kind;
                }
            }
            return BUILDER;
        }
    }

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final Map<UUID, BuildJob> jobs = new LinkedHashMap<>();
    private final Map<UUID, List<String>> notices = new HashMap<>();

    public static JourneymanRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_NAME);
    }

    public Optional<Entry> get(@Nullable UUID id) {
        return id == null ? Optional.empty() : Optional.ofNullable(entries.get(id));
    }

    public Collection<Entry> all() {
        return Collections.unmodifiableCollection(entries.values());
    }

    @Nullable
    public JourneymanEntity loaded(MinecraftServer server, @Nullable UUID id) {
        Entry entry = id == null ? null : entries.get(id);
        if (entry == null) {
            return null;
        }
        ServerLevel level = server.getLevel(entry.dimension);
        Entity entity = level == null ? null : level.getEntity(id);
        if (entity == null) {
            for (ServerLevel other : server.getAllLevels()) {
                entity = other.getEntity(id);
                if (entity != null) {
                    break;
                }
            }
        }
        return entity instanceof JourneymanEntity journeyman && journeyman.isAlive() ? journeyman : null;
    }

    public List<Entry> ownedBy(UUID owner) {
        return entries.values().stream()
                .filter(entry -> entry.owner.equals(owner))
                .sorted(Comparator.comparing(entry -> entry.name.toLowerCase(Locale.ROOT)))
                .toList();
    }

    public List<Entry> ownedBy(UUID owner, Kind kind) {
        return ownedBy(owner).stream().filter(entry -> entry.kind == kind).toList();
    }

    public List<Entry> crewOf(UUID foreman) {
        return entries.values().stream()
                .filter(entry -> entry.kind == Kind.BUILDER && foreman.equals(entry.foreman))
                .sorted(Comparator.comparing(entry -> entry.name.toLowerCase(Locale.ROOT)))
                .toList();
    }

    public List<Entry> orphansOf(UUID owner) {
        return ownedBy(owner, Kind.BUILDER).stream().filter(entry -> entry.foreman == null).toList();
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

    public List<String> namesOf(UUID owner) {
        List<String> names = new ArrayList<>();
        for (Entry entry : ownedBy(owner)) {
            names.add(entry.name);
        }
        return names;
    }

    public void put(UUID id, Kind kind, UUID owner, String ownerName, String name, ResourceKey<Level> dimension, BlockPos pos,
                    @Nullable UUID foreman) {
        Entry entry = entries.get(id);
        Entry updated = new Entry(id, kind, owner, ownerName, name, dimension, pos.immutable(), foreman);
        if (!updated.equals(entry)) {
            entries.put(id, updated);
            setDirty();
        }
    }

    public void setForeman(UUID id, @Nullable UUID foreman) {
        Entry entry = entries.get(id);
        if (entry != null && !Objects.equals(entry.foreman, foreman)) {
            entries.put(id, entry.withForeman(foreman));
            BuildJob job = jobWithMember(id);
            if (job != null && !job.getForeman().equals(foreman)) {
                job.removeMember(id);
            }
            setDirty();
        }
    }

    public void remove(UUID id) {
        Entry entry = entries.remove(id);
        if (entry == null) {
            return;
        }
        if (entry.kind == Kind.FOREMAN) {
            BuildJob job = jobOf(id);
            if (job != null) {
                jobs.remove(job.getId());
            }
            for (Map.Entry<UUID, Entry> member : entries.entrySet()) {
                if (id.equals(member.getValue().foreman)) {
                    member.setValue(member.getValue().withForeman(null));
                }
            }
        } else {
            BuildJob job = jobWithMember(id);
            if (job != null) {
                job.removeMember(id);
            }
        }
        setDirty();
    }

    public Optional<BuildJob> getJob(@Nullable UUID jobId) {
        return jobId == null ? Optional.empty() : Optional.ofNullable(jobs.get(jobId));
    }

    public Collection<BuildJob> jobs() {
        return Collections.unmodifiableCollection(jobs.values());
    }

    public boolean hasJob(UUID jobId) {
        return jobs.containsKey(jobId);
    }

    @Nullable
    public BuildJob jobOf(UUID foreman) {
        for (BuildJob job : jobs.values()) {
            if (job.getForeman().equals(foreman)) {
                return job;
            }
        }
        return null;
    }

    @Nullable
    public BuildJob jobWithMember(UUID builder) {
        for (BuildJob job : jobs.values()) {
            if (job.hasMember(builder)) {
                return job;
            }
        }
        return null;
    }

    public void addJob(BuildJob job) {
        jobs.put(job.getId(), job);
        setDirty();
    }

    public void removeJob(UUID jobId) {
        if (jobs.remove(jobId) != null) {
            setDirty();
        }
    }

    public boolean joinJob(BuildJob job, UUID member) {
        boolean added = job.addMember(member);
        if (added) {
            setDirty();
        }
        return added;
    }

    public boolean leaveJob(BuildJob job, UUID member) {
        if (!job.removeMember(member)) {
            return false;
        }
        setDirty();
        return true;
    }

    public void addNotice(UUID owner, String notice) {
        List<String> list = notices.computeIfAbsent(owner, key -> new ArrayList<>());
        list.remove(notice);
        list.add(notice);
        while (list.size() > MAX_NOTICES) {
            list.remove(0);
        }
        setDirty();
    }

    public List<String> takeNotices(UUID owner) {
        List<String> list = notices.remove(owner);
        if (list == null) {
            return List.of();
        }
        setDirty();
        return list;
    }

    public void updateOwnerName(UUID owner, String ownerName) {
        for (Map.Entry<UUID, Entry> mapEntry : entries.entrySet()) {
            Entry entry = mapEntry.getValue();
            if (entry.owner.equals(owner) && !entry.ownerName.equals(ownerName)) {
                mapEntry.setValue(new Entry(entry.id, entry.kind, entry.owner, ownerName, entry.name, entry.dimension, entry.pos, entry.foreman));
                setDirty();
            }
        }
    }

    public Optional<String> findOverlappingJob(ResourceKey<Level> dimension, BoundingBox box, @Nullable UUID exceptJob) {
        for (BuildJob job : jobs.values()) {
            if (!job.getId().equals(exceptJob) && job.getDimension().equals(dimension) && job.getArea().intersects(box)) {
                return Optional.of(get(job.getForeman()).map(Entry::qualifiedName).orElse("another foreman"));
            }
        }
        return Optional.empty();
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Entry entry : entries.values()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putUUID("Id", entry.id);
            entryTag.putString("Kind", entry.kind.name());
            entryTag.putUUID("Owner", entry.owner);
            entryTag.putString("OwnerName", entry.ownerName);
            entryTag.putString("Name", entry.name);
            entryTag.putString("Dimension", entry.dimension.location().toString());
            entryTag.putIntArray("Pos", new int[]{entry.pos.getX(), entry.pos.getY(), entry.pos.getZ()});
            if (entry.foreman != null) {
                entryTag.putUUID("Foreman", entry.foreman);
            }
            list.add(entryTag);
        }
        tag.put("Journeymen", list);
        ListTag jobList = new ListTag();
        jobs.values().forEach(job -> jobList.add(job.save()));
        tag.put("Jobs", jobList);
        ListTag noticeList = new ListTag();
        for (Map.Entry<UUID, List<String>> owner : notices.entrySet()) {
            CompoundTag ownerTag = new CompoundTag();
            ownerTag.putUUID("Owner", owner.getKey());
            ListTag messages = new ListTag();
            owner.getValue().forEach(message -> messages.add(StringTag.valueOf(message)));
            ownerTag.put("Messages", messages);
            noticeList.add(ownerTag);
        }
        tag.put("Notices", noticeList);
        return tag;
    }

    private static JourneymanRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        JourneymanRegistry registry = new JourneymanRegistry();
        boolean current = tag.contains("Journeymen", Tag.TAG_LIST);
        ListTag list = tag.getList(current ? "Journeymen" : "Builders", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            ResourceLocation dimension = ResourceLocation.tryParse(entryTag.getString("Dimension"));
            int[] pos = entryTag.getIntArray("Pos");
            if (!entryTag.hasUUID("Id") || !entryTag.hasUUID("Owner") || dimension == null || pos.length != 3) {
                continue;
            }
            Kind kind = current ? Kind.byName(entryTag.getString("Kind")) : Kind.BUILDER;
            UUID foreman = current && entryTag.hasUUID("Foreman") ? entryTag.getUUID("Foreman") : null;
            Entry entry = new Entry(entryTag.getUUID("Id"), kind, entryTag.getUUID("Owner"), entryTag.getString("OwnerName"),
                    entryTag.getString("Name"), ResourceKey.create(Registries.DIMENSION, dimension), new BlockPos(pos[0], pos[1], pos[2]), foreman);
            registry.entries.put(entry.id, entry);
        }
        ListTag jobList = tag.getList("Jobs", Tag.TAG_COMPOUND);
        for (int i = 0; i < jobList.size(); i++) {
            BuildJob job = BuildJob.load(jobList.getCompound(i));
            Entry foreman = job == null ? null : registry.entries.get(job.getForeman());
            if (foreman != null && foreman.kind == Kind.FOREMAN) {
                registry.jobs.put(job.getId(), job);
            }
        }
        ListTag noticeList = tag.getList("Notices", Tag.TAG_COMPOUND);
        for (int i = 0; i < noticeList.size(); i++) {
            CompoundTag ownerTag = noticeList.getCompound(i);
            if (!ownerTag.hasUUID("Owner")) {
                continue;
            }
            ListTag messages = ownerTag.getList("Messages", Tag.TAG_STRING);
            List<String> messageList = new ArrayList<>();
            for (int j = 0; j < messages.size(); j++) {
                messageList.add(messages.getString(j));
            }
            registry.notices.put(ownerTag.getUUID("Owner"), messageList);
        }
        return registry;
    }

    public record Entry(UUID id, Kind kind, UUID owner, String ownerName, String name, ResourceKey<Level> dimension, BlockPos pos,
                        @Nullable UUID foreman) {

        public String qualifiedName() {
            return ownerName + "." + name;
        }

        Entry withForeman(@Nullable UUID newForeman) {
            return new Entry(id, kind, owner, ownerName, name, dimension, pos, newForeman);
        }
    }
}
