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
import io.github.apperk274.journeymen.build.MaterialSupply;
import io.github.apperk274.journeymen.build.PlacingTask;
import io.github.apperk274.journeymen.build.PlacementTransform;
import io.github.apperk274.journeymen.build.PreCheck;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.BuilderNames;
import io.github.apperk274.journeymen.entity.BuilderRegistry;
import io.github.apperk274.journeymen.entity.ModEntities;
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
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public class BuilderCommands {

    private static final SuggestionProvider<CommandSourceStack> OPTION_SUGGESTIONS = (context, builder) -> {
        String remaining = builder.getRemaining();
        int lastSpace = remaining.lastIndexOf(' ');
        SuggestionsBuilder offset = builder.createOffset(builder.getStart() + lastSpace + 1);
        List<String> used = List.of(remaining.toLowerCase(Locale.ROOT).split("\\s+"));
        return SharedSuggestionProvider.suggest(BuildOptions.ALL.stream().filter(option -> !used.contains(option)), offset);
    };

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
                .then(Commands.literal("check").then(jobArguments(JobMode.CHECK)))
                .then(Commands.literal("build").then(jobArguments(JobMode.BUILD)))
                .then(Commands.literal("supply")
                        .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(jobArguments(JobMode.SUPPLY).executes(BuilderCommands::supplyCurrentJob)))
                .then(Commands.literal("cancel").then(BuilderTargets.argument().executes(BuilderCommands::cancel)))
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
        return BuilderTargets.argument().then(Commands.argument("file", StringArgumentType.string())
                .suggests(SchematicCommands.SCHEMATIC_FILES)
                .executes(context -> job(context, mode, false, false, false))
                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(context -> job(context, mode, true, false, false))
                        .then(Commands.argument("rotation", TemplateRotationArgument.templateRotation())
                                .executes(context -> job(context, mode, true, true, false))
                                .then(Commands.argument("options", StringArgumentType.greedyString())
                                        .suggests(OPTION_SUGGESTIONS)
                                        .executes(context -> job(context, mode, true, true, true))))));
    }

    private static int job(CommandContext<CommandSourceStack> context, JobMode mode, boolean hasPos, boolean hasRotation, boolean hasOptions)
            throws CommandSyntaxException {
        boolean start = mode == JobMode.BUILD;
        CommandSourceStack source = context.getSource();
        BuilderEntity builder = BuilderTargets.resolveLoaded(context);
        if (start && builder.getJob() != null) {
            throw JourneymenCommand.fail(builder.getBuilderName() + " is already working on " + builder.getJob().getFile() + ". Cancel that job first.");
        }
        if (builder.level() != source.getLevel()) {
            throw JourneymenCommand.fail(builder.getBuilderName() + " is in another dimension.");
        }
        String file = StringArgumentType.getString(context, "file");
        BlockPos origin = hasPos ? BlockPosArgument.getBlockPos(context, "pos") : BlockPos.containing(source.getPosition());
        Rotation rotation = hasRotation ? TemplateRotationArgument.getRotation(context, "rotation") : Rotation.NONE;
        BuildOptions options;
        try {
            options = hasOptions ? BuildOptions.parse(StringArgumentType.getString(context, "options")) : BuildOptions.DEFAULT;
        } catch (IllegalArgumentException e) {
            throw JourneymenCommand.fail(e.getMessage());
        }
        SchematicCommands.loadAsync(source, file, schematic -> {
            if (!builder.isAlive()) {
                source.sendFailure(Component.literal(builder.getBuilderName() + " is gone."));
                return;
            }
            if (mode == JobMode.SUPPLY) {
                supply(source, builder, file, origin, rotation, options, schematic);
                return;
            }
            PlacementTransform transform = PlacementTransform.of(schematic, origin, rotation, options.corner());
            PreCheck check = PreCheck.run(builder, schematic, transform, options);
            BoundingBox area = check.getArea();
            source.sendSuccess(() -> Component.literal((start ? "Build " : "Check ") + file).withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(" for " + builder.getBuilderName() + " at " + origin.toShortString() + ", rotation "
                            + rotation.getSerializedName() + ", options " + options.describe() + "; area "
                            + area.minX() + " " + area.minY() + " " + area.minZ() + " to " + area.maxX() + " " + area.maxY() + " " + area.maxZ())
                            .withStyle(ChatFormatting.GRAY)), false);
            check.lines().forEach(line -> source.sendSuccess(() -> line, false));
            if (!start) {
                return;
            }
            BuildPlan plan = check.getPlan();
            if (!check.passed() || plan == null) {
                source.sendFailure(Component.literal(builder.getBuilderName() + " can't start this job yet."));
                return;
            }
            if (builder.getJob() != null) {
                source.sendFailure(Component.literal(builder.getBuilderName() + " got another job in the meantime."));
                return;
            }
            builder.startJob(new BuildJob(file, origin, rotation, options, area, BuildJob.Phase.WALKING), schematic, plan);
            source.sendSuccess(() -> Component.literal(builder.getBuilderName() + " is on the way.").withStyle(ChatFormatting.GREEN), false);
        });
        return 1;
    }

    private static int supplyCurrentJob(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BuilderEntity builder = BuilderTargets.resolveLoaded(context);
        BuildJob job = builder.getJob();
        if (job == null) {
            throw JourneymenCommand.fail(builder.getBuilderName() + " has no job. Name a schematic: /journeymen builder supply "
                    + builder.getBuilderName() + " <file> [pos] [rotation] [options]");
        }
        SchematicCommands.loadAsync(source, job.getFile(), schematic -> {
            if (builder.isAlive()) {
                supply(source, builder, job.getFile(), job.getOrigin(), job.getRotation(), job.getOptions(), schematic);
            }
        });
        return 1;
    }

    private static void supply(CommandSourceStack source, BuilderEntity builder, String file, BlockPos origin, Rotation rotation,
                               BuildOptions options, Schematic schematic) {
        ServerLevel level = (ServerLevel) builder.level();
        PlacementTransform transform = PlacementTransform.of(schematic, origin, rotation, options.corner());
        if (!BuildPlan.isLoaded(level, transform.area(schematic.getSize()))) {
            source.sendFailure(Component.literal("Part of the site isn't loaded. Go closer to it and try again. Nothing was given."));
            return;
        }
        MaterialSupply.Needs needs = MaterialSupply.needs(level, BuildPlan.create(level, schematic, transform));
        List<MaterialSupply.Target> chests = new ArrayList<>();
        for (AssignedChest chest : builder.getChests()) {
            if (chest.role() == AssignedChest.Role.SUPPLY && level.isLoaded(chest.pos())) {
                Container container = HopperBlockEntity.getContainerAt(level, chest.pos());
                if (container != null) {
                    chests.add(MaterialSupply.snapshot(container));
                }
            }
        }
        ServerPlayer player = source.getPlayer();
        MaterialSupply.Target playerTarget = player == null ? null : MaterialSupply.snapshot(player.getInventory());
        long toChests = 0;
        long toPlayer = 0;
        long notFitting = 0;
        for (ItemStack stack : needs.stacks()) {
            ItemStack remaining = stack;
            for (MaterialSupply.Target chest : chests) {
                remaining = MaterialSupply.insert(chest, remaining, false);
                if (remaining.isEmpty()) {
                    break;
                }
            }
            toChests += stack.getCount() - remaining.getCount();
            if (!remaining.isEmpty() && playerTarget != null) {
                int before = remaining.getCount();
                remaining = MaterialSupply.insert(playerTarget, remaining, true);
                toPlayer += before - remaining.getCount();
            }
            notFitting += remaining.getCount();
        }
        if (notFitting > 0) {
            String where = chests.isEmpty() ? builder.getBuilderName() + " has no loaded supply chests" : "they don't fit in " + builder.getBuilderName() + "'s supply chests";
            source.sendFailure(Component.literal("Not enough room: " + notFitting + " of " + (toChests + toPlayer + notFitting) + " items don't fit ("
                    + where + (playerTarget == null ? ", and there is no player inventory to fall back on" : " or your inventory") + "). Nothing was given."));
            return;
        }
        chests.forEach(MaterialSupply::apply);
        if (playerTarget != null) {
            MaterialSupply.apply(playerTarget);
        }
        long total = toChests + toPlayer;
        long finalToChests = toChests;
        long finalToPlayer = toPlayer;
        source.sendSuccess(() -> Component.literal("Supplied " + total + (total == 1 ? " item" : " items") + " for " + file + ": " + finalToChests + " into "
                + builder.getBuilderName() + "'s supply chests" + (finalToPlayer > 0 ? ", " + finalToPlayer + " into your inventory" : "") + ".")
                .withStyle(ChatFormatting.GREEN), true);
        needs.unobtainable().forEach((block, count) -> source.sendSuccess(() -> Component.literal("Can't be supplied (no survival item): " + count + " ")
                .append(block.getName()).withStyle(ChatFormatting.GOLD), false));
        needs.noKnownTool().forEach((block, count) -> source.sendSuccess(() -> Component.literal("No known tool for " + count + " ")
                .append(block.getName()).withStyle(ChatFormatting.GOLD), false));
    }

    private static int cancel(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        BuilderEntity builder = BuilderTargets.resolveLoaded(context);
        BuildJob job = builder.getJob();
        if (job == null) {
            throw JourneymenCommand.fail(builder.getBuilderName() + " has no job.");
        }
        builder.cancelJob();
        context.getSource().sendSuccess(() -> Component.literal(builder.getBuilderName() + " stopped working on " + job.getFile() + "."), false);
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
        }
        BuildJob job = builder == null ? null : builder.getJob();
        if (job == null) {
            source.sendSuccess(() -> Component.literal(builder == null ? "  Job: unknown while not loaded" : "  Job: none"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.literal("  Job: " + job.getFile() + " at " + job.getOrigin().toShortString() + ", rotation "
                + job.describeRotation() + ", options " + job.getOptions().describe()), false);
        String phase = job.getPhase() == BuildJob.Phase.PAUSED && job.getPauseReason() != null
                ? "paused: " + job.getPauseReason()
                : job.getPhase().description();
        source.sendSuccess(() -> Component.literal("  Phase: " + phase), false);
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
        if (builder.getJob() != null) {
            return "working on " + builder.getJob().getFile() + " (" + builder.getJob().getPhase().description() + ")";
        }
        if (builder.getComeTarget() != null) {
            return "walking to you";
        }
        return builder.isFollowing() ? "following its owner" : "idle";
    }
}
