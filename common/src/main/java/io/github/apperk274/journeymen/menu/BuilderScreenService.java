package io.github.apperk274.journeymen.menu;

import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.build.BlockRequirements;
import io.github.apperk274.journeymen.build.BuildJob;
import io.github.apperk274.journeymen.build.BuildOptions;
import io.github.apperk274.journeymen.build.Crew;
import io.github.apperk274.journeymen.build.JobService;
import io.github.apperk274.journeymen.build.MaterialSupply;
import io.github.apperk274.journeymen.command.SchematicCommands;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.ForemanEntity;
import io.github.apperk274.journeymen.entity.JourneymanEntity;
import io.github.apperk274.journeymen.entity.JourneymanRegistry;
import io.github.apperk274.journeymen.network.PreviewBlocks;
import io.github.apperk274.journeymen.network.ScreenPayloads;
import io.github.apperk274.journeymen.platform.Services;
import io.github.apperk274.journeymen.schematic.MaterialList;
import io.github.apperk274.journeymen.schematic.Schematic;
import io.github.apperk274.journeymen.schematic.SchematicFiles;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

public final class BuilderScreenService {

    private BuilderScreenService() {
    }

    public static void open(ServerPlayer player, BuilderEntity builder, int tab) {
        Services.MENUS.openBuilderMenu(player, builder, tab);
        sendState(player, builder);
        sendSchematicList(player);
    }

