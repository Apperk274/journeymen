package io.github.apperk274.journeymen.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.apperk274.journeymen.build.BuildJob;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.JourneymanEntity;
import io.github.apperk274.journeymen.entity.JourneymanRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class JourneymanCommands {

    private JourneymanCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> common(LiteralArgumentBuilder<CommandSourceStack> root, JourneymanRegistry.Kind kind) {
        String argument = kind.id();
        return root
                .then(Commands.literal("list")
                        .executes(context -> list(context, kind, false))
                        .then(Commands.literal("all")
                                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(context -> list(context, kind, true))))
                .then(Commands.literal("status").then(Targets.argument(argument, kind).executes(context -> status(context, kind))))
                .then(Commands.literal("rename").then(Targets.argument(argument, kind)
                        .then(Commands.argument("name", StringArgumentType.string()).executes(context -> rename(context, kind)))))
                .then(Commands.literal("come").then(Targets.argument(argument, kind).executes(context -> come(context, kind))))
                .then(Commands.literal("follow").then(Targets.argument(argument, kind).executes(context -> setFollowing(context, kind, true))))
                .then(Commands.literal("stay").then(Targets.argument(argument, kind).executes(context -> setFollowing(context, kind, false))))
                .then(Commands.literal("kill").then(Targets.argument(argument, kind).executes(context -> kill(context, kind))))
                .then(Commands.literal("unstick").then(Targets.argument(argument, kind).executes(context -> unstick(context, kind))));
    }

    private static JourneymanEntity loaded(CommandContext<CommandSourceStack> context, JourneymanRegistry.Kind kind) throws CommandSyntaxException {
        return Targets.loaded(context.getSource(), Targets.resolve(context, kind.id(), kind));
    }

    private static int list(CommandContext<CommandSourceStack> context, JourneymanRegistry.Kind kind, boolean all) {
        CommandSourceStack source = context.getSource();
        JourneymanRegistry registry = JourneymanRegistry.get(source.getServer());
        ServerPlayer player = source.getPlayer();
        boolean showAll = all || player == null;
        Collection<JourneymanRegistry.Entry> entries = showAll ? registry.all().stream().filter(entry -> entry.kind() == kind).toList()
                : registry.ownedBy(player.getUUID(), kind);
        String plural = kind == JourneymanRegistry.Kind.FOREMAN ? "foremen" : "builders";
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal(showAll ? "There are no " + plural + "." : "You have no " + plural + "."), false);
            return 0;
        }
        List<JourneymanRegistry.Entry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> a.qualifiedName().compareToIgnoreCase(b.qualifiedName()));
        for (JourneymanRegistry.Entry entry : sorted) {
            JourneymanEntity journeyman = Targets.find(source.getServer(), entry);
            String label = showAll ? entry.qualifiedName() : entry.name();
            String crew = "";
            if (kind == JourneymanRegistry.Kind.BUILDER) {
                crew = registry.get(entry.foreman()).map(foreman -> ", in " + foreman.name() + "'s crew").orElse(", orphan");
            } else {
                int size = registry.crewOf(entry.id()).size();
                crew = ", crew of " + size;
            }
            String line = " - " + Status.position(entry) + ", " + Status.activity(journeyman) + crew;
            source.sendSuccess(() -> Component.literal(label).withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(line).withStyle(ChatFormatting.GRAY)), false);
        }
        return sorted.size();
    }

    private static int status(CommandContext<CommandSourceStack> context, JourneymanRegistry.Kind kind) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        JourneymanRegistry.Entry entry = Targets.resolve(context, kind.id(), kind);
        Status.of(source.getServer(), entry).forEach(line -> source.sendSuccess(() -> line, false));
        return 1;
    }

    private static int rename(CommandContext<CommandSourceStack> context, JourneymanRegistry.Kind kind) throws CommandSyntaxException {
        JourneymanEntity journeyman = loaded(context, kind);
        String oldName = journeyman.getJourneymanName();
        String newName = StringArgumentType.getString(context, "name");
        String problem = journeyman.rename(newName);
        if (problem != null) {
            throw JourneymenCommand.fail(problem);
        }
        context.getSource().sendSuccess(() -> Component.literal(oldName + " is now called " + newName + "."), false);
        return 1;
    }

    private static void refuseWhileWorking(JourneymanEntity journeyman) throws CommandSyntaxException {
        BuildJob job = journeyman.getJob();
        if (job != null) {
            String whose = journeyman instanceof BuilderEntity builder && builder.foreman() != null
                    ? " for " + builder.foreman().getJourneymanName() + ". Cancel his job first." : ". Cancel the job first.";
            throw JourneymenCommand.fail(journeyman.getJourneymanName() + " is busy with " + job.getFile() + whose);
        }
    }

    private static int come(CommandContext<CommandSourceStack> context, JourneymanRegistry.Kind kind) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        JourneymanEntity journeyman = loaded(context, kind);
        if (journeyman.level() != source.getLevel()) {
            throw JourneymenCommand.fail(journeyman.getJourneymanName() + " is in another dimension.");
        }
        refuseWhileWorking(journeyman);
        BlockPos target = BlockPos.containing(source.getPosition());
        journeyman.comeTo(target);
        source.sendSuccess(() -> Component.literal(journeyman.getJourneymanName() + " is coming to " + target.getX() + " " + target.getY() + " "
                + target.getZ() + "."), false);
        return 1;
    }

    private static int setFollowing(CommandContext<CommandSourceStack> context, JourneymanRegistry.Kind kind, boolean following)
            throws CommandSyntaxException {
        JourneymanEntity journeyman = loaded(context, kind);
        refuseWhileWorking(journeyman);
        journeyman.setFollowing(following);
        String message = following ? journeyman.getJourneymanName() + " follows its owner now." : journeyman.getJourneymanName() + " stays here now.";
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int unstick(CommandContext<CommandSourceStack> context, JourneymanRegistry.Kind kind) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        JourneymanEntity journeyman = loaded(context, kind);
        BlockPos here = journeyman.level() == source.getLevel() ? BlockPos.containing(source.getPosition()) : null;
        BlockPos from = journeyman.blockPosition();
        String problem = journeyman.unstick(here);
        if (problem != null) {
            throw JourneymenCommand.fail(problem);
        }
        BlockPos to = journeyman.blockPosition();
        source.sendSuccess(() -> Component.literal(journeyman.getJourneymanName() + " got out of " + from.toShortString() + " to "
                + to.toShortString() + "."), false);
        return 1;
    }

    private static int kill(CommandContext<CommandSourceStack> context, JourneymanRegistry.Kind kind) throws CommandSyntaxException {
        JourneymanEntity journeyman = loaded(context, kind);
        String name = journeyman.getJourneymanName();
        journeyman.kill();
        context.getSource().sendSuccess(() -> Component.literal("Killed " + name + "."), false);
        return 1;
    }
}
