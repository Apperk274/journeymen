package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

public final class BuildJob {

    public enum Phase {
        LOADING("loading the schematic"),
        WALKING("walking to the site"),
        CLEARING("clearing the site"),
        PLACING("placing blocks"),
        PAUSED("paused");

        private final String description;

        Phase(String description) {
            this.description = description;
        }

        public String description() {
            return description;
        }
    }

    private final UUID id;
    private final UUID foreman;
    private final ResourceKey<Level> dimension;
    private final String file;
    private final BlockPos origin;
    private final Rotation rotation;
    private final BuildOptions options;
    private final BoundingBox area;
    private final List<UUID> members = new ArrayList<>();
    @Nullable
    private Schematic schematic;
    @Nullable
    private BuildPlan plan;
    private boolean loading;
    @Nullable
    private NeedsIndex needsIndex;
    private final Map<BlockPos, Integer> redoCounts = new HashMap<>();
    private final List<BlockPos> unclearable = new ArrayList<>();
    private final Set<BlockPos> skippedPositions = new LinkedHashSet<>();
    private final CrewWork crewWork = new CrewWork();
    @Nullable
    private Vec3i schematicSize;
    @Nullable
    private Vec3i pasteOffset;
    private String fileHash = "";

    public BuildJob(UUID id, UUID foreman, ResourceKey<Level> dimension, String file, BlockPos origin, Rotation rotation, BuildOptions options,
                    BoundingBox area) {
        this.id = id;
        this.foreman = foreman;
        this.dimension = dimension;
        this.file = file;
        this.origin = origin.immutable();
        this.rotation = rotation;
        this.options = options;
        this.area = area;
    }

    public UUID getId() {
        return id;
    }

    public UUID getForeman() {
        return foreman;
    }

    public ResourceKey<Level> getDimension() {
        return dimension;
    }

    public String getFile() {
        return file;
    }

    public BlockPos getOrigin() {
        return origin;
    }

    public Rotation getRotation() {
        return rotation;
    }

    public BuildOptions getOptions() {
        return options;
    }

    public BoundingBox getArea() {
        return area;
    }

    public List<UUID> getMembers() {
        return List.copyOf(members);
    }

    public boolean hasMember(UUID member) {
        return members.contains(member);
    }

    public int size() {
        return members.size();
    }

    public boolean addMember(UUID member) {
        if (members.contains(member)) {
            return false;
        }
        members.add(member);
        return true;
    }

    public boolean removeMember(UUID member) {
        return members.remove(member);
    }

    @Nullable
    public Schematic getSchematic() {
        return schematic;
    }

    @Nullable
    public BuildPlan getPlan() {
        return plan;
    }

    public void setLoaded(Schematic schematic, @Nullable BuildPlan plan) {
        this.schematic = schematic;
        this.plan = plan;
        this.loading = false;
        this.needsIndex = null;
        this.schematicSize = schematic.getSize();
        this.pasteOffset = schematic.getPasteOffset();
        this.fileHash = schematic.getFileHash();
    }

    @Nullable
    public String shapeChange(Schematic loaded) {
        Vec3i size = loaded.getSize();
        Vec3i offset = loaded.getPasteOffset();
        boolean sizeChanged = schematicSize != null && !schematicSize.equals(size);
        boolean offsetChanged = pasteOffset != null && !options.corner() && !pasteOffset.equals(offset);
        boolean areaChanged = !PlacementTransform.of(loaded, origin, rotation, options.corner()).area(size).equals(area);
        if (!sizeChanged && !offsetChanged && !areaChanged) {
            return null;
        }
        String now = describeSize(size) + " with paste offset " + describe(offset);
        return schematicSize == null || pasteOffset == null
                ? "it now covers a different area (" + now + ")"
                : "it is now " + now + " instead of " + describeSize(schematicSize) + " with paste offset " + describe(pasteOffset);
    }

    public boolean contentChanged(Schematic loaded) {
        return !fileHash.isEmpty() && !fileHash.equals(loaded.getFileHash());
    }

    private static String describeSize(Vec3i size) {
        return size.getX() + "×" + size.getY() + "×" + size.getZ();
    }

    private static String describe(Vec3i vector) {
        return vector.getX() + " " + vector.getY() + " " + vector.getZ();
    }

    public void setPlan(BuildPlan plan) {
        this.plan = plan;
    }

    public boolean isLoading() {
        return loading;
    }

    public void setLoading(boolean loading) {
        this.loading = loading;
    }

    public Map<BlockPos, Integer> getRedoCounts() {
        return redoCounts;
    }

    public List<BlockPos> getUnclearable() {
        return unclearable;
    }

    public Set<BlockPos> getSkippedPositions() {
        return skippedPositions;
    }

    public CrewWork getCrewWork() {
        return crewWork;
    }

