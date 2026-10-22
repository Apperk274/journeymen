package io.github.apperk274.journeymen.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.ModEntities;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class JourneymenCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal(Constants.MOD_ID)
                .executes(JourneymenCommand::help)
                .then(Commands.literal("help").executes(JourneymenCommand::help))
                .then(Commands.literal("builder")
                        .then(Commands.literal("summon")
                                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(JourneymenCommand::summonBuilder))));
    }

    private static int help(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        source.sendSystemMessage(Component.literal(Constants.MOD_NAME)
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)
                .append(Component.literal(" is loaded on " + Services.PLATFORM.getPlatformName())
                        .withStyle(ChatFormatting.GRAY)));
        source.sendSystemMessage(entry("help", "Show this help"));
        source.sendSystemMessage(entry("builder summon", "Summon a builder that works for you"));
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
}
