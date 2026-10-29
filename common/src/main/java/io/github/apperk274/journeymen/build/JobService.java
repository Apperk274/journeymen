package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.ForemanEntity;
import io.github.apperk274.journeymen.entity.JourneymanRegistry;
import io.github.apperk274.journeymen.platform.Services;
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

    private static volatile int developmentDelay;

    private JobService() {
    }

    public record Result(@Nullable Component header, List<Component> lines, boolean passed, boolean started, @Nullable String error,
                         int unplaceable, long missing, boolean missingTools, @Nullable MaterialSupply.Outcome supplied,
                         List<String> crew) {

        static Result failure(String error) {
            return new Result(null, List.of(), false, false, error, 0, 0, false, null, List.of());
        }

        Result withSupplied(MaterialSupply.Outcome outcome) {
            return new Result(header, lines, passed, started, error, unplaceable, missing, missingTools, outcome, crew);
        }
    }

    public static void run(MinecraftServer server, ForemanEntity foreman, String file, BlockPos origin, Rotation rotation, BuildOptions options,
                           boolean start, Consumer<Result> done) {
        if (start && foreman.getJob() != null) {
            done.accept(Result.failure(foreman.getJourneymanName() + " is already working on " + foreman.getJob().getFile() + ". Cancel that job first."));
            return;
        }
        load(server, foreman, file, done, schematic -> done.accept(check(foreman, file, origin, rotation, options, start, schematic)));
    }

    public static void supplyMissing(MinecraftServer server, ForemanEntity foreman, String file, BlockPos origin, Rotation rotation,
                                     BuildOptions options, ServerPlayer player, Consumer<Result> done) {
        boolean listed = server.getPlayerList().getPlayer(player.getUUID()) == player;
        load(server, foreman, file, done, schematic -> {
            ServerPlayer current = listed ? server.getPlayerList().getPlayer(player.getUUID()) : player;
            if (current == null || current.hasDisconnected()) {
                done.accept(Result.failure(player.getGameProfile().getName() + " left, so nothing was supplied."));
                return;
            }
            if (!current.isCreative()) {
                done.accept(Result.failure("You aren't in creative mode anymore, so nothing was supplied."));
                return;
            }
            if (!foreman.canBeCommandedBy(current)) {
                done.accept(Result.failure("You can't give " + foreman.getJourneymanName() + " orders anymore, so nothing was supplied."));
                return;
            }
            MaterialSupply.Outcome outcome = MaterialSupply.supply(foreman, schematic, file, origin, rotation, options, true, current);
            done.accept(check(foreman, file, origin, rotation, options, false, schematic).withSupplied(outcome));
        });
    }

    public static void setDevelopmentDelay(int millis) {
        developmentDelay = Math.max(0, millis);
    }

    private static void load(MinecraftServer server, ForemanEntity foreman, String file, Consumer<Result> done, Consumer<Schematic> loaded) {
        int delay = Services.PLATFORM.isDevelopmentEnvironment() ? developmentDelay : 0;
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        if (delay > 0) {
                            Thread.sleep(delay);
                        }
                        return SchematicFiles.load(file);
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
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
                    if (!foreman.isAlive()) {
                        done.accept(Result.failure(foreman.getJourneymanName() + " is gone."));
                        return;
                    }
                    try {
                        loaded.accept(schematic);
                    } catch (RuntimeException e) {
                        Constants.LOG.error("Failed to check schematic {}", file, e);
                        done.accept(Result.failure("Could not check " + file + ": " + e.getMessage()));
                    }
                }, server);
    }

    private static Result check(ForemanEntity foreman, String file, BlockPos origin, Rotation rotation, BuildOptions options, boolean start,
                                Schematic schematic) {
        PlacementTransform transform = PlacementTransform.of(schematic, origin, rotation, options.corner());
        PreCheck check = PreCheck.run(foreman, schematic, transform, options);
        BoundingBox area = check.getArea();
        Component header = Component.literal((start ? "Build " : "Check ") + file).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" for " + foreman.getJourneymanName() + "'s crew at " + origin.toShortString() + ", rotation "
                        + rotation.getSerializedName() + ", options " + options.describe() + "; area "
                        + area.minX() + " " + area.minY() + " " + area.minZ() + " to " + area.maxX() + " " + area.maxY() + " " + area.maxZ())
                        .withStyle(ChatFormatting.GRAY));
        List<Component> lines = check.lines();
        BuildPlan plan = check.getPlan();
        if (!start) {
            return new Result(header, lines, check.passed(), false, null, check.getUnplaceableCount(), check.getMissingCount(),
                    check.isMissingTools(), null, List.of());
        }
        if (!check.passed() || plan == null) {
            return new Result(header, lines, false, false, foreman.getJourneymanName() + " can't start this job yet.", check.getUnplaceableCount(),
                    check.getMissingCount(), check.isMissingTools(), null, List.of());
        }
        if (foreman.getJob() != null) {
            return new Result(header, lines, true, false, foreman.getJourneymanName() + " got another job in the meantime.",
                    check.getUnplaceableCount(), check.getMissingCount(), check.isMissingTools(), null, List.of());
        }
        BuildJob job = new BuildJob(UUID.randomUUID(), foreman.getUUID(), foreman.level().dimension(), file, origin, rotation, options, area);
        job.setLoaded(schematic, plan);
        JourneymanRegistry.get(foreman.getServer()).addJob(job);
        foreman.beginJob(job);
        List<String> crew = Crew.loaded(foreman.getServer(), job).stream().map(BuilderEntity::getJourneymanName).toList();
        return new Result(header, lines, true, true, null, check.getUnplaceableCount(), check.getMissingCount(), check.isMissingTools(), null, crew);
    }
}
