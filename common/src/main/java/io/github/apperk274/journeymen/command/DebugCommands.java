package io.github.apperk274.journeymen.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.menu.BuilderMenu;
import io.github.apperk274.journeymen.menu.BuilderScreenService;
import io.github.apperk274.journeymen.menu.LinkModeService;
import io.github.apperk274.journeymen.network.ScreenPayloads;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.GameModeArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

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
                        .then(Commands.argument("builder", EntityArgument.entity())
                                .then(Commands.argument("action", StringArgumentType.word())
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .then(Commands.argument("text", StringArgumentType.greedyString())
                                                        .executes(DebugCommands::action))))))
                .then(Commands.literal("gamemode")
                        .then(Commands.argument("player", StringArgumentType.word())
                                .then(Commands.argument("gamemode", GameModeArgument.gameMode())
                                        .executes(DebugCommands::gameMode))))
                .then(Commands.literal("use")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(DebugCommands::use)))
                .then(Commands.literal("interact")
                        .then(Commands.argument("entity", EntityArgument.entity())
                                .executes(DebugCommands::interact)));
    }

    private static int action(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Entity entity = EntityArgument.getEntity(context, "builder");
        String text = StringArgumentType.getString(context, "text");
        BuilderScreenService.handle(new ScreenPayloads.BuilderAction(entity.getId(), StringArgumentType.getString(context, "action"),
                text.equals("-") ? "" : text.replace('|', '\n'), BlockPosArgument.getBlockPos(context, "pos")), player);
        if (entity instanceof BuilderEntity builder) {
            Constants.LOG.info("[debug action] {} alive={} following={} job={} chests={}", builder.getBuilderName(), builder.isAlive(),
                    builder.isFollowing(), builder.getJob() == null ? "-" : builder.getJob().getFile(), builder.getChests());
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

    private static int use(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        boolean handled = LinkModeService.onUseBlock(player, BlockPosArgument.getBlockPos(context, "pos"));
        Constants.LOG.info("[debug use] handled={}", handled);
        return 1;
    }

    private static int interact(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        Entity entity = EntityArgument.getEntity(context, "entity");
        boolean linkingBefore = LinkModeService.isLinking(player);
        InteractionResult result = player.interactOn(entity, InteractionHand.MAIN_HAND);
        Constants.LOG.info("[debug interact] {} result={} linking={}->{} menu={}", entity.getName().getString(), result, linkingBefore,
                LinkModeService.isLinking(player), player.containerMenu instanceof BuilderMenu menu ? "builder tab " + menu.getActiveTab() : "none");
        return 1;
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
