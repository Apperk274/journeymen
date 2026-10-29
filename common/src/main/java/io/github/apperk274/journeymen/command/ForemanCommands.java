package io.github.apperk274.journeymen.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.github.apperk274.journeymen.build.AssignedChest;
import io.github.apperk274.journeymen.build.BuildJob;
import io.github.apperk274.journeymen.build.BuildOptions;
import io.github.apperk274.journeymen.build.Crew;
import io.github.apperk274.journeymen.build.JobService;
import io.github.apperk274.journeymen.build.MaterialSupply;
import io.github.apperk274.journeymen.entity.ForemanEntity;
import io.github.apperk274.journeymen.entity.JourneymanRegistry;
import io.github.apperk274.journeymen.hire.HireService;
import io.github.apperk274.journeymen.schematic.Schematic;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.TemplateRotationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Rotation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

public class ForemanCommands {

    private static final String FOREMAN = Targets.FOREMAN;
    private static final List<String> SUPPLY_OPTIONS = Stream.concat(BuildOptions.ALL.stream(), Stream.of(MaterialSupply.MISSING_ONLY)).toList();
    private static final SuggestionProvider<CommandSourceStack> ROLE_SUGGESTIONS = (context, builder) ->
            SharedSuggestionProvider.suggest(List.of("supply", "dump"), builder);

