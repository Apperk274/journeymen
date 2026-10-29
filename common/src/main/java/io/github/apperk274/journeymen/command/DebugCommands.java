package io.github.apperk274.journeymen.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.apperk274.journeymen.CommonClass;
import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.build.ChunkKeeper;
import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.ForemanEntity;
import io.github.apperk274.journeymen.entity.JourneymanEntity;
import io.github.apperk274.journeymen.entity.JourneymanRegistry;
import io.github.apperk274.journeymen.menu.BuilderMenu;
import io.github.apperk274.journeymen.menu.ForemanMenu;
import io.github.apperk274.journeymen.menu.LinkModeService;
import io.github.apperk274.journeymen.menu.ScreenActions;
import io.github.apperk274.journeymen.network.ScreenPayloads;
import io.github.apperk274.journeymen.platform.Services;
import io.github.apperk274.journeymen.schematic.SchematicFiles;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameModeArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class DebugCommands {

    static LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("debug")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("as")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("permission", IntegerArgumentType.integer(0, 4))
                                        .then(Commands.argument("pos", Vec3Argument.vec3())
                                                .then(Commands.argument("command", StringArgumentType.greedyString())
                                                        .executes(DebugCommands::runAs))))))
                .then(Commands.literal("action")
                        .then(Commands.argument("journeyman", EntityArgument.entity())
                                .then(Commands.argument("action", StringArgumentType.word())
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                                        .executes(DebugCommands::action))))))
                .then(Commands.literal("gamemode")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("gamemode", GameModeArgument.gameMode())
                                        .executes(DebugCommands::gameMode))))
                .then(Commands.literal("rejoin")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(DebugCommands::rejoin))))
                .then(Commands.literal("use")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(DebugCommands::use)))
                .then(Commands.literal("useitem")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .then(Commands.argument("face", StringArgumentType.word())
                                        .executes(DebugCommands::useItem))))
                .then(Commands.literal("protect")
                        .then(Commands.literal("clear").executes(DebugCommands::protectClear))
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .then(Commands.argument("allowed", StringArgumentType.word())
                                                .executes(DebugCommands::protect)))))
                .then(Commands.literal("interact")
                        .then(Commands.argument("entity", EntityArgument.entity())
                                .executes(DebugCommands::interact)))
                .then(Commands.literal("config")
                        .then(Commands.argument("key", StringArgumentType.word())
                                .then(Commands.argument("value", StringArgumentType.greedyString())
                                        .executes(DebugCommands::config))))
                .then(Commands.literal("chunks").executes(DebugCommands::chunks))
                .then(Commands.literal("paths").executes(DebugCommands::paths))
                .then(Commands.literal("copyschematic")
                        .then(Commands.argument("from", StringArgumentType.string())
                                .then(Commands.argument("to", StringArgumentType.string())
                                        .executes(DebugCommands::copySchematic))));
    }

    private static int action(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Entity entity = EntityArgument.getEntity(context, "journeyman");
        String text = StringArgumentType.getString(context, "text");
        ScreenActions.handle(new ScreenPayloads.Action(entity.getId(), StringArgumentType.getString(context, "action"),
                text.equals("-") ? "" : text.replace('|', '\n'), BlockPosArgument.getBlockPos(context, "pos")), player, message -> {
            Constants.LOG.info("[debug reply] {}", message.getString());
            player.sendSystemMessage(message);
        });
        if (entity instanceof JourneymanEntity journeyman) {
            ForemanEntity foreman = journeyman instanceof ForemanEntity self ? self : ((BuilderEntity) journeyman).foreman();
            List<String> crew = foreman == null ? List.of() : foreman.crewEntries().stream().map(JourneymanRegistry.Entry::name).sorted().toList();
            Constants.LOG.info("[debug action] {} alive={} following={} job={} crew={} chests={}", journeyman.getJourneymanName(), journeyman.isAlive(),
                    journeyman.isFollowing(), journeyman.getJob() == null ? "-" : journeyman.getJob().getFile(), crew,
                    foreman == null ? "-" : foreman.getChests());
        }
        return 1;
    }

    private static int gameMode(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        String name = StringArgumentType.getString(context, "player");
        ServerPlayer player = Services.PLATFORM.getFakePlayer(context.getSource().getLevel(), new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name));
        player.setGameMode(GameModeArgument.getGameMode(context, "gamemode"));
        Constants.LOG.info("[debug gamemode] {} is in {} mode", name, player.gameMode.getGameModeForPlayer().getName());
        return 1;
    }

    private static int rejoin(CommandContext<CommandSourceStack> context) {
        String player = StringArgumentType.getString(context, "player");
        String name = StringArgumentType.getString(context, "name");
        ServerPlayer joining = Services.PLATFORM.getFakePlayer(context.getSource().getLevel(),
                new GameProfile(UUIDUtil.createOfflinePlayerUUID(player), name));
        CommonClass.onPlayerJoin(joining);
        Constants.LOG.info("[debug rejoin] {} joined as {}", player, name);
        return 1;
    }

    private static int use(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        boolean handled = LinkModeService.onUseBlock(player, BlockPosArgument.getBlockPos(context, "pos"));
        Constants.LOG.info("[debug use] handled={}", handled);
        return 1;
    }

    private static int useItem(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        BlockPos pos = BlockPosArgument.getBlockPos(context, "pos");
        Direction face = Direction.byName(StringArgumentType.getString(context, "face"));
        if (face == null) {
            throw JourneymenCommand.fail("Unknown face " + StringArgumentType.getString(context, "face") + ".");
        }
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos).relative(face, 0.5), face, pos, false);
        InteractionResult result = player.gameMode.useItemOn(player, player.serverLevel(), player.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
        Constants.LOG.info("[debug useitem] {} on {} {}: {}", player.getGameProfile().getName(), pos.toShortString(), face.getName(), result);
        return 1;
    }

    private static int protect(CommandContext<CommandSourceStack> context) {
        BoundingBox box = BoundingBox.fromCorners(BlockPosArgument.getBlockPos(context, "from"), BlockPosArgument.getBlockPos(context, "to"));
        String allowed = StringArgumentType.getString(context, "allowed");
        DebugProtection.add(box, allowed);
        String result = "Protected " + box.minX() + " " + box.minY() + " " + box.minZ() + " to " + box.maxX() + " " + box.maxY() + " " + box.maxZ()
                + (allowed.equals(DebugProtection.ANYONE) ? " for nobody (watched only)" : " for everyone but " + allowed);
        Constants.LOG.info("[debug protect] {}", result);
        context.getSource().sendSuccess(() -> Component.literal(result), false);
        return 1;
    }

    private static int protectClear(CommandContext<CommandSourceStack> context) {
        int count = DebugProtection.clear();
        Constants.LOG.info("[debug protect] cleared {} areas", count);
        context.getSource().sendSuccess(() -> Component.literal("Cleared " + count + " protected areas"), false);
        return count;
    }

    private static int interact(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Entity entity = EntityArgument.getEntity(context, "entity");
        boolean linkingBefore = LinkModeService.isLinking(player);
        InteractionResult result = player.interactOn(entity, InteractionHand.MAIN_HAND);
        Constants.LOG.info("[debug interact] {} result={} linking={}->{} menu={}", entity.getName().getString(), result, linkingBefore,
                LinkModeService.isLinking(player), player.containerMenu instanceof BuilderMenu menu ? "builder tab " + menu.getActiveTab()
                        : player.containerMenu instanceof ForemanMenu menu ? "foreman tab " + menu.getActiveTab() : "none");
        return 1;
    }

    private static int config(CommandContext<CommandSourceStack> context) {
        String key = StringArgumentType.getString(context, "key");
        String value = StringArgumentType.getString(context, "value");
        String problem = JourneymenConfig.set(key, value);
        ChunkKeeper.update(context.getSource().getServer());
        String result = problem == null ? key + " = " + value + " until the next config reload" : problem;
        Constants.LOG.info("[debug config] {}", result);
        context.getSource().sendSuccess(() -> Component.literal(result), false);
        return problem == null ? 1 : 0;
    }

    private static int copySchematic(CommandContext<CommandSourceStack> context) {
        String from = StringArgumentType.getString(context, "from");
        String to = StringArgumentType.getString(context, "to");
        String result;
        try {
            SchematicFiles.copy(from, to);
            result = "copied " + from + " to " + to;
        } catch (IOException e) {
            result = "could not copy " + from + " to " + to + ": " + e.getMessage();
        }
        Constants.LOG.info("[debug copyschematic] {}", result);
        String reply = result;
        context.getSource().sendSuccess(() -> Component.literal(reply), false);
        return 1;
    }

    private static int chunks(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<String> lines = new ArrayList<>();
        lines.add("keep_chunks_loaded=" + JourneymenConfig.KEEP_CHUNKS_LOADED.get() + ", tickets held: " + ChunkKeeper.heldTickets().entrySet().stream()
                .map(entry -> entry.getKey().location() + " " + entry.getValue()).sorted().toList());
        if (JourneymenConfig.KEEP_CHUNKS_LOADED.get()) {
            for (ChunkKeeper.Kept kept : ChunkKeeper.kept(source.getServer())) {
                List<String> spots = kept.journeymen().stream()
                        .map(spot -> spot.name() + " at " + spot.chunk().x + " " + spot.chunk().z + " in " + spot.dimension().location()).toList();
                lines.add(kept.foreman() + " keeps " + kept.site().size() + " site chunks of " + kept.file() + " in " + kept.dimension().location()
                        + " and " + String.join(", ", spots));
            }
        }
        for (String line : lines) {
            Constants.LOG.info("[debug chunks] {}", line);
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return lines.size();
    }

    private static int paths(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<String> lines = new ArrayList<>();
        for (ServerLevel level : source.getServer().getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof JourneymanEntity journeyman) {
                    lines.add(journeyman.qualifiedName() + " " + journeyman.takePathSearches() + " path searches");
                }
            }
        }
        lines.sort(null);
        for (String line : lines) {
            Constants.LOG.info("[debug paths] {}", line);
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return lines.size();
    }

    private static int runAs(CommandContext<CommandSourceStack> context) {
        CommandSourceStack caller = context.getSource();
        String name = StringArgumentType.getString(context, "player");
        int permission = IntegerArgumentType.getInteger(context, "permission");
        Vec3 pos = Vec3Argument.getVec3(context, "pos");
        String command = StringArgumentType.getString(context, "command");
        GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name);
        ServerPlayer player = Services.PLATFORM.getFakePlayer(caller.getLevel(), profile);
        player.moveTo(pos.x, pos.y, pos.z, 0.0F, 0.0F);
        CommandSource forward = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                Constants.LOG.info("[debug as {}] {}", name, message.getString());
                caller.sendSystemMessage(message);
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        Constants.LOG.info("[debug as {}] /{}", name, command);
        CommandSourceStack stack = player.createCommandSourceStack().withSource(forward).withPermission(permission);
        caller.getServer().getCommands().performPrefixedCommand(stack, command);
        return 1;
    }
}
