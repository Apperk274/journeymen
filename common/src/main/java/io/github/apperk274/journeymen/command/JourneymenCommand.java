package io.github.apperk274.journeymen.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class JourneymenCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(Constants.MOD_ID)
                .executes(JourneymenCommand::help)
                .then(Commands.literal("help").executes(JourneymenCommand::help)));
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSystemMessage(Component.literal(Constants.MOD_NAME)
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(" is loaded on " + Services.PLATFORM.getPlatformName())
                        .withStyle(ChatFormatting.GRAY)));
        source.sendSystemMessage(entry("/" + Constants.MOD_ID + " help", "Show this help"));
        return 1;
    }

    private static Component entry(String command, String description) {
        return Component.literal(command).withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" - " + description).withStyle(ChatFormatting.WHITE));
    }
}
