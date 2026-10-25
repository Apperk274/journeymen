package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.BuilderRegistry;
import io.github.apperk274.journeymen.schematic.Schematic;
import io.github.apperk274.journeymen.schematic.SchematicFiles;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

public final class JobService {

    private JobService() {
    }

    public record Result(@Nullable Component header, List<Component> lines, boolean passed, boolean started, @Nullable String error,
                         int unplaceable, long missing, boolean missingTools, @Nullable MaterialSupply.Outcome supplied) {

        static Result failure(String error) {
            return new Result(null, List.of(), false, false, error, 0, 0, false, null);
        }

        Result withSupplied(MaterialSupply.Outcome outcome) {
            return new Result(header, lines, passed, started, error, unplaceable, missing, missingTools, outcome);
        }
    }

    public static void run(MinecraftServer server, BuilderEntity builder, String file, BlockPos origin, Rotation rotation, BuildOptions options,
                           boolean start, Consumer<Result> done) {
        run(server, List.of(builder), file, origin, rotation, options, start, done);
    }

    public static void run(MinecraftServer server, List<BuilderEntity> crew, String file, BlockPos origin, Rotation rotation, BuildOptions options,
                           boolean start, Consumer<Result> done) {
        String problem = crewProblem(crew, start);
        if (problem != null) {
            done.accept(Result.failure(problem));
            return;
        }
        load(server, crew, file, done, schematic -> done.accept(check(crew, file, origin, rotation, options, start, schematic)));
    }

    @Nullable
    public static String crewProblem(List<BuilderEntity> crew, boolean start) {
        int max = JourneymenConfig.MAX_CREW_SIZE.get();
        if (max > 0 && crew.size() > max) {
            return "A crew can have at most " + max + " builders.";
        }
        for (BuilderEntity member : crew) {
            if (member.level() != crew.get(0).level()) {
                return member.getBuilderName() + " is in another dimension.";
            }
            if (start && member.getJob() != null) {
                return member.getBuilderName() + " is already working on " + member.getJob().getFile() + ". Cancel that job first.";
            }
        }
        return null;
    }

    public static void supplyMissing(MinecraftServer server, BuilderEntity builder, String file, BlockPos origin, Rotation rotation,
                                     BuildOptions options, ServerPlayer player, Consumer<Result> done) {
        load(server, List.of(builder), file, done, schematic -> {
            MaterialSupply.Outcome outcome = MaterialSupply.supply(builder, schematic, file, origin, rotation, options, true, player);
            done.accept(check(List.of(builder), file, origin, rotation, options, false, schematic).withSupplied(outcome));
        });
    }

    private static void load(MinecraftServer server, List<BuilderEntity> crew, String file, Consumer<Result> done, Consumer<Schematic> loaded) {
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        return SchematicFiles.load(file);
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, Util.backgroundExecutor())
                .whenCompleteAsync((schematic, error) -> {
                    if (error != null) {
                        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                        if (!(cause instanceof IOException)) {
                            Constants.LOG.error("Failed to load schematic {}", file, cause);
                        }
                        done.accept(Result.failure("Could not load " + file + ": " + cause.getMessage()));
                        return;
                    }
                    for (BuilderEntity member : crew) {
                        if (!member.isAlive()) {
                            done.accept(Result.failure(member.getBuilderName() + " is gone."));
                            return;
                        }
                    }
                    loaded.accept(schematic);
                }, server);
    }

    private static Result check(List<BuilderEntity> crew, String file, BlockPos origin, Rotation rotation, BuildOptions options, boolean start,
                                Schematic schematic) {
        BuilderEntity builder = crew.get(0);
        PlacementTransform transform = PlacementTransform.of(schematic, origin, rotation, options.corner());
        PreCheck check = PreCheck.run(crew, schematic, transform, options);
        BoundingBox area = check.getArea();
        Component header = Component.literal((start ? "Build " : "Check ") + file).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" for " + Crew.names(crew) + " at " + origin.toShortString() + ", rotation "
                        + rotation.getSerializedName() + ", options " + options.describe() + "; area "
                        + area.minX() + " " + area.minY() + " " + area.minZ() + " to " + area.maxX() + " " + area.maxY() + " " + area.maxZ())
                        .withStyle(ChatFormatting.GRAY));
        List<Component> lines = check.lines();
        BuildPlan plan = check.getPlan();
        if (!start) {
            return new Result(header, lines, check.passed(), false, null, check.getUnplaceableCount(), check.getMissingCount(),
                    check.isMissingTools(), null);
        }
        if (!check.passed() || plan == null) {
            String who = crew.size() == 1 ? builder.getBuilderName() : "The crew";
            return new Result(header, lines, false, false, who + " can't start this job yet.", check.getUnplaceableCount(),
                    check.getMissingCount(), check.isMissingTools(), null);
        }
        for (BuilderEntity member : crew) {
            if (member.getJob() != null) {
                return new Result(header, lines, true, false, member.getBuilderName() + " got another job in the meantime.",
                        check.getUnplaceableCount(), check.getMissingCount(), check.isMissingTools(), null);
            }
        }
        BuildJob job = new BuildJob(UUID.randomUUID(), builder.level().dimension(), file, origin, rotation, options, area);
        crew.forEach(member -> job.addMember(member.getUUID()));
        job.setLoaded(schematic, plan);
        BuilderRegistry.get(builder.getServer()).addJob(job);
        crew.forEach(member -> member.beginJob(job));
        return new Result(header, lines, true, true, null, check.getUnplaceableCount(), check.getMissingCount(), check.isMissingTools(), null);
    }
}
