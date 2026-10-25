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
import io.github.apperk274.journeymen.build.BuildPlan;
import io.github.apperk274.journeymen.build.ClearingTask;
import io.github.apperk274.journeymen.build.Crew;
import io.github.apperk274.journeymen.build.JobService;
import io.github.apperk274.journeymen.build.MaterialSupply;
import io.github.apperk274.journeymen.build.PlacingTask;
import io.github.apperk274.journeymen.build.WorkState;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.BuilderNames;
import io.github.apperk274.journeymen.entity.BuilderRegistry;
import io.github.apperk274.journeymen.entity.ModEntities;
import io.github.apperk274.journeymen.entity.StuckRecovery;
import io.github.apperk274.journeymen.schematic.Schematic;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.TemplateRotationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Rotation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Stream;

public class BuilderCommands {

    private static final List<String> SUPPLY_OPTIONS = Stream.concat(BuildOptions.ALL.stream(), Stream.of(MaterialSupply.MISSING_ONLY)).toList();

    private static SuggestionProvider<CommandSourceStack> optionSuggestions(List<String> options) {
        return (context, builder) -> {
            String remaining = builder.getRemaining();
            int lastSpace = remaining.lastIndexOf(' ');
            SuggestionsBuilder offset = builder.createOffset(builder.getStart() + lastSpace + 1);
            List<String> used = List.of(remaining.toLowerCase(Locale.ROOT).split("\\s+"));
            return SharedSuggestionProvider.suggest(options.stream().filter(option -> !used.contains(option)), offset);
        };
    }

    private static final int STUCK_SHOWN_SECONDS = 5;
    private static final String HELPER = "helper";

