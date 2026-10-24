package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.schematic.Schematic;
import io.github.apperk274.journeymen.schematic.SchematicFiles;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

public final class JobService {

    private JobService() {
    }

    public record Result(@Nullable Component header, List<Component> lines, boolean passed, boolean started, @Nullable String error,
                         int unplaceable, long missing) {

        static Result failure(String error) {
            return new Result(null, List.of(), false, false, error, 0, 0);
        }
    }

    public static void run(MinecraftServer server, BuilderEntity builder, String file, BlockPos origin, Rotation rotation, BuildOptions options,
                           boolean start, Consumer<Result> done) {
        if (start && builder.getJob() != null) {
            done.accept(Result.failure(builder.getBuilderName() + " is already working on " + builder.getJob().getFile() + ". Cancel that job first."));
            return;
        }
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
                    if (!builder.isAlive()) {
                        done.accept(Result.failure(builder.getBuilderName() + " is gone."));
                        return;
                    }
                    done.accept(check(builder, file, origin, rotation, options, start, schematic));
                }, server);
    }

    private static Result check(BuilderEntity builder, String file, BlockPos origin, Rotation rotation, BuildOptions options, boolean start,
                                Schematic schematic) {
        PlacementTransform transform = PlacementTransform.of(schematic, origin, rotation, options.corner());
        PreCheck check = PreCheck.run(builder, schematic, transform, options);
        BoundingBox area = check.getArea();
        Component header = Component.literal((start ? "Build " : "Check ") + file).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" for " + builder.getBuilderName() + " at " + origin.toShortString() + ", rotation "
                        + rotation.getSerializedName() + ", options " + options.describe() + "; area "
                        + area.minX() + " " + area.minY() + " " + area.minZ() + " to " + area.maxX() + " " + area.maxY() + " " + area.maxZ())
                        .withStyle(ChatFormatting.GRAY));
        List<Component> lines = check.lines();
        BuildPlan plan = check.getPlan();
        if (!start) {
            return new Result(header, lines, check.passed(), false, null, check.getUnplaceableCount(), check.getMissingCount());
        }
        if (!check.passed() || plan == null) {
            return new Result(header, lines, false, false, builder.getBuilderName() + " can't start this job yet.", check.getUnplaceableCount(),
                    check.getMissingCount());
        }
        if (builder.getJob() != null) {
            return new Result(header, lines, true, false, builder.getBuilderName() + " got another job in the meantime.", check.getUnplaceableCount(),
                    check.getMissingCount());
        }
        builder.startJob(new BuildJob(file, origin, rotation, options, area, BuildJob.Phase.WALKING), schematic, plan);
        return new Result(header, lines, true, true, null, check.getUnplaceableCount(), check.getMissingCount());
    }
}
