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
import java.util.Optional;
import java.util.UUID;

public final class BuilderRegistry extends SavedData {

    private static final String DATA_NAME = "journeymen_builders";
    private static final Factory<BuilderRegistry> FACTORY = new Factory<>(BuilderRegistry::new, BuilderRegistry::load, null);

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final Map<UUID, BuildJob> jobs = new LinkedHashMap<>();
    private final Map<UUID, JobArea> legacyJobAreas = new HashMap<>();
    private final Map<UUID, List<String>> notices = new HashMap<>();
    private static final int MAX_NOTICES = 20;

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
        boolean changed = entries.remove(id) != null;
        changed |= legacyJobAreas.remove(id) != null;
        BuildJob job = jobOf(id);
        if (job != null) {
            leaveJob(job, id);
            changed = true;
        }
        if (changed) {
            setDirty();
        }
    }

    public Optional<BuildJob> getJob(@Nullable UUID jobId) {
        return jobId == null ? Optional.empty() : Optional.ofNullable(jobs.get(jobId));
    }

    public boolean hasJob(UUID jobId) {
        return jobs.containsKey(jobId);
    }

    @Nullable
    public BuildJob jobOf(UUID member) {
        for (BuildJob job : jobs.values()) {
            if (job.hasMember(member)) {
                return job;
            }
        }
        return null;
    }

    public void addJob(BuildJob job) {
        jobs.put(job.getId(), job);
        job.getMembers().forEach(legacyJobAreas::remove);
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
            legacyJobAreas.remove(member);
            setDirty();
        }
        return added;
    }

    public boolean leaveJob(BuildJob job, UUID member) {
        if (!job.removeMember(member)) {
            return false;
        }
        if (job.size() == 0) {
            jobs.remove(job.getId());
        }
        setDirty();
        return true;
    }

    public void forgetLegacyJobArea(UUID id) {
        if (legacyJobAreas.remove(id) != null) {
            setDirty();
        }
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
                mapEntry.setValue(new Entry(entry.id, entry.owner, ownerName, entry.name, entry.dimension, entry.pos));
                setDirty();
            }
        }
    }

    public Optional<String> findOverlappingJob(ResourceKey<Level> dimension, BoundingBox box, @Nullable UUID exceptJob) {
        for (BuildJob job : jobs.values()) {
            if (!job.getId().equals(exceptJob) && job.getDimension().equals(dimension) && job.getArea().intersects(box)) {
                return Optional.of(describeCrew(job));
            }
        }
        for (Map.Entry<UUID, JobArea> job : legacyJobAreas.entrySet()) {
            if (job.getValue().dimension().equals(dimension) && job.getValue().box().intersects(box)) {
                Entry entry = entries.get(job.getKey());
                if (entry != null) {
                    return Optional.of(entry.qualifiedName());
                }
            }
        }
        return Optional.empty();
    }

    public String describeCrew(BuildJob job) {
        List<String> names = new ArrayList<>();
        for (UUID member : job.getMembers()) {
            Entry entry = entries.get(member);
            if (entry != null) {
                names.add(entry.qualifiedName());
            }
        }
        return names.isEmpty() ? "nobody" : String.join(", ", names);
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
        ListTag jobList = new ListTag();
        jobs.values().forEach(job -> jobList.add(job.save()));
        tag.put("Jobs", jobList);
        ListTag legacy = new ListTag();
        for (Map.Entry<UUID, JobArea> job : legacyJobAreas.entrySet()) {
            CompoundTag jobTag = new CompoundTag();
            jobTag.putUUID("Id", job.getKey());
            jobTag.putString("Dimension", job.getValue().dimension().location().toString());
            BoundingBox box = job.getValue().box();
            jobTag.putIntArray("Box", new int[]{box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()});
            legacy.add(jobTag);
        }
        tag.put("JobAreas", legacy);
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
        ListTag jobList = tag.getList("Jobs", Tag.TAG_COMPOUND);
        for (int i = 0; i < jobList.size(); i++) {
            BuildJob job = BuildJob.load(jobList.getCompound(i));
            if (job != null && job.size() > 0) {
                registry.jobs.put(job.getId(), job);
            }
        }
        ListTag legacy = tag.getList("JobAreas", Tag.TAG_COMPOUND);
        for (int i = 0; i < legacy.size(); i++) {
            CompoundTag jobTag = legacy.getCompound(i);
            ResourceLocation dimension = ResourceLocation.tryParse(jobTag.getString("Dimension"));
            int[] box = jobTag.getIntArray("Box");
            if (jobTag.hasUUID("Id") && dimension != null && box.length == 6) {
                registry.legacyJobAreas.put(jobTag.getUUID("Id"), new JobArea(ResourceKey.create(Registries.DIMENSION, dimension),
                        new BoundingBox(box[0], box[1], box[2], box[3], box[4], box[5])));
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

    public List<String> namesOf(UUID owner) {
        List<String> names = new ArrayList<>();
        for (Entry entry : ownedBy(owner)) {
            names.add(entry.name);
        }
        return names;
    }

    public record JobArea(ResourceKey<Level> dimension, BoundingBox box) {
    }

    public record Entry(UUID id, UUID owner, String ownerName, String name, ResourceKey<Level> dimension, BlockPos pos) {

        public String qualifiedName() {
            return ownerName + "." + name;
        }
    }
}