    public static void tick(BuilderEntity builder) {
        for (ServerPlayer player : builder.getServer().getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof BuilderMenu menu && menu.getBuilder() == builder) {
                sendState(player, builder);
            }
        }
    }

    public static void sendState(ServerPlayer player, BuilderEntity builder) {
        ForemanEntity foreman = builder.foreman();
        BuildJob job = foreman == null ? null : foreman.getJob();
        ForemanEntity.JobStatus status = foreman == null || job == null ? null : foreman.jobStatus();
        String phase = status != null ? status.text() : foreman == null ? "no foreman" : "idle";
        String detail = status != null && status.phase() == BuildJob.Phase.PAUSED && status.pauseReason() != null ? status.pauseReason() : "";
        Services.NETWORK.sendToPlayer(player, new ScreenPayloads.BuilderState(builder.getId(), builder.getJourneymanName(), builder.getOwnerName(),
                builder.isFollowing(), builder.getHealth(), builder.getMaxHealth(), job == null ? "" : job.getFile(), phase, detail,
                status == null ? 0 : status.done(), status == null ? 0 : status.total(), LinkModeService.chestEntries(foreman), job != null));
    }

    public static void sendSchematicList(ServerPlayer player) {
        List<String> files;
        try {
            files = SchematicFiles.list();
        } catch (IOException e) {
            files = List.of();
        }
        Services.NETWORK.sendToPlayer(player, new ScreenPayloads.SchematicList(files));
    }

    public static void handle(ScreenPayloads.BuilderAction action, ServerPlayer player) {
        Entity entity = player.level().getEntity(action.builderId());
        if (!(entity instanceof JourneymanEntity journeyman) || !journeyman.isAlive()) {
            player.sendSystemMessage(Component.literal("That journeyman is gone.").withStyle(ChatFormatting.RED));
            return;
        }
        if (!journeyman.canBeCommandedBy(player)) {
            player.sendSystemMessage(Component.literal(journeyman.getJourneymanName() + " works for someone else.").withStyle(ChatFormatting.RED));
            return;
        }
        String name = journeyman.getJourneymanName();
        switch (action.action()) {
            case "follow" -> {
                if (journeyman.getJob() != null) {
                    tell(player, name + " is busy with " + journeyman.getJob().getFile() + ". Cancel the job first.", false);
                } else {
                    journeyman.setFollowing(true);
                    tell(player, name + " follows you now.", true);
                }
            }
            case "stay" -> {
                if (journeyman.getJob() != null) {
                    tell(player, name + " is busy with " + journeyman.getJob().getFile() + ". Cancel the job first.", false);
                } else {
                    journeyman.setFollowing(false);
                    tell(player, name + " stays here now.", true);
                }
            }
            case "rename" -> {
                String problem = journeyman.rename(action.text().strip());
                tell(player, problem != null ? problem : name + " is now called " + journeyman.getJourneymanName() + ".", problem == null);
            }
            case "dismiss" -> {
                player.closeContainer();
                journeyman.kill();
                tell(player, "You dismissed " + name + ".", true);
                return;
            }
            case "notices" -> {
                if (!player.getUUID().equals(journeyman.getOwner())) {
                    tell(player, "Only " + journeyman.getOwnerName() + " can read " + name + "'s notices.", false);
                } else {
                    List<String> notices = JourneymanRegistry.get(player.server).takeNotices(player.getUUID());
                    if (notices.isEmpty()) {
                        tell(player, "Your journeymen have nothing new to tell you.", true);
                    }
                    notices.forEach(notice -> player.sendSystemMessage(Component.literal(notice)));
                }
            }
            case "schematic_list" -> sendSchematicList(player);
            case "schematic_info" -> sendSchematicInfo(player, action.text());
            case "cancel_job", "remove_chest", "link_supply", "link_dump", "check", "start", "supply_missing" -> {
                ForemanEntity foreman = foremanFor(player, journeyman);
                if (foreman != null) {
                    foremanAction(player, journeyman, foreman, action);
                }
            }
            default -> {
            }
        }
        if (journeyman instanceof BuilderEntity builder) {
            sendState(player, builder);
        }
    }

    @Nullable
    private static ForemanEntity foremanFor(ServerPlayer player, JourneymanEntity journeyman) {
        if (journeyman instanceof ForemanEntity foreman) {
            return foreman;
        }
        BuilderEntity builder = (BuilderEntity) journeyman;
        ForemanEntity foreman = builder.foreman();
        if (foreman == null) {
            tell(player, builder.getForemanId() == null ? builder.getJourneymanName() + " has no foreman. Another foreman can adopt it with "
                    + "/journeymen foreman adopt <foreman> " + builder.getJourneymanName() + "."
                    : builder.getJourneymanName() + "'s foreman isn't loaded.", false);
        }
        return foreman;
    }

    private static void foremanAction(ServerPlayer player, JourneymanEntity source, ForemanEntity foreman, ScreenPayloads.BuilderAction action) {
        switch (action.action()) {
            case "cancel_job" -> {
                BuildJob job = foreman.getJob();
                if (job != null) {
                    foreman.cancelJob();
                    tell(player, foreman.getJourneymanName() + "'s crew stopped working on " + job.getFile() + ".", true);
                }
            }
            case "remove_chest" -> foreman.removeChest(action.pos());
            case "link_supply", "link_dump" -> {
                player.closeContainer();
                LinkModeService.start(player, foreman, action.action().equals("link_supply"));
            }
            case "check", "start" -> runJob(player, source, foreman, action, action.action().equals("start") ? JobAction.START : JobAction.CHECK);
            case "supply_missing" -> {
                if (player.isCreative()) {
                    runJob(player, source, foreman, action, JobAction.SUPPLY_MISSING);
                } else {
                    tell(player, "Only players in creative mode can supply missing items.", false);
                }
            }
            default -> {
            }
        }
    }

    private static void tell(ServerPlayer player, String message, boolean good) {
        player.sendSystemMessage(Component.literal(message).withStyle(good ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private enum JobAction {
        CHECK,
        START,
        SUPPLY_MISSING
    }

    private static void runJob(ServerPlayer player, JourneymanEntity source, ForemanEntity foreman, ScreenPayloads.BuilderAction action,
                               JobAction jobAction) {
        String[] parts = action.text().split("\n", -1);
        if (parts.length < 3) {
            return;
        }
        String file = parts[0];
        Rotation rotation = Rotation.NONE;
        for (Rotation candidate : Rotation.values()) {
            if (candidate.getSerializedName().equals(parts[1])) {
                rotation = candidate;
            }
        }
        BuildOptions options;
        try {
            options = BuildOptions.parse(parts[2]);
        } catch (IllegalArgumentException e) {
            tell(player, e.getMessage(), false);
            return;
        }
        BlockPos origin = action.pos();
        Rotation finalRotation = rotation;
        Consumer<JobService.Result> done = result -> {
            List<Component> lines = new ArrayList<>();
            MaterialSupply.Outcome supplied = result.supplied();
            if (supplied != null) {
                lines.add(supplied.summary());
                lines.addAll(supplied.notes());
                player.sendSystemMessage(supplied.summary());
                supplied.notes().forEach(player::sendSystemMessage);
                Constants.LOG.info("[supply] {} for {}: {} The check {} now.", player.getName().getString(), foreman.getJourneymanName(),
                        supplied.summary().getString(), result.passed() ? "passes" : "still fails");
            }
            if (result.header() != null) {
                lines.add(result.header());
            }
            lines.addAll(result.lines());
            Services.NETWORK.sendToPlayer(player, new ScreenPayloads.CheckResult(source.getId(), file, origin, finalRotation.getSerializedName(),
                    options.describe().equals("none") ? "" : options.describe(), lines, result.passed(), result.started(),
                    result.error() == null ? "" : result.error(), result.unplaceable(), (int) Math.min(Integer.MAX_VALUE, result.missing()),
                    result.missingTools()));
            if (result.started()) {
                tell(player, foreman.getJourneymanName() + "'s crew is on the way to build " + file + ": " + Crew.list(result.crew()) + ".", true);
            }
        };
        if (jobAction == JobAction.SUPPLY_MISSING) {
            JobService.supplyMissing(player.server, foreman, file, origin, rotation, options, player, done);
        } else {
            JobService.run(player.server, foreman, file, origin, rotation, options, jobAction == JobAction.START, done);
        }
    }

    private static void sendSchematicInfo(ServerPlayer player, String file) {
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        Schematic schematic = SchematicFiles.load(file);
                        return new LoadedSchematic(schematic, PreviewBlocks.encode(schematic));
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, Util.backgroundExecutor())
                .whenCompleteAsync((loaded, error) -> {
                    if (error != null) {
                        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                        tell(player, "Could not load " + file + ": " + cause.getMessage(), false);
                        return;
                    }
                    Services.NETWORK.sendToPlayer(player, info(file, loaded, player));
                    List<byte[]> parts = loaded.preview().parts();
                    for (int i = 0; i < parts.size(); i++) {
                        Services.NETWORK.sendToPlayer(player, new ScreenPayloads.SchematicBlocks(file, i, parts.get(i)));
                    }
                }, player.server);
    }

    private record LoadedSchematic(Schematic schematic, PreviewBlocks.Encoded preview) {
    }

    private static ScreenPayloads.SchematicInfo info(String file, LoadedSchematic loaded, ServerPlayer player) {
        Schematic schematic = loaded.schematic();
        MaterialList materials = MaterialList.of(schematic, player.registryAccess());
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal(schematic.getSize().getX() + "×" + schematic.getSize().getY() + "×" + schematic.getSize().getZ()
                + ", " + materials.getBlockCount() + " blocks").withStyle(ChatFormatting.GRAY));
        for (Map.Entry<ItemStack, Long> entry : materials.getItems()) {
            lines.add(SchematicCommands.itemLine(entry.getKey(), entry.getValue()));
        }
        for (Map.Entry<BlockRequirements.ToolUse, Integer> entry : materials.getToolUses().entrySet()) {
            lines.add(Component.literal(entry.getValue() + " uses of " + entry.getKey().description()).withStyle(ChatFormatting.GRAY));
        }
        for (Map.Entry<Block, Integer> entry : materials.getUnobtainable().entrySet()) {
            lines.add(Component.literal("Can't be placed by hand: " + entry.getValue() + " ").append(entry.getKey().getName())
                    .withStyle(ChatFormatting.RED));
        }
        boolean complete = loaded.preview().complete();
        return new ScreenPayloads.SchematicInfo(file, new BlockPos(schematic.getSize()), new BlockPos(schematic.getPasteOffset()), lines,
                complete ? schematic.getPalette() : List.of(), loaded.preview().parts().size(), complete);
    }
}
