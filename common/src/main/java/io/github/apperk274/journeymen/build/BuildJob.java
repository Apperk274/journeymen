package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.schematic.Schematic;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

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

    private final String file;
    private final BlockPos origin;
    private final Rotation rotation;
    private final BuildOptions options;
    private final BoundingBox area;
    private Phase phase;
    private Phase resumePhase;
    @Nullable
    private String pauseReason;
    @Nullable
    private Schematic schematic;
    @Nullable
    private BuildPlan plan;
    private boolean loading;

    public BuildJob(String file, BlockPos origin, Rotation rotation, BuildOptions options, BoundingBox area, Phase phase) {
        this.file = file;
        this.origin = origin.immutable();
        this.rotation = rotation;
        this.options = options;
        this.area = area;
        this.phase = phase;
        this.resumePhase = phase;
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

    public Phase getPhase() {
        return phase;
    }

    public void setPhase(Phase phase) {
        this.phase = phase;
        if (phase != Phase.PAUSED && phase != Phase.LOADING) {
            this.resumePhase = phase;
            this.pauseReason = null;
        }
    }

    public Phase getResumePhase() {
        return resumePhase;
    }

    public void pause(String reason) {
        if (phase != Phase.PAUSED && phase != Phase.LOADING) {
            resumePhase = phase;
        }
        phase = Phase.PAUSED;
        pauseReason = reason;
    }

    @Nullable
    public String getPauseReason() {
        return pauseReason;
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
        tag.putString("File", file);
        tag.putIntArray("Origin", new int[]{origin.getX(), origin.getY(), origin.getZ()});
        tag.putString("Rotation", rotation.name());
        tag.put("Options", options.save());
        tag.putIntArray("Area", new int[]{area.minX(), area.minY(), area.minZ(), area.maxX(), area.maxY(), area.maxZ()});
        tag.putString("Phase", (phase == Phase.LOADING ? resumePhase : phase).name());
        tag.putString("ResumePhase", resumePhase.name());
        if (pauseReason != null) {
            tag.putString("PauseReason", pauseReason);
        }
        return tag;
    }

    @Nullable
    public static BuildJob load(CompoundTag tag) {
        int[] origin = tag.getIntArray("Origin");
        int[] area = tag.getIntArray("Area");
        if (origin.length != 3 || area.length != 6 || tag.getString("File").isEmpty()) {
            return null;
        }
        Rotation rotation;
        Phase resumePhase;
        try {
            rotation = Rotation.valueOf(tag.getString("Rotation").toUpperCase(Locale.ROOT));
            resumePhase = Phase.valueOf(tag.getString("ResumePhase"));
        } catch (IllegalArgumentException e) {
            return null;
        }
        BuildJob job = new BuildJob(tag.getString("File"), new BlockPos(origin[0], origin[1], origin[2]), rotation,
                BuildOptions.load(tag.getCompound("Options")),
                new BoundingBox(area[0], area[1], area[2], area[3], area[4], area[5]), Phase.LOADING);
        job.resumePhase = resumePhase == Phase.LOADING ? Phase.WALKING : resumePhase;
        job.pauseReason = tag.contains("PauseReason") ? tag.getString("PauseReason") : null;
        return job;
    }
}