    private static SuggestionProvider<CommandSourceStack> optionSuggestions(List<String> options) {
        return (context, builder) -> {
            String remaining = builder.getRemaining();
            int lastSpace = remaining.lastIndexOf(' ');
            SuggestionsBuilder offset = builder.createOffset(builder.getStart() + lastSpace + 1);
            List<String> used = List.of(remaining.toLowerCase(Locale.ROOT).split("\\s+"));
            return SharedSuggestionProvider.suggest(options.stream().filter(option -> !used.contains(option)), offset);
        };
    }

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("foreman")
                .then(Commands.literal("summon")
                        .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> summon(context, null))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(context -> summon(context, StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("hire").then(foremanArgument()
                        .executes(context -> hire(context, null))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(context -> hire(context, StringArgumentType.getString(context, "name"))))))
                .then(Commands.literal("adopt").then(foremanArgument()
                        .then(Targets.argument(Targets.BUILDER, JourneymanRegistry.Kind.BUILDER).executes(ForemanCommands::adopt))))
                .then(Commands.literal("check").then(jobArguments(JobMode.CHECK)))
                .then(Commands.literal("build").then(jobArguments(JobMode.BUILD)))
                .then(Commands.literal("supply")
                        .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(jobArguments(JobMode.SUPPLY).executes(context -> supplyCurrentJob(context, false))
                                .then(Commands.literal(MaterialSupply.MISSING_ONLY).executes(context -> supplyCurrentJob(context, true)))))
                .then(Commands.literal("cancel").then(foremanArgument().executes(ForemanCommands::cancel)))
                .then(Commands.literal("chest").then(foremanArgument()
                        .then(Commands.literal("add").then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("role", StringArgumentType.word()).suggests(ROLE_SUGGESTIONS)
                                        .executes(ForemanCommands::addChest))))
                        .then(Commands.literal("remove").then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ForemanCommands::removeChest)))
                        .then(Commands.literal("list").executes(ForemanCommands::listChests))));
        return JourneymanCommands.common(root, JourneymanRegistry.Kind.FOREMAN);
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> foremanArgument() {
        return Targets.argument(FOREMAN, JourneymanRegistry.Kind.FOREMAN);
    }

    private enum JobMode {
        CHECK,
        BUILD,
        SUPPLY
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> jobArguments(JobMode mode) {
        return foremanArgument().then(Commands.argument("file", StringArgumentType.string())
                .suggests(SchematicCommands.SCHEMATIC_FILES)
                .executes(context -> job(context, mode, false, false, false))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(context -> job(context, mode, true, false, false))
                        .then(Commands.argument("rotation", TemplateRotationArgument.templateRotation())
                                .executes(context -> job(context, mode, true, true, false))
                                .then(Commands.argument("options", StringArgumentType.greedyString())
                                        .suggests(optionSuggestions(mode == JobMode.SUPPLY ? SUPPLY_OPTIONS : BuildOptions.ALL))
                                        .executes(context -> job(context, mode, true, true, true))))));
    }

    private static int summon(CommandContext<CommandSourceStack> context, @Nullable String requestedName) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        JourneymanRegistry registry = JourneymanRegistry.get(source.getServer());
        String name = HireService.pickName(registry, player.getUUID(), player.getRandom(), requestedName);
        String problem = HireService.nameProblem(registry, player.getUUID(), player.getGameProfile().getName(), name, true);
        if (problem != null) {
            throw JourneymenCommand.fail(problem);
        }
        ForemanEntity foreman = HireService.spawnForeman(source.getLevel(), player.getUUID(), player.getGameProfile().getName(), name,
                player.position(), player.getYRot());
        if (foreman == null) {
            throw JourneymenCommand.fail("Could not create a foreman.");
        }
        source.sendSuccess(() -> Component.literal("Summoned foreman " + name + ". Give him builders with /journeymen builder summon " + name
                + " or /journeymen foreman hire " + name + "."), false);
        return 1;
    }

    private static int hire(CommandContext<CommandSourceStack> context, @Nullable String name) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ForemanEntity foreman = Targets.foreman(context, FOREMAN);
        HireService.hireBuilder(player, foreman, name).forEach(line -> source.sendSuccess(() -> line, false));
        return 1;
    }

    private static int adopt(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ForemanEntity foreman = Targets.foreman(context, FOREMAN);
        JourneymanRegistry.Entry entry = Targets.resolve(context, Targets.BUILDER, JourneymanRegistry.Kind.BUILDER);
        String problem = HireService.adopt(source.getServer(), foreman, entry);
        if (problem != null) {
            throw JourneymenCommand.fail(problem);
        }
        source.sendSuccess(() -> Component.literal(foreman.getJourneymanName() + " adopted " + entry.name() + ".").withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int job(CommandContext<CommandSourceStack> context, JobMode mode, boolean hasPos, boolean hasRotation, boolean hasOptions)
            throws CommandSyntaxException {
        boolean start = mode == JobMode.BUILD;
        CommandSourceStack source = context.getSource();
        ForemanEntity foreman = Targets.foreman(context, FOREMAN);
        if (start && foreman.getJob() != null) {
            throw JourneymenCommand.fail(foreman.getJourneymanName() + " is already working on " + foreman.getJob().getFile() + ". Cancel that job first.");
        }
        if (foreman.level() != source.getLevel()) {
            throw JourneymenCommand.fail(foreman.getJourneymanName() + " is in another dimension.");
        }
        String file = StringArgumentType.getString(context, "file");
        BlockPos origin = hasPos ? BlockPosArgument.getBlockPos(context, "pos") : BlockPos.containing(source.getPosition());
        Rotation rotation = hasRotation ? TemplateRotationArgument.getRotation(context, "rotation") : Rotation.NONE;
        String optionText = hasOptions ? StringArgumentType.getString(context, "options") : "";
        boolean missingOnly = false;
        if (mode == JobMode.SUPPLY) {
            List<String> words = new ArrayList<>(List.of(optionText.trim().split("\\s+")));
            missingOnly = words.removeIf(word -> word.equalsIgnoreCase(MaterialSupply.MISSING_ONLY));
            optionText = String.join(" ", words);
        }
        BuildOptions options;
        try {
            options = BuildOptions.parse(optionText);
        } catch (IllegalArgumentException e) {
            throw JourneymenCommand.fail(mode == JobMode.SUPPLY ? e.getMessage() + ", " + MaterialSupply.MISSING_ONLY : e.getMessage());
        }
        if (mode == JobMode.SUPPLY) {
            boolean onlyMissing = missingOnly;
            SchematicCommands.loadAsync(source, file, schematic -> {
                if (foreman.isAlive()) {
                    supply(source, foreman, file, origin, rotation, options, schematic, onlyMissing);
                }
            });
            return 1;
        }
        JobService.run(source.getServer(), foreman, file, origin, rotation, options, start, result -> {
            if (result.header() != null) {
                source.sendSuccess(result::header, false);
                result.lines().forEach(line -> source.sendSuccess(() -> line, false));
            }
            if (result.error() != null) {
                source.sendFailure(Component.literal(result.error()));
            } else if (result.started()) {
                source.sendSuccess(() -> Component.literal(foreman.getJourneymanName() + "'s crew is on the way: " + Crew.list(result.crew()) + ".")
                        .withStyle(ChatFormatting.GREEN), false);
            }
        });
        return 1;
    }

    private static int supplyCurrentJob(CommandContext<CommandSourceStack> context, boolean missingOnly) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ForemanEntity foreman = Targets.foreman(context, FOREMAN);
        BuildJob job = foreman.getJob();
        if (job == null) {
            throw JourneymenCommand.fail(foreman.getJourneymanName() + " has no job. Name a schematic: /journeymen foreman supply "
                    + foreman.getJourneymanName() + " <file> [pos] [rotation] [options]");
        }
        SchematicCommands.loadAsync(source, job.getFile(), schematic -> {
            if (foreman.isAlive()) {
                supply(source, foreman, job.getFile(), job.getOrigin(), job.getRotation(), job.getOptions(), schematic, missingOnly);
            }
        });
        return 1;
    }

    private static void supply(CommandSourceStack source, ForemanEntity foreman, String file, BlockPos origin, Rotation rotation,
                               BuildOptions options, Schematic schematic, boolean missingOnly) {
        MaterialSupply.Outcome outcome = MaterialSupply.supply(foreman, schematic, file, origin, rotation, options, missingOnly, source.getPlayer());
        if (!outcome.success()) {
            source.sendFailure(outcome.summary());
            return;
        }
        source.sendSuccess(outcome::summary, outcome.given() > 0);
        outcome.notes().forEach(note -> source.sendSuccess(() -> note, false));
    }

    private static int cancel(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        MinecraftServer server = context.getSource().getServer();
        JourneymanRegistry.Entry entry = Targets.resolve(context, FOREMAN, JourneymanRegistry.Kind.FOREMAN);
        ForemanEntity foreman = Targets.find(server, entry) instanceof ForemanEntity loaded ? loaded : null;
        BuildJob job = foreman != null ? foreman.getJob() : JourneymanRegistry.get(server).jobOf(entry.id());
        if (job == null) {
            throw JourneymenCommand.fail(entry.name() + " has no job.");
        }
        if (foreman != null) {
            foreman.cancelJob();
        } else {
            ForemanEntity.endJobOf(server, job);
        }
        context.getSource().sendSuccess(() -> Component.literal(entry.name() + "'s crew stopped working on " + job.getFile() + "."), false);
        return 1;
    }

    private static int addChest(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ForemanEntity foreman = Targets.foreman(context, FOREMAN);
        if (foreman.level() != source.getLevel()) {
            throw JourneymenCommand.fail(foreman.getJourneymanName() + " is in another dimension.");
        }
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        String roleId = StringArgumentType.getString(context, "role");
        AssignedChest.Role role = AssignedChest.Role.byId(roleId);
        if (role == null) {
            throw JourneymenCommand.fail("Unknown chest role '" + roleId + "'. Use supply or dump.");
        }
        String problem = foreman.addChest(source.getLevel(), pos, role);
        if (problem != null) {
            throw JourneymenCommand.fail(problem);
        }
        source.sendSuccess(() -> Component.literal(foreman.getJourneymanName() + " uses the chest at " + pos.toShortString() + " as a " + role.id()
                + " chest."), false);
        return 1;
    }

    private static int removeChest(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ForemanEntity foreman = Targets.foreman(context, FOREMAN);
        BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
        if (!foreman.removeChest(context.getSource().getLevel().dimension(), pos)) {
            throw JourneymenCommand.fail(foreman.getJourneymanName() + " has no chest at " + pos.toShortString() + ".");
        }
        context.getSource().sendSuccess(() -> Component.literal(foreman.getJourneymanName() + " no longer uses the chest at " + pos.toShortString()
                + "."), false);
        return 1;
    }

    private static int listChests(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ForemanEntity foreman = Targets.foreman(context, FOREMAN);
        List<AssignedChest> chests = foreman.getChests();
        if (chests.isEmpty()) {
            source.sendSuccess(() -> Component.literal(foreman.getJourneymanName() + " has no chests."), false);
            return 0;
        }
        for (AssignedChest chest : chests) {
            String where = chest.isIn(foreman.level().dimension()) ? "" : " in " + chest.dimension().location();
            source.sendSuccess(() -> Component.literal(chest.role().id() + " chest at " + chest.pos().toShortString() + where), false);
        }
        return chests.size();
    }
}