    private static final SuggestionProvider<CommandSourceStack> ROLE_SUGGESTIONS = (context, builder) ->
            SharedSuggestionProvider.suggest(List.of("supply", "dump"), builder);

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
                .then(Commands.literal("notices").executes(BuilderCommands::notices))
                .then(Commands.literal("rename").then(BuilderTargets.argument()
                        .then(Commands.argument("name", StringArgumentType.string()).executes(BuilderCommands::rename))))
                .then(Commands.literal("come").then(BuilderTargets.argument().executes(BuilderCommands::come)))
                .then(Commands.literal("follow").then(BuilderTargets.argument().executes(context -> setFollowing(context, true))))
                .then(Commands.literal("stay").then(BuilderTargets.argument().executes(context -> setFollowing(context, false))))
                .then(Commands.literal("kill").then(BuilderTargets.argument().executes(BuilderCommands::kill)))
                .then(Commands.literal("unstick").then(BuilderTargets.argument().executes(BuilderCommands::unstick)))
                .then(Commands.literal("check").then(jobArguments(JobMode.CHECK)))
                .then(Commands.literal("build").then(jobArguments(JobMode.BUILD)))
                .then(Commands.literal("supply")
                        .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(jobArguments(JobMode.SUPPLY).executes(context -> supplyCurrentJob(context, false))
                                .then(Commands.literal(MaterialSupply.MISSING_ONLY).executes(context -> supplyCurrentJob(context, true)))))
                .then(Commands.literal("cancel").then(BuilderTargets.argument().executes(BuilderCommands::cancel)))
                .then(Commands.literal("join").then(BuilderTargets.argument(HELPER)
                        .then(BuilderTargets.argument().executes(BuilderCommands::join))))
                .then(Commands.literal("leave").then(BuilderTargets.argument().executes(BuilderCommands::leave)))
                .then(Commands.literal("chest").then(BuilderTargets.argument()
                        .then(Commands.literal("add").then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("role", StringArgumentType.word()).suggests(ROLE_SUGGESTIONS)
                                        .executes(BuilderCommands::addChest))))
                        .then(Commands.literal("remove").then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(BuilderCommands::removeChest)))
                        .then(Commands.literal("list").executes(BuilderCommands::listChests))));
    }

    private enum JobMode {
        CHECK,
        BUILD,
        SUPPLY
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> jobArguments(JobMode mode) {
        return (mode == JobMode.SUPPLY ? BuilderTargets.argument() : BuilderTargets.crewArgument()).then(Commands.argument("file", StringArgumentType.string())
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

    private static int job(CommandContext<CommandSourceStack> context, JobMode mode, boolean hasPos, boolean hasRotation, boolean hasOptions)
            throws CommandSyntaxException {
        boolean start = mode == JobMode.BUILD;
        CommandSourceStack source = context.getSource();
        List<BuilderEntity> crew = mode == JobMode.SUPPLY ? List.of(BuilderTargets.resolveLoaded(context)) : BuilderTargets.resolveCrew(context);
        BuilderEntity builder = crew.get(0);
        for (BuilderEntity member : crew) {
            if (start && member.getJob() != null) {
                throw JourneymenCommand.fail(member.getBuilderName() + " is already working on " + member.getJob().getFile() + ". Cancel that job first.");
            }
            if (member.level() != source.getLevel()) {
                throw JourneymenCommand.fail(member.getBuilderName() + " is in another dimension.");
            }
        }
        String crewProblem = JobService.crewProblem(crew, start);
        if (crewProblem != null) {
            throw JourneymenCommand.fail(crewProblem);
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
                if (builder.isAlive()) {
                    supply(source, builder, file, origin, rotation, options, schematic, onlyMissing);
                }
            });
            return 1;
        }
        JobService.run(source.getServer(), crew, file, origin, rotation, options, start, result -> {
            if (result.header() != null) {
                source.sendSuccess(result::header, false);
                result.lines().forEach(line -> source.sendSuccess(() -> line, false));
            }
            if (result.error() != null) {
                source.sendFailure(Component.literal(result.error()));
            } else if (result.started()) {
                String who = Crew.names(crew) + (crew.size() == 1 ? " is" : " are");
                source.sendSuccess(() -> Component.literal(who + " on the way.").withStyle(ChatFormatting.GREEN), false);
            }
        });
        return 1;
    }

    private static int supplyCurrentJob(CommandContext<CommandSourceStack> context, boolean missingOnly) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BuilderEntity builder = BuilderTargets.resolveLoaded(context);
        BuildJob job = builder.getJob();
        if (job == null) {
            throw JourneymenCommand.fail(builder.getBuilderName() + " has no job. Name a schematic: /journeymen builder supply "
                    + builder.getBuilderName() + " <file> [pos] [rotation] [options]");
        }
        SchematicCommands.loadAsync(source, job.getFile(), schematic -> {
            if (builder.isAlive()) {
                supply(source, builder, job.getFile(), job.getOrigin(), job.getRotation(), job.getOptions(), schematic, missingOnly);
            }
        });
        return 1;
    }

    private static void supply(CommandSourceStack source, BuilderEntity builder, String file, BlockPos origin, Rotation rotation,
                               BuildOptions options, Schematic schematic, boolean missingOnly) {
        MaterialSupply.Outcome outcome = MaterialSupply.supply(builder, schematic, file, origin, rotation, options, missingOnly, source.getPlayer());
        if (!outcome.success()) {
            source.sendFailure(outcome.summary());
            return;
        }
        source.sendSuccess(outcome::summary, outcome.given() > 0);
        outcome.notes().forEach(note -> source.sendSuccess(() -> note, false));
    }

    private static int cancel(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        BuilderEntity builder = BuilderTargets.resolveLoaded(context);
        BuildJob job = builder.getJob();
        if (job == null) {
            throw JourneymenCommand.fail(builder.getBuilderName() + " has no job.");
        }
        String who = job.size() == 1 ? builder.getBuilderName() : "The crew of " + BuilderRegistry.get(context.getSource().getServer()).describeCrew(job);
        builder.cancelJob();
        context.getSource().sendSuccess(() -> Component.literal(who + " stopped working on " + job.getFile() + "."), false);
        return 1;
    }

    private static int join(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BuilderEntity helper = BuilderTargets.resolveLoaded(context, HELPER);
        BuilderEntity builder = BuilderTargets.resolveLoaded(context);
        String problem = Crew.join(helper, builder, source.hasPermission(Commands.LEVEL_GAMEMASTERS));
        if (problem != null) {
            throw JourneymenCommand.fail(problem);
        }
        BuildJob job = helper.getJob();
        String crew = BuilderRegistry.get(source.getServer()).describeCrew(job);
        source.sendSuccess(() -> Component.literal(helper.getBuilderName() + " joins the crew of " + job.getFile() + " (" + crew + ").")
                .withStyle(ChatFormatting.GREEN), false);
        return 1;
    }

    private static int leave(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BuilderEntity builder = BuilderTargets.resolveLoaded(context);
        BuildJob job = builder.getJob();
        if (job == null) {
            throw JourneymenCommand.fail(builder.getBuilderName() + " has no job.");
        }
        builder.leaveJob();
        BuilderRegistry registry = BuilderRegistry.get(source.getServer());
        String rest = registry.hasJob(job.getId()) ? " " + registry.describeCrew(job) + " carry on." : " Nobody is working on it anymore.";
        source.sendSuccess(() -> Component.literal(builder.getBuilderName() + " left the crew of " + job.getFile() + "." + rest), false);
        return 1;
    }

    private static int addChest(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BuilderEntity builder = BuilderTargets.resolveLoaded(context);
        if (builder.level() != source.getLevel()) {
            throw JourneymenCommand.fail(builder.getBuilderName() + " is in another dimension.");
        }
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        String roleId = StringArgumentType.getString(context, "role");
        AssignedChest.Role role = AssignedChest.Role.byId(roleId);
        if (role == null) {
            throw JourneymenCommand.fail("Unknown chest role '" + roleId + "'. Use supply or dump.");
        }
        String problem = builder.addChest(pos, role);
        if (problem != null) {
            throw JourneymenCommand.fail(problem);
        }
        source.sendSuccess(() -> Component.literal(builder.getBuilderName() + " uses the chest at " + pos.toShortString() + " as a " + role.id() + " chest."), false);
        return 1;
    }

    private static int removeChest(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        BuilderEntity builder = BuilderTargets.resolveLoaded(context);
        BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
        if (!builder.removeChest(pos)) {
            throw JourneymenCommand.fail(builder.getBuilderName() + " has no chest at " + pos.toShortString() + ".");
        }
        context.getSource().sendSuccess(() -> Component.literal(builder.getBuilderName() + " no longer uses the chest at " + pos.toShortString() + "."), false);
        return 1;
    }

    private static int listChests(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BuilderEntity builder = BuilderTargets.resolveLoaded(context);
        List<AssignedChest> chests = builder.getChests();
        if (chests.isEmpty()) {
            source.sendSuccess(() -> Component.literal(builder.getBuilderName() + " has no chests."), false);
            return 0;
        }
        for (AssignedChest chest : chests) {
            source.sendSuccess(() -> Component.literal(chest.role().id() + " chest at " + chest.pos().toShortString()), false);
        }
        return chests.size();
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

    private static int notices(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        List<String> notices = BuilderRegistry.get(source.getServer()).takeNotices(player.getUUID());
        if (notices.isEmpty()) {
            source.sendSuccess(() -> Component.literal("Your builders have nothing new to tell you."), false);
            return 0;
        }
        notices.forEach(notice -> source.sendSuccess(() -> Component.literal(notice), false));
        return notices.size();
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
            describeStuck(source, builder);
        }
        BuildJob job = builder == null ? null : builder.getJob();
        if (job == null) {
            source.sendSuccess(() -> Component.literal(builder == null ? "  Job: unknown while not loaded" : "  Job: none"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("  Job: " + job.getFile() + " at " + job.getOrigin().toShortString() + ", rotation "
                + job.describeRotation() + ", options " + job.getOptions().describe()), false);
        WorkState work = builder.getWork();
        String phase = work.getPhase() == BuildJob.Phase.PAUSED && work.getPauseReason() != null
                ? "paused: " + work.getPauseReason()
                : work.getPhase().description();
        source.sendSuccess(() -> Component.literal("  Phase: " + phase), false);
        if (job.size() > 1) {
            describeCrew(source, job);
        }
        ClearingTask clearing = builder.getClearing();
        PlacingTask placing = builder.getPlacing();
        BuildPlan plan = job.getPlan();
        if (placing != null) {
            source.sendSuccess(() -> Component.literal("  Placing: " + placing.getPlaced() + " placed, " + placing.getRemaining() + " left"), false);
        } else if (clearing != null) {
            source.sendSuccess(() -> Component.literal("  Clearing: " + clearing.getCleared() + " cleared, " + clearing.getRemaining() + " left"), false);
        } else if (plan != null && plan.getTotalCount() > 0) {
            int percent = plan.getDoneCount() * 100 / plan.getTotalCount();
            source.sendSuccess(() -> Component.literal("  Progress: " + percent + "% (" + plan.getDoneCount() + " of " + plan.getTotalCount() + " blocks right)"), false);
        }
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
        if (builder.getJob() != null) {
            throw JourneymenCommand.fail(builder.getBuilderName() + " is busy with " + builder.getJob().getFile() + ". Cancel the job first.");
        }
        BlockPos target = BlockPos.containing(source.getPosition());
        builder.comeTo(target);
        source.sendSuccess(() -> Component.literal(builder.getBuilderName() + " is coming to " + target.getX() + " " + target.getY() + " " + target.getZ() + "."), false);
        return 1;
    }

    private static int setFollowing(CommandContext<CommandSourceStack> context, boolean following) throws CommandSyntaxException {
        BuilderEntity builder = BuilderTargets.resolveLoaded(context);
        if (following && builder.getJob() != null) {
            throw JourneymenCommand.fail(builder.getBuilderName() + " is busy with " + builder.getJob().getFile() + ". Cancel the job first.");
        }
        builder.setFollowing(following);
        String message = following ? builder.getBuilderName() + " follows its owner now." : builder.getBuilderName() + " stays here now.";
        context.getSource().sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static int unstick(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BuilderEntity builder = BuilderTargets.resolveLoaded(context);
        BlockPos here = builder.level() == source.getLevel() ? BlockPos.containing(source.getPosition()) : null;
        BlockPos from = builder.blockPosition();
        String problem = builder.unstick(here);
        if (problem != null) {
            throw JourneymenCommand.fail(problem);
        }
        BlockPos to = builder.blockPosition();
        source.sendSuccess(() -> Component.literal(builder.getBuilderName() + " got out of " + from.toShortString() + " to "
                + to.toShortString() + "."), false);
        return 1;
    }

    private static int kill(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        BuilderEntity builder = BuilderTargets.resolveLoaded(context);
        String name = builder.getBuilderName();
        builder.kill();
        context.getSource().sendSuccess(() -> Component.literal("Killed " + name + "."), false);
        return 1;
    }

    private static void describeStuck(CommandSourceStack source, BuilderEntity builder) {
        StuckRecovery recovery = builder.getStuckRecovery();
        int stuckSeconds = recovery.getStuckTicks() / 20;
        if (stuckSeconds >= STUCK_SHOWN_SECONDS && recovery.getGoalDescription() != null) {
            int limit = StuckRecovery.limitTicks() / 20;
            source.sendSuccess(() -> Component.literal("  Stuck for " + stuckSeconds + " s on the way to " + recovery.getGoalDescription()
                    + (limit > 0 ? "; it gets itself out after " + limit + " s" : "")), false);
        }
        StuckRecovery.Escape escape = recovery.getLast();
        if (escape != null) {
            long seconds = Math.max(0, (builder.level().getGameTime() - escape.time()) / 20);
            String ago = seconds < 120 ? seconds + " s ago" : seconds / 60 + " min ago";
            source.sendSuccess(() -> Component.literal("  Last got unstuck " + ago + ": from " + escape.from().toShortString() + " to "
                    + escape.to().toShortString() + ", on the way to " + escape.reason()), false);
        }
    }

    private static void describeCrew(CommandSourceStack source, BuildJob job) {
        BuilderRegistry registry = BuilderRegistry.get(source.getServer());
        List<UUID> members = job.getMembers();
        source.sendSuccess(() -> Component.literal("  Crew of " + members.size() + ":"), false);
        for (UUID id : members) {
            BuilderRegistry.Entry entry = registry.get(id).orElse(null);
            BuilderEntity member = entry == null ? null : BuilderTargets.find(source.getServer(), entry);
            String name = entry == null ? id.toString() : entry.name();
            String doing;
            if (member == null) {
                doing = "not loaded";
            } else if (member.getWork().getPhase() == BuildJob.Phase.PAUSED && member.getWork().getPauseReason() != null) {
                doing = "paused: " + member.getWork().getPauseReason();
            } else {
                doing = member.progress().text();
            }
            String line = "    " + name + (job.isLeader(id) ? " (leader)" : "") + ": " + doing;
            source.sendSuccess(() -> Component.literal(line), false);
        }
    }

    private static String describePosition(BuilderRegistry.Entry entry) {
        BlockPos pos = entry.pos();
        return "at " + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " in " + entry.dimension().location();
    }

    private static String describeActivity(@Nullable BuilderEntity builder) {
        if (builder == null) {
            return "not loaded";
        }
        if (builder.getJob() != null) {
            return "working on " + builder.getJob().getFile() + " (" + builder.getWork().getPhase().description() + ")";
        }
        if (builder.getComeTarget() != null) {
            return "walking to you";
        }
        return builder.isFollowing() ? "following its owner" : "idle";
    }
}
