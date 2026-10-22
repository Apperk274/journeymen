package io.github.apperk274.journeymen.command;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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
                                                        .executes(DebugCommands::runAs))))));
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
