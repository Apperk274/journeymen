package io.github.apperk274.journeymen.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.ModEntities;
import io.github.apperk274.journeymen.platform.Services;
import io.github.apperk274.journeymen.schematic.MaterialList;
import io.github.apperk274.journeymen.schematic.Schematic;
import io.github.apperk274.journeymen.schematic.SchematicFiles;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Stream;

public class JourneymenCommand {

    private static final SuggestionProvider<CommandSourceStack> SCHEMATIC_FILES = (context, builder) -> {
        Stream<String> names;
        try {
            names = SchematicFiles.list().stream().map(StringArgumentType::escapeIfRequired);
        } catch (IOException e) {
            names = Stream.empty();
        }
        return SharedSuggestionProvider.suggest(names, builder);
    };

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(Constants.MOD_ID)
                .executes(JourneymenCommand::help)
                .then(Commands.literal("help").executes(JourneymenCommand::help))
                .then(Commands.literal("builder")
                        .then(Commands.literal("summon")
                                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(JourneymenCommand::summonBuilder)))
                .then(Commands.literal("schematic")
                        .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("list").executes(JourneymenCommand::listSchematics))
                        .then(Commands.literal("materials")
                                .then(Commands.argument("file", StringArgumentType.string())
                                        .suggests(SCHEMATIC_FILES)
                                        .executes(JourneymenCommand::showMaterials)))));
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSystemMessage(Component.literal(Constants.MOD_NAME)
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(" is loaded on " + Services.PLATFORM.getPlatformName())
                        .withStyle(ChatFormatting.GRAY)));
        source.sendSystemMessage(entry("help", "Show this help"));
        source.sendSystemMessage(entry("builder summon", "Summon a builder that works for you"));
        source.sendSystemMessage(entry("schematic list", "List the files in the schematics folder"));
        source.sendSystemMessage(entry("schematic materials <file>", "Show the materials a schematic needs"));
        return 1;
    }

    private static Component entry(String command, String description) {
        return Component.literal("/" + Constants.MOD_ID + " " + command).withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" - " + description).withStyle(ChatFormatting.WHITE));
    }

    private static int summonBuilder(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        BuilderEntity builder = ModEntities.BUILDER.create(level);
        if (builder == null) {
            source.sendFailure(Component.literal("Could not create a builder."));
            return 0;
        }
        builder.moveTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), 0.0F);
        builder.setYHeadRot(player.getYRot());
        builder.setOwner(player.getUUID());
        level.addFreshEntity(builder);
        source.sendSuccess(() -> Component.literal("Summoned a builder. Right-click it to open its inventory."), false);
        return 1;
    }

    private static int listSchematics(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<String> names;
        try {
            names = SchematicFiles.list();
        } catch (IOException e) {
            source.sendFailure(Component.literal("Could not read " + SchematicFiles.directory() + ": " + e.getMessage()));
            return 0;
        }
        if (names.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No schematics yet. Put .schem or .nbt files in " + SchematicFiles.directory()), false);
            return 0;
        }
        source.sendSuccess(() -> Component.literal(names.size() + " schematic(s) in " + SchematicFiles.directory() + ":"), false);
        for (String name : names) {
            String command = "/" + Constants.MOD_ID + " schematic materials " + StringArgumentType.escapeIfRequired(name);
            source.sendSuccess(() -> Component.literal("  " + name).withStyle(style -> style
                    .withColor(ChatFormatting.YELLOW)
                    .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, command))
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to show materials")))), false);
        }
        return names.size();
    }

    private static int showMaterials(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String name = StringArgumentType.getString(context, "file");
        CompletableFuture
                .supplyAsync(() -> {
                    try {
                        Schematic schematic = SchematicFiles.load(name);
                        return new LoadedSchematic(schematic, MaterialList.of(schematic));
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, Util.backgroundExecutor())
                .whenCompleteAsync((loaded, error) -> {
                    if (error != null) {
                        Throwable cause = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
                        if (!(cause instanceof IOException)) {
                            Constants.LOG.error("Failed to load schematic {}", name, cause);
                        }
                        source.sendFailure(Component.literal("Could not load " + name + ": " + cause.getMessage()));
                        return;
                    }
                    sendMaterials(source, name, loaded.schematic(), loaded.materials());
                }, source.getServer());
        return 1;
    }

    private static void sendMaterials(CommandSourceStack source, String name, Schematic schematic, MaterialList materials) {
        Vec3i size = schematic.getSize();
        source.sendSuccess(() -> Component.literal(name).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(": " + size.getX() + "×" + size.getY() + "×" + size.getZ()
                        + ", " + materials.getBlockCount() + " blocks").withStyle(ChatFormatting.GRAY)), false);
        if (materials.getItems().isEmpty()) {
            source.sendSuccess(() -> Component.literal("Nothing to place."), false);
        }
        for (Map.Entry<Item, Integer> entry : materials.getItems().entrySet()) {
            Item item = entry.getKey();
            int count = entry.getValue();
            MutableComponent line = Component.literal(String.format("%6d ", count)).withStyle(ChatFormatting.YELLOW)
                    .append(item.getDescription().copy().withStyle(ChatFormatting.WHITE));
            int stackSize = item.getDefaultMaxStackSize();
            if (stackSize > 1 && count > stackSize) {
                int stacks = count / stackSize;
                int rest = count % stackSize;
                line.append(Component.literal(" (" + stacks + "×" + stackSize + (rest > 0 ? " + " + rest : "") + ")")
                        .withStyle(ChatFormatting.GRAY));
            }
            source.sendSuccess(() -> line, false);
        }
        for (Map.Entry<Block, Integer> entry : materials.getUnobtainable().entrySet()) {
            source.sendSuccess(() -> Component.literal("Can't be placed by hand: " + entry.getValue() + " ")
                    .append(entry.getKey().getName())
                    .withStyle(ChatFormatting.RED), false);
        }
        if (!schematic.getUnknownBlocks().isEmpty()) {
            source.sendSuccess(() -> Component.literal("Skipped blocks this game doesn't have (missing mod?): "
                    + String.join(", ", schematic.getUnknownBlocks().stream().sorted().toList()))
                    .withStyle(ChatFormatting.RED), false);
        }
    }

    private record LoadedSchematic(Schematic schematic, MaterialList materials) {
    }
}
