package io.github.apperk274.journeymen.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.BuilderNames;
import io.github.apperk274.journeymen.entity.BuilderRegistry;
import io.github.apperk274.journeymen.entity.ModEntities;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;

public class BuilderCommands {

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("builder")
                .then(Commands.literal("summon")
                        .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> summon(context, null))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(context -> summon(context, StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("list")
                        .executes(context -> list(context, false))
                        .then(Commands.literal("all")
                                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                                .executes(context -> list(context, true))))
                .then(Commands.literal("status").then(BuilderTargets.argument().executes(BuilderCommands::status)))
                .then(Commands.literal("rename").then(BuilderTargets.argument()
                        .then(Commands.argument("name", StringArgumentType.string()).executes(BuilderCommands::rename))))
                .then(Commands.literal("come").then(BuilderTargets.argument().executes(BuilderCommands::come)))
                .then(Commands.literal("follow").then(BuilderTargets.argument().executes(context -> setFollowing(context, true))))
                .then(Commands.literal("stay").then(BuilderTargets.argument().executes(context -> setFollowing(context, false))))
                .then(Commands.literal("kill").then(BuilderTargets.argument().executes(BuilderCommands::kill)));
    }

    private static int summon(CommandContext<CommandSourceStack> context, @Nullable String requestedName) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = source.getLevel();
        BuilderRegistry registry = BuilderRegistry.get(source.getServer());
        String name = requestedName;
        if (name == null) {
            name = BuilderNames.random(level.getRandom(), new HashSet<>(registry.namesOf(player.getUUID())));
        } else {
            String problem = BuilderNames.validate(name);
            if (problem != null) {
                throw JourneymenCommand.fail(problem);
            }
            if (registry.isNameTaken(player.getUUID(), name, null)) {
                throw JourneymenCommand.fail("You already have a builder named " + name + ".");
            }
        }
        BuilderEntity builder = ModEntities.BUILDER.create(level);
        if (builder == null) {
            throw JourneymenCommand.fail("Could not create a builder.");
        }
        builder.moveTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), 0.0F);
        builder.setYHeadRot(player.getYRot());
        builder.assignOwner(player, name);
        if (!level.addFreshEntity(builder)) {
            registry.remove(builder.getUUID());
            throw JourneymenCommand.fail("Could not create a builder.");
        }
        String summonedName = name;
        source.sendSuccess(() -> Component.literal("Summoned " + summonedName + ". Right-click it to open its inventory."), false);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context, boolean all) {
        CommandSourceStack source = context.getSource();
        BuilderRegistry registry = BuilderRegistry.get(source.getServer());
        ServerPlayer player = source.getPlayer();
        boolean showAll = all || player == null;
        Collection<BuilderRegistry.Entry> entries = showAll ? registry.all() : registry.ownedBy(player.getUUID());
        if (entries.isEmpty()) {
            source.sendSuccess(() -> Component.literal(showAll ? "There are no builders." : "You have no builders."), false);
            return 0;
        }
        List<BuilderRegistry.Entry> sorted = new ArrayList<>(entries);
        sorted.sort((a, b) -> a.qualifiedName().compareToIgnoreCase(b.qualifiedName()));
        for (BuilderRegistry.Entry entry : sorted) {
            BuilderEntity builder = BuilderTargets.find(source.getServer(), entry);
            String label = showAll ? entry.qualifiedName() : entry.name();
            source.sendSuccess(() -> Component.literal(label).withStyle(ChatFormatting.YELLOW)
                    .append(Component.literal(" - " + describePosition(entry) + ", " + describeActivity(builder))
                            .withStyle(ChatFormatting.GRAY)), false);
        }
        return sorted.size();
    }

    private static int status(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BuilderRegistry.Entry entry = BuilderTargets.resolve(context);
        BuilderEntity builder = BuilderTargets.find(source.getServer(), entry);
        source.sendSuccess(() -> Component.literal(entry.name()).withStyle(ChatFormatting.GOLD)
                .append(Component.literal(" (owner " + entry.ownerName() + ")").withStyle(ChatFormatting.GRAY)), false);
        source.sendSuccess(() -> Component.literal("  " + describePosition(entry)), false);
        source.sendSuccess(() -> Component.literal("  " + describeActivity(builder)), false);
        if (builder != null) {
            source.sendSuccess(() -> Component.literal("  Health " + Math.round(builder.getHealth()) + "/" + Math.round(builder.getMaxHealth())), false);
        }
        source.sendSuccess(() -> Component.literal("  Job: none"), false);
        return 1;
    }

    private static int rename(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        BuilderEntity builder = BuilderTargets.resolveLoaded(context);
        String oldName = builder.getBuilderName();
        String newName = StringArgumentType.getString(context, "name");
        String problem = builder.rename(newName);
        if (problem != null) {
            throw JourneymenCommand.fail(problem);
        }
        context.getSource().sendSuccess(() -> Component.literal(oldName + " is now called " + newName + "."), false);
        return 1;
    }

    private static int come(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BuilderEntity builder = BuilderTargets.resolveLoaded(context);
        if (builder.level() != source.getLevel()) {
            throw JourneymenCommand.fail(builder.getBuilderName() + " is in another dimension.");
        }
        BlockPos target = BlockPos.containing(source.getPosition());
        builder.comeTo(target);
        source.sendSuccess(() -> Component.literal(builder.getBuilderName() + " is coming to " + target.getX() + " " + target.getY() + " " + target.getZ() + "."), false);
        return 1;
    }

    private static int setFollowing(CommandContext<CommandSourceStack> context, boolean following) throws CommandSyntaxException {
        BuilderEntity builder = BuilderTargets.resolveLoaded(context);
        builder.setFollowing(following);
        String message = following ? builder.getBuilderName() + " follows its owner now." : builder.getBuilderName() + " stays here now.";
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int kill(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        BuilderEntity builder = BuilderTargets.resolveLoaded(context);
        String name = builder.getBuilderName();
        builder.kill();
        context.getSource().sendSuccess(() -> Component.literal("Killed " + name + "."), false);
        return 1;
    }

    private static String describePosition(BuilderRegistry.Entry entry) {
        BlockPos pos = entry.pos();
        return "at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " in " + entry.dimension().location();
    }

    private static String describeActivity(@Nullable BuilderEntity builder) {
        if (builder == null) {
            return "not loaded";
        }
        if (builder.getComeTarget() != null) {
            return "walking to you";
        }
        return builder.isFollowing() ? "following its owner" : "idle";
    }
}
