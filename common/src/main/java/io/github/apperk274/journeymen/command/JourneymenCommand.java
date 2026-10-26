package io.github.apperk274.journeymen.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.entity.JourneymanRegistry;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public class JourneymenCommand {

    private static final List<String[]> HELP = List.of(
            new String[]{"help", "Show this help"},
            new String[]{"notices", "Show what your journeymen said while you were away"},
            new String[]{"recruiter hire [name]", "Hire a foreman from the nearest recruiter"},
            new String[]{"foreman summon [name]", "Summon a foreman that works for you (ops)"},
            new String[]{"foreman hire <foreman> [name]", "Hire a builder into a foreman's crew (stand within 8 blocks of him)"},
            new String[]{"foreman adopt <foreman> <builder>", "Give an orphaned builder (its foreman died) to another foreman"},
            new String[]{"foreman check <foreman> <file> [pos] [rotation] [options]", "Run the pre-check for a job without starting it"},
            new String[]{"foreman build <foreman> <file> [pos] [rotation] [options]", "Give a foreman a job; his crew builds it"},
            new String[]{"foreman cancel <foreman>", "Stop a foreman's job; the half-built structure stays"},
            new String[]{"foreman supply <foreman> [<file> [pos] [rotation] [options]]", "Put everything a job needs in his supply chests (ops)"},
            new String[]{"foreman supply <foreman> missing-only", "Supply only what the current job's pre-check finds missing (ops); with a file, add missing-only to the options"},
            new String[]{"foreman chest <foreman> add <pos> supply|dump", "Give a foreman a supply or dump chest"},
            new String[]{"foreman chest <foreman> remove <pos>", "Take a chest away from a foreman"},
            new String[]{"foreman chest <foreman> list", "List a foreman's chests"},
            new String[]{"builder summon <foreman> [name]", "Summon a builder into a foreman's crew (ops)"},
            new String[]{"foreman|builder list", "List your foremen or builders"},
            new String[]{"foreman|builder status <name>", "Show where a journeyman is and what it's doing"},
            new String[]{"foreman|builder rename <name> <new name>", "Rename a journeyman"},
            new String[]{"foreman|builder come <name>", "Call an idle journeyman to where you stand"},
            new String[]{"foreman|builder follow <name>", "Make an idle journeyman follow you"},
            new String[]{"foreman|builder stay <name>", "Make an idle journeyman stay where it is"},
            new String[]{"foreman|builder kill <name>", "Kill a journeyman; a builder drops its inventory, a foreman's job ends"},
            new String[]{"foreman|builder unstick <name>", "Get a stuck journeyman out to a safe spot near where it's going"},
            new String[]{"stall place <pos> [facing]", "Place a Recruiter's Stall without the item (ops)"},
            new String[]{"schematic list", "List the files in the schematics folder"},
            new String[]{"schematic materials <file>", "Show the materials a schematic needs"},
            new String[]{"config reload", "Reload the config file"});

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(Constants.MOD_ID)
                .executes(JourneymenCommand::help)
                .then(Commands.literal("help").executes(JourneymenCommand::help))
                .then(Commands.literal("notices").executes(JourneymenCommand::notices))
                .then(ForemanCommands.build())
                .then(BuilderCommands.build())
                .then(RecruiterCommands.recruiter())
                .then(RecruiterCommands.stall())
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

    private static int notices(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        List<String> notices = JourneymanRegistry.get(source.getServer()).takeNotices(player.getUUID());
        if (notices.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Your journeymen have nothing new to tell you."), false);
            return 0;
        }
        notices.forEach(notice -> source.sendSuccess(() -> Component.literal(notice), false));
        return notices.size();
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
