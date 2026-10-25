package io.github.apperk274.journeymen.build;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

public final class WorkState {

    private BuildJob.Phase phase = BuildJob.Phase.WALKING;
    private BuildJob.Phase resumePhase = BuildJob.Phase.WALKING;
    @Nullable
    private String pauseReason;

    public BuildJob.Phase getPhase() {
        return phase;
    }

    public void setPhase(BuildJob.Phase phase) {
        this.phase = phase;
        if (phase != BuildJob.Phase.PAUSED && phase != BuildJob.Phase.LOADING) {
            this.resumePhase = phase;
            this.pauseReason = null;
        }
    }

    public BuildJob.Phase getResumePhase() {
        return resumePhase;
    }

    public void pause(String reason) {
        if (phase != BuildJob.Phase.PAUSED && phase != BuildJob.Phase.LOADING) {
            resumePhase = phase;
        }
        phase = BuildJob.Phase.PAUSED;
        pauseReason = reason;
    }

    @Nullable
    public String getPauseReason() {
        return pauseReason;
    }

    public void reset() {
        phase = BuildJob.Phase.WALKING;
        resumePhase = BuildJob.Phase.WALKING;
        pauseReason = null;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Phase", (phase == BuildJob.Phase.LOADING ? resumePhase : phase).name());
        tag.putString("ResumePhase", resumePhase.name());
        if (pauseReason != null) {
            tag.putString("PauseReason", pauseReason);
        }
        return tag;
    }

    public void load(CompoundTag tag) {
        BuildJob.Phase resume;
        try {
            resume = BuildJob.Phase.valueOf(tag.getString("ResumePhase"));
        } catch (IllegalArgumentException e) {
            resume = BuildJob.Phase.WALKING;
        }
        phase = BuildJob.Phase.LOADING;
        resumePhase = resume == BuildJob.Phase.LOADING ? BuildJob.Phase.WALKING : resume;
        pauseReason = tag.contains("PauseReason") ? tag.getString("PauseReason") : null;
    }
}
