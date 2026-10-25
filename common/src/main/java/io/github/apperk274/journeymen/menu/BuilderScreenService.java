package io.github.apperk274.journeymen.menu;

import io.github.apperk274.journeymen.build.AssignedChest;
import io.github.apperk274.journeymen.build.BlockRequirements;
import io.github.apperk274.journeymen.build.BuildJob;
import io.github.apperk274.journeymen.build.BuildOptions;
import io.github.apperk274.journeymen.build.JobService;
import io.github.apperk274.journeymen.command.SchematicCommands;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.BuilderRegistry;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

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
        BuildJob job = builder.getJob();
        BuilderEntity.Progress progress = builder.progress();
        String detail = job != null && job.getPhase() == BuildJob.Phase.PAUSED && job.getPauseReason() != null ? job.getPauseReason() : "";
        List<ScreenPayloads.ChestEntry> chests = new ArrayList<>();
        for (AssignedChest chest : builder.getChests()) {
            chests.add(new ScreenPayloads.ChestEntry(chest.pos(), chest.role() == AssignedChest.Role.SUPPLY));
        }
        Services.NETWORK.sendToPlayer(player, new ScreenPayloads.BuilderState(builder.getId(), builder.getBuilderName(), builder.getOwnerName(),
                builder.isFollowing(), builder.getHealth(), builder.getMaxHealth(), job == null ? "" : job.getFile(), progress.text(), detail,
                progress.done(), progress.total(), chests, job != null));
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
        if (!(entity instanceof BuilderEntity builder) || !builder.isAlive()) {
            player.sendSystemMessage(Component.literal("That builder is gone.").withStyle(ChatFormatting.RED));
            return;
        }
        if (!builder.canBeCommandedBy(player)) {
            player.sendSystemMessage(Component.literal(builder.getBuilderName() + " works for someone else.").withStyle(ChatFormatting.RED));
            return;
        }
        String name = builder.getBuilderName();
        switch (action.action()) {
            case "follow" -> {
                if (builder.getJob() != null) {
                    tell(player, name + " is busy with " + builder.getJob().getFile() + ". Cancel the job first.", false);
                } else {
                    builder.setFollowing(true);
                    tell(player, name + " follows you now.", true);
                }
            }
            case "stay" -> {
                builder.setFollowing(false);
                tell(player, name + " stays here now.", true);
            }
            case "rename" -> {
                String problem = builder.rename(action.text().strip());
                tell(player, problem != null ? problem : name + " is now called " + builder.getBuilderName() + ".", problem == null);
            }
            case "dismiss" -> {
                player.closeContainer();
                builder.kill();
                tell(player, "You dismissed " + name + ".", true);
                return;
            }
            case "cancel_job" -> {
                BuildJob job = builder.getJob();
                if (job != null) {
                    builder.cancelJob();
                    tell(player, name + " stopped working on " + job.getFile() + ".", true);
                }
            }
            case "remove_chest" -> builder.removeChest(action.pos());
            case "link_supply", "link_dump" -> {
                player.closeContainer();
                LinkModeService.start(player, builder, action.action().equals("link_supply"));
            }
            case "notices" -> {
                if (!player.getUUID().equals(builder.getOwner())) {
                    tell(player, "Only " + builder.getOwnerName() + " can read " + name + "'s notices.", false);
                } else {
                    List<String> notices = BuilderRegistry.get(player.server).takeNotices(player.getUUID());
                    if (notices.isEmpty()) {
                        tell(player, "Your builders have nothing new to tell you.", true);
                    }
                    notices.forEach(notice -> player.sendSystemMessage(Component.literal(notice)));
                }
            }
            case "schematic_list" -> sendSchematicList(player);
            case "schematic_info" -> sendSchematicInfo(player, action.text());
            case "check", "start" -> runJob(player, builder, action, action.action().equals("start"));
            default -> {
            }
        }
        sendState(player, builder);
    }

    private static void tell(ServerPlayer player, String message, boolean good) {
        player.sendSystemMessage(Component.literal(message).withStyle(good ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private static void runJob(ServerPlayer player, BuilderEntity builder, ScreenPayloads.BuilderAction action, boolean start) {
        String[] parts = action.text().split("\n", -1);
        if (parts.length != 3) {
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
        JobService.run(player.server, builder, file, origin, rotation, options, start, result -> {
            List<Component> lines = new ArrayList<>();
            if (result.header() != null) {
                lines.add(result.header());
            }
            lines.addAll(result.lines());
            Services.NETWORK.sendToPlayer(player, new ScreenPayloads.CheckResult(builder.getId(), file, origin, finalRotation.getSerializedName(),
                    options.describe().equals("none") ? "" : options.describe(), lines, result.passed(), result.started(),
                    result.error() == null ? "" : result.error(), result.unplaceable(), (int) Math.min(Integer.MAX_VALUE, result.missing())));
            if (result.started()) {
                tell(player, builder.getBuilderName() + " is on the way to build " + file + ".", true);
            }
        });
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