    public ItemCounter remainingNeeds(ServerLevel level, Collection<Item> items, Predicate<BlockPos> include) {
        if (schematic == null || items.isEmpty()) {
            return new ItemCounter();
        }
        if (needsIndex == null) {
            needsIndex = NeedsIndex.create(level, schematic, transform(), options, level.registryAccess());
        }
        return needsIndex.remaining(level, items, include, level.registryAccess());
    }

    public PlacementTransform transform() {
        if (schematic == null) {
            throw new IllegalStateException("Schematic not loaded");
        }
        return PlacementTransform.of(schematic, origin, rotation, options.corner());
    }

    public String describeRotation() {
        return rotation.getSerializedName();
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("Foreman", foreman);
        tag.putString("Dimension", dimension.location().toString());
        tag.putString("File", file);
        tag.putIntArray("Origin", new int[]{origin.getX(), origin.getY(), origin.getZ()});
        tag.putString("Rotation", rotation.name());
        tag.put("Options", options.save());
        tag.putIntArray("Area", new int[]{area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(), area.maxZ()});
        ListTag memberList = new ListTag();
        members.forEach(member -> memberList.add(new IntArrayTag(UUIDUtil.uuidToIntArray(member))));
        tag.put("Members", memberList);
        ListTag redoList = new ListTag();
        redoCounts.forEach((pos, count) -> redoList.add(new IntArrayTag(new int[]{pos.getX(), pos.getY(), pos.getZ(), count})));
        tag.put("RedoCounts", redoList);
        tag.put("Unclearable", positionList(unclearable));
        Set<BlockPos> skipped = new LinkedHashSet<>(skippedPositions);
        if (crewWork.getPlacing() != null) {
            skipped.addAll(crewWork.getPlacing().getSkipped());
        }
        tag.put("Skipped", positionList(skipped));
        if (schematicSize != null && pasteOffset != null) {
            tag.putIntArray("SchematicSize", new int[]{schematicSize.getX(), schematicSize.getY(), schematicSize.getZ()});
            tag.putIntArray("PasteOffset", new int[]{pasteOffset.getX(), pasteOffset.getY(), pasteOffset.getZ()});
        }
        if (!fileHash.isEmpty()) {
            tag.putString("FileHash", fileHash);
        }
        return tag;
    }

    private static ListTag positionList(Collection<BlockPos> positions) {
        ListTag list = new ListTag();
        positions.forEach(pos -> list.add(new IntArrayTag(new int[]{pos.getX(), pos.getY(), pos.getZ()})));
        return list;
    }

    private static List<BlockPos> loadPositions(CompoundTag tag, String key) {
        List<BlockPos> positions = new ArrayList<>();
        ListTag list = tag.getList(key, Tag.TAG_INT_ARRAY);
        for (int i = 0; i < list.size(); i++) {
            int[] pos = list.getIntArray(i);
            if (pos.length == 3) {
                positions.add(new BlockPos(pos[0], pos[1], pos[2]));
            }
        }
        return positions;
    }

    @Nullable
    public static BuildJob load(CompoundTag tag) {
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
        if (!tag.hasUUID("Id") || !tag.hasUUID("Foreman") || dimension == null) {
            return null;
        }
        int[] origin = tag.getIntArray("Origin");
        int[] area = tag.getIntArray("Area");
        if (origin.length != 3 || area.length != 6 || tag.getString("File").isEmpty()) {
            return null;
        }
        Rotation rotation;
        try {
            rotation = Rotation.valueOf(tag.getString("Rotation").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        BuildJob job = new BuildJob(tag.getUUID("Id"), tag.getUUID("Foreman"), ResourceKey.create(Registries.DIMENSION, dimension), tag.getString("File"),
                new BlockPos(origin[0], origin[1], origin[2]), rotation, BuildOptions.load(tag.getCompound("Options")),
                new BoundingBox(area[0], area[1], area[2], area[3], area[4], area[5]));
        ListTag memberList = tag.getList("Members", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < memberList.size(); i++) {
            int[] uuid = memberList.getIntArray(i);
            if (uuid.length == 4) {
                job.addMember(UUIDUtil.uuidFromIntArray(uuid));
            }
        }
        ListTag redoList = tag.getList("RedoCounts", Tag.TAG_INT_ARRAY);
        for (int i = 0; i < redoList.size(); i++) {
            int[] redo = redoList.getIntArray(i);
            if (redo.length == 4) {
                job.redoCounts.put(new BlockPos(redo[0], redo[1], redo[2]), redo[3]);
            }
        }
        job.unclearable.addAll(loadPositions(tag, "Unclearable"));
        job.skippedPositions.addAll(loadPositions(tag, "Skipped"));
        int[] size = tag.getIntArray("SchematicSize");
        int[] offset = tag.getIntArray("PasteOffset");
        if (size.length == 3 && offset.length == 3) {
            job.schematicSize = new Vec3i(size[0], size[1], size[2]);
            job.pasteOffset = new Vec3i(offset[0], offset[1], offset[2]);
        }
        job.fileHash = tag.getString("FileHash");
        return job;
    }
}
