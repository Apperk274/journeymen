package io.github.apperk274.journeymen.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.List;

public class JourneymenCommand {

    private static final List<String[]> HELP = List.of(
            new String[]{"help", "Show this help"},
            new String[]{"builder summon [name]", "Summon a builder that works for you"},
            new String[]{"builder list", "List your builders"},
            new String[]{"builder status <builder>", "Show where a builder is and what it's doing"},
            new String[]{"builder rename <builder> <name>", "Rename a builder"},
            new String[]{"builder come <builder>", "Call a builder to where you stand"},
            new String[]{"builder follow <builder>", "Make a builder follow you"},
            new String[]{"builder stay <builder>", "Make a builder stay where it is"},
            new String[]{"builder kill <builder>", "Kill a builder; it drops its inventory"},
            new String[]{"builder check <builder> <file> [pos] [rotation] [options]", "Run the pre-check for a job without starting it"},
            new String[]{"builder build <builder> <file> [pos] [rotation] [options]", "Give a builder a job"},
            new String[]{"builder cancel <builder>", "Stop a builder's job; the half-built structure stays"},
            new String[]{"builder chest <builder> add <pos> supply|dump", "Give a builder a supply or dump chest"},
            new String[]{"builder chest <builder> remove <pos>", "Take a chest away from a builder"},
            new String[]{"builder chest <builder> list", "List a builder's chests"},
            new String[]{"schematic list", "List the files in the schematics folder"},
            new String[]{"schematic materials <file>", "Show the materials a schematic needs"},
            new String[]{"config reload", "Reload the config file"});

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(Constants.MOD_ID)
                .executes(JourneymenCommand::help)
                .then(Commands.literal("help").executes(JourneymenCommand::help))
                .then(BuilderCommands.build())
                .then(SchematicCommands.build())
                .then(Commands.literal("config")
                        .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.literal("reload").executes(JourneymenCommand::reloadConfig)));
        if (Services.PLATFORM.isDevelopmentEnvironment()) {
            root.then(DebugCommands.build());
        }
        dispatcher.register(root);
    }

    static CommandSyntaxException fail(String message) {
        return new SimpleCommandExceptionType(Component.literal(message)).create();
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSystemMessage(Component.literal(Constants.MOD_NAME)
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(" is loaded on " + Services.PLATFORM.getPlatformName())
                        .withStyle(ChatFormatting.GRAY)));
        for (String[] entry : HELP) {
            source.sendSystemMessage(Component.literal("/" + Constants.MOD_ID + " " + entry[0]).withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(" - " + entry[1]).withStyle(ChatFormatting.WHITE)));
        }
        return 1;
    }

    private static int reloadConfig(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<String> problems = JourneymenConfig.load();
        if (problems.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Reloaded " + JourneymenConfig.file()), true);
            return 1;
        }
        source.sendFailure(Component.literal("Reloaded " + JourneymenConfig.file() + " with problems:"));
        problems.forEach(problem -> source.sendFailure(Component.literal("  " + problem)));
        return 0;
    }
}
