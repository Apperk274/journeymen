package io.github.apperk274.journeymen.command;

import io.github.apperk274.journeymen.build.AssignedChest;
import io.github.apperk274.journeymen.build.BuildJob;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.ForemanEntity;
import io.github.apperk274.journeymen.entity.JourneymanEntity;
import io.github.apperk274.journeymen.entity.JourneymanRegistry;
import io.github.apperk274.journeymen.entity.StuckRecovery;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public final class Status {

    private static final int STUCK_SHOWN_SECONDS = 5;

    private Status() {
    }

    public static List<Component> foreman(MinecraftServer server, ForemanEntity foreman) {
        JourneymanRegistry.Entry entry = JourneymanRegistry.get(server).get(foreman.getUUID()).orElse(null);
        return entry == null ? List.of(Component.literal(foreman.getJourneymanName())) : of(server, entry);
    }

    public static List<Component> of(MinecraftServer server, JourneymanRegistry.Entry entry) {
        List<Component> lines = new ArrayList<>();
        JourneymanEntity journeyman = Targets.find(server, entry);
        lines.add(Component.literal(entry.name()).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" (" + entry.kind().id() + " of " + entry.ownerName() + ")").withStyle(ChatFormatting.GRAY)));
        lines.add(line(position(entry)));
        JourneymanRegistry registry = JourneymanRegistry.get(server);
        if (entry.kind() == JourneymanRegistry.Kind.BUILDER) {
            String foreman = registry.get(entry.foreman()).map(JourneymanRegistry.Entry::name).orElse(null);
            lines.add(line(foreman == null ? "Foreman: none (orphan; adopt with /journeymen foreman adopt <foreman> " + entry.name() + ")"
                    : "Foreman: " + foreman));
        }
        lines.add(line(activity(journeyman)));
        if (journeyman != null) {
            lines.add(line("Health " + Math.round(journeyman.getHealth()) + "/" + Math.round(journeyman.getMaxHealth())));
            lines.addAll(stuck(journeyman));
        }
        if (journeyman instanceof ForemanEntity foreman) {
            foremanJob(server, foreman, lines);
        } else if (journeyman instanceof BuilderEntity builder) {
            builderJob(builder, lines);
        } else {
            BuildJob job = entry.kind() == JourneymanRegistry.Kind.FOREMAN ? registry.jobOf(entry.id()) : null;
            lines.add(line(job != null ? "Job: " + job.getFile() + " at " + job.getOrigin().toShortString() + " (waiting while he isn't loaded)"
                    : entry.kind() == JourneymanRegistry.Kind.FOREMAN ? "Job: none" : "Job: unknown while not loaded"));
        }
        return lines;
    }

    private static void foremanJob(MinecraftServer server, ForemanEntity foreman, List<Component> lines) {
        long supply = foreman.getChests().stream().filter(chest -> chest.role() == AssignedChest.Role.SUPPLY).count();
        long dump = foreman.getChests().size() - supply;
        lines.add(line("Chests: " + supply + " supply, " + dump + " dump"));
        BuildJob job = foreman.getJob();
        if (job == null) {
            lines.add(line("Job: none"));
        } else {
            lines.add(line("Job: " + job.getFile() + " at " + job.getOrigin().toShortString() + ", rotation " + job.describeRotation()
                    + ", options " + job.getOptions().describe()));
            ForemanEntity.JobStatus status = foreman.jobStatus();
            lines.add(line("Phase: " + (status.phase() == BuildJob.Phase.PAUSED && status.pauseReason() != null
                    ? "paused: " + status.pauseReason() : status.text())));
        }
        List<JourneymanRegistry.Entry> crew = foreman.crewEntries();
        if (crew.isEmpty()) {
            lines.add(line("Crew: none"));
            return;
        }
        lines.add(line("Crew of " + crew.size() + ":"));
        for (JourneymanRegistry.Entry member : crew) {
            JourneymanEntity loaded = Targets.find(server, member);
            String doing;
            if (!(loaded instanceof BuilderEntity builder)) {
                doing = "not loaded";
            } else if (job != null && builder.getJob() != job) {
                doing = !builder.level().dimension().equals(job.getDimension()) ? "in another dimension"
                        : ForemanEntity.canTakePart(builder, job.getDimension(), job.getArea()) ? "joining" : "too far from the site";
            } else {
                doing = task(builder);
            }
            lines.add(line("  " + member.name() + ": " + doing));
        }
    }

    private static void builderJob(BuilderEntity builder, List<Component> lines) {
        BuildJob job = builder.getJob();
        ForemanEntity foreman = builder.foreman();
        if (job == null) {
            lines.add(line("Job: none"));
            return;
        }
        lines.add(line("Job: " + job.getFile() + (foreman == null ? "" : " for " + foreman.getJourneymanName())));
        lines.add(line("Task: " + task(builder)));
    }

    private static String task(BuilderEntity builder) {
        if (builder.getJob() != null && builder.getWork().getPhase() == BuildJob.Phase.PAUSED && builder.getWork().getPauseReason() != null) {
            return "paused: " + builder.getWork().getPauseReason();
        }
        return builder.progress().text();
    }

    private static List<Component> stuck(JourneymanEntity journeyman) {
        List<Component> lines = new ArrayList<>();
        StuckRecovery recovery = journeyman.getStuckRecovery();
        int stuckSeconds = recovery.getStuckTicks() / 20;
        if (stuckSeconds >= STUCK_SHOWN_SECONDS && recovery.getGoalDescription() != null) {
            int limit = StuckRecovery.limitTicks() / 20;
            lines.add(line("Stuck for " + stuckSeconds + " s on the way to " + recovery.getGoalDescription()
                    + (limit > 0 ? "; it gets itself out after " + limit + " s" : "")));
        }
        StuckRecovery.Escape escape = recovery.getLast();
        if (escape != null) {
            long seconds = Math.max(0, (journeyman.level().getGameTime() - escape.time()) / 20);
            String ago = seconds < 120 ? seconds + " s ago" : seconds / 60 + " min ago";
            lines.add(line("Last got unstuck " + ago + ": from " + escape.from().toShortString() + " to " + escape.to().toShortString()
                    + ", on the way to " + escape.reason()));
        }
        return lines;
    }

    static String position(JourneymanRegistry.Entry entry) {
        BlockPos pos = entry.pos();
        return "at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " in " + entry.dimension().location();
    }

    static String activity(@Nullable JourneymanEntity journeyman) {
        if (journeyman == null) {
            return "not loaded";
        }
        BuildJob job = journeyman.getJob();
        if (job != null) {
            String phase = journeyman instanceof ForemanEntity foreman ? foreman.jobStatus().text()
                    : ((BuilderEntity) journeyman).getWork().getPhase().description();
            return "working on " + job.getFile() + " (" + phase + ")";
        }
        if (journeyman.getComeTarget() != null) {
            return "walking to you";
        }
        return journeyman.isFollowing() ? "following its owner" : "idle";
    }

    private static Component line(String text) {
        return Component.literal("  " + text);
    }
}
