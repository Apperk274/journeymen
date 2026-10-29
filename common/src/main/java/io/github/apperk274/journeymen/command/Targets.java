package io.github.apperk274.journeymen.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.ForemanEntity;
import io.github.apperk274.journeymen.entity.JourneymanEntity;
import io.github.apperk274.journeymen.entity.JourneymanRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

final class Targets {

    static final String FOREMAN = "foreman";
    static final String BUILDER = "builder";

    private Targets() {
    }

    private static SuggestionProvider<CommandSourceStack> suggestions(JourneymanRegistry.Kind kind) {
        return (context, builder) -> {
            CommandSourceStack source = context.getSource();
            JourneymanRegistry registry = JourneymanRegistry.get(source.getServer());
            List<String> names = new ArrayList<>();
            ServerPlayer player = source.getPlayer();
            if (player != null) {
                registry.ownedBy(player.getUUID(), kind).forEach(entry -> names.add(entry.name()));
            }
            if (source.hasPermission(Commands.LEVEL_GAMEMASTERS)) {
                registry.all().stream().filter(entry -> entry.kind() == kind).forEach(entry -> names.add(entry.qualifiedName()));
            }
            return SharedSuggestionProvider.suggest(names.stream().map(StringArgumentType::escapeIfRequired), builder);
        };
    }

    static RequiredArgumentBuilder<CommandSourceStack, String> argument(String name, JourneymanRegistry.Kind kind) {
        return Commands.argument(name, StringArgumentType.string()).suggests(suggestions(kind));
    }

    static JourneymanRegistry.Entry resolve(CommandContext<CommandSourceStack> context, String argument, JourneymanRegistry.Kind kind)
            throws CommandSyntaxException {
        return resolve(context.getSource(), context.getArgument(argument, String.class), kind);
    }

    static JourneymanRegistry.Entry resolve(CommandSourceStack source, String raw, JourneymanRegistry.Kind kind) throws CommandSyntaxException {
        JourneymanRegistry registry = JourneymanRegistry.get(source.getServer());
        ServerPlayer player = source.getPlayer();
        JourneymanRegistry.Entry entry;
        int dot = raw.lastIndexOf('.');
        if (dot >= 0) {
            String ownerName = raw.substring(0, dot);
            String name = raw.substring(dot + 1);
            List<JourneymanRegistry.Entry> matches = registry.findByOwnerName(ownerName, name);
            if (matches.isEmpty()) {
                throw JourneymenCommand.fail(ownerName + " has no " + kind.id() + " named " + name + ".");
            }
            entry = matches.get(0);
            boolean own = player != null && entry.owner().equals(player.getUUID());
            if (!own && !source.hasPermission(Commands.LEVEL_GAMEMASTERS)) {
                throw JourneymenCommand.fail("You can only command your own journeymen.");
            }
        } else {
            if (player == null) {
                throw JourneymenCommand.fail("Name the " + kind.id() + " as <owner>.<name> when not running this as a player.");
            }
            entry = registry.find(player.getUUID(), raw)
                    .orElseThrow(() -> JourneymenCommand.fail("You have no " + kind.id() + " named " + raw + "."));
        }
        if (entry.kind() != kind) {
            throw JourneymenCommand.fail(entry.name() + " is a " + entry.kind().id() + ", not a " + kind.id() + ".");
        }
        return entry;
    }

    static ForemanEntity foreman(CommandContext<CommandSourceStack> context, String argument) throws CommandSyntaxException {
        return (ForemanEntity) loaded(context.getSource(), resolve(context, argument, JourneymanRegistry.Kind.FOREMAN));
    }

    static BuilderEntity builder(CommandContext<CommandSourceStack> context, String argument) throws CommandSyntaxException {
        return (BuilderEntity) loaded(context.getSource(), resolve(context, argument, JourneymanRegistry.Kind.BUILDER));
    }

    static JourneymanEntity loaded(CommandSourceStack source, JourneymanRegistry.Entry entry) throws CommandSyntaxException {
        JourneymanEntity journeyman = find(source.getServer(), entry);
        if (journeyman == null) {
            BlockPos pos = entry.pos();
            throw JourneymenCommand.fail(entry.name() + " is in an unloaded area (last seen at " + pos.getX() + " " + pos.getY() + " "
                    + pos.getZ() + " in " + entry.dimension().location() + ").");
        }
        return journeyman;
    }

    @Nullable
    static JourneymanEntity find(MinecraftServer server, JourneymanRegistry.Entry entry) {
        JourneymanEntity journeyman = JourneymanRegistry.get(server).loaded(server, entry.id());
        return journeyman != null && journeyman.kind() == entry.kind() ? journeyman : null;
    }
}
