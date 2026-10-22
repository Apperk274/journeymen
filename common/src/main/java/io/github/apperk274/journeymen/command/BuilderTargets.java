package io.github.apperk274.journeymen.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.BuilderRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

final class BuilderTargets {

    static final String ARGUMENT = "builder";

    private static final SuggestionProvider<CommandSourceStack> SUGGESTIONS = (context, builder) -> {
        CommandSourceStack source = context.getSource();
        BuilderRegistry registry = BuilderRegistry.get(source.getServer());
        List<String> names = new ArrayList<>();
        ServerPlayer player = source.getPlayer();
        if (player != null) {
            names.addAll(registry.namesOf(player.getUUID()));
        }
        if (source.hasPermission(Commands.LEVEL_GAMEMASTERS)) {
            registry.all().forEach(entry -> names.add(entry.qualifiedName()));
        }
        return SharedSuggestionProvider.suggest(names.stream().map(StringArgumentType::escapeIfRequired), builder);
    };

    private BuilderTargets() {
    }

    static RequiredArgumentBuilder<CommandSourceStack, String> argument() {
        return Commands.argument(ARGUMENT, StringArgumentType.string()).suggests(SUGGESTIONS);
    }

    static BuilderRegistry.Entry resolve(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        String raw = StringArgumentType.getString(context, ARGUMENT);
        BuilderRegistry registry = BuilderRegistry.get(source.getServer());
        ServerPlayer player = source.getPlayer();
        int dot = raw.indexOf('.');
        if (dot >= 0) {
            String ownerName = raw.substring(0, dot);
            String name = raw.substring(dot + 1);
            List<BuilderRegistry.Entry> matches = registry.findByOwnerName(ownerName, name);
            if (matches.isEmpty()) {
                throw JourneymenCommand.fail(ownerName + " has no builder named " + name + ".");
            }
            BuilderRegistry.Entry entry = matches.get(0);
            boolean own = player != null && entry.owner().equals(player.getUUID());
            if (!own && !source.hasPermission(Commands.LEVEL_GAMEMASTERS)) {
                throw JourneymenCommand.fail("You can only command your own builders.");
            }
            return entry;
        }
        if (player == null) {
            throw JourneymenCommand.fail("Name the builder as <owner>.<name> when not running this as a player.");
        }
        return registry.find(player.getUUID(), raw)
                .orElseThrow(() -> JourneymenCommand.fail("You have no builder named " + raw + "."));
    }

    static BuilderEntity resolveLoaded(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        BuilderRegistry.Entry entry = resolve(context);
        BuilderEntity builder = find(context.getSource().getServer(), entry);
        if (builder == null) {
            BlockPos pos = entry.pos();
            throw JourneymenCommand.fail(entry.name() + " is in an unloaded area (last seen at " + pos.getX() + " " + pos.getY() + " "
                    + pos.getZ() + " in " + entry.dimension().location() + ").");
        }
        return builder;
    }

    static BuilderEntity find(MinecraftServer server, BuilderRegistry.Entry entry) {
        ServerLevel level = server.getLevel(entry.dimension());
        Entity entity = level == null ? null : level.getEntity(entry.id());
        return entity instanceof BuilderEntity builder && builder.isAlive() ? builder : null;
    }
}
