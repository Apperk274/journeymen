package io.github.apperk274.journeymen.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.ForemanEntity;
import io.github.apperk274.journeymen.entity.JourneymanRegistry;
import io.github.apperk274.journeymen.hire.HireService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

public class BuilderCommands {

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("builder")
                .then(Commands.literal("summon")
                        .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Targets.argument(Targets.FOREMAN, JourneymanRegistry.Kind.FOREMAN)
                                .executes(context -> summon(context, null))
                                .then(Commands.argument("name", StringArgumentType.string())
                                        .executes(context -> summon(context, StringArgumentType.getString(context, "name"))))));
        return JourneymanCommands.common(root, JourneymanRegistry.Kind.BUILDER);
    }

    private static int summon(CommandContext<CommandSourceStack> context, @Nullable String requestedName) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ForemanEntity foreman = Targets.foreman(context, Targets.FOREMAN);
        if (foreman.getOwner() == null) {
            throw JourneymenCommand.fail(foreman.getJourneymanName() + " has no owner.");
        }
        if (foreman.isCrewFull()) {
            throw JourneymenCommand.fail(foreman.getJourneymanName() + "'s crew is full (" + JourneymenConfig.MAX_CREW_SIZE.get() + " builders).");
        }
        JourneymanRegistry registry = JourneymanRegistry.get(source.getServer());
        String name = HireService.pickName(registry, foreman.getOwner(), player.getRandom(), requestedName);
        String problem = HireService.nameProblem(registry, foreman.getOwner(), foreman.getOwnerName(), name, foreman.getOwner().equals(player.getUUID()));
        if (problem != null) {
            throw JourneymenCommand.fail(problem);
        }
        BuilderEntity builder = HireService.spawnBuilder(source.getLevel(), foreman, name, player.position(), player.getYRot());
        if (builder == null) {
            throw JourneymenCommand.fail("Could not create a builder.");
        }
        source.sendSuccess(() -> Component.literal("Summoned " + name + " into " + foreman.getJourneymanName() + "'s crew."), false);
        return 1;
    }
}
