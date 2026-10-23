package io.github.apperk274.journeymen.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.github.apperk274.journeymen.block.ModBlocks;
import io.github.apperk274.journeymen.entity.RecruiterEntity;
import io.github.apperk274.journeymen.hire.HireService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class RecruiterCommands {

    static LiteralArgumentBuilder<CommandSourceStack> recruiter() {
        return Commands.literal("recruiter")
                .then(Commands.literal("hire")
                        .executes(context -> hire(context, null))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(context -> hire(context, StringArgumentType.getString(context, "name")))));
    }

    static LiteralArgumentBuilder<CommandSourceStack> stall() {
        return Commands.literal("stall")
                .requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("place").then(Commands.argument("pos", BlockPosArgument.blockPos())
                        .executes(context -> placeStall(context, null))
                        .then(Commands.argument("facing", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(List.of("north", "east", "south", "west"), builder))
                                .executes(context -> placeStall(context, StringArgumentType.getString(context, "facing"))))));
    }

    private static int hire(CommandContext<CommandSourceStack> context, @Nullable String name) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        RecruiterEntity recruiter = HireService.nearestRecruiter(player);
        if (recruiter == null) {
            throw JourneymenCommand.fail("There is no recruiter within " + (int) HireService.RANGE + " blocks.");
        }
        HireService.hire(player, recruiter, name).forEach(line -> context.getSource().sendSuccess(() -> line, false));
        return 1;
    }

    private static int placeStall(CommandContext<CommandSourceStack> context, @Nullable String facingName) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");
        Direction facing = facingName == null ? Direction.fromYRot(source.getRotation().y).getOpposite() : Direction.byName(facingName);
        if (facing == null || facing.getAxis().isVertical()) {
            throw JourneymenCommand.fail("Facing must be north, east, south or west.");
        }
        if (!ModBlocks.RECRUITERS_STALL.place(source.getLevel(), pos, facing)) {
            throw JourneymenCommand.fail("There isn't room for a Recruiter's Stall there (it needs 3 wide, 2 deep and 3 tall).");
        }
        source.sendSuccess(() -> Component.literal("Placed a Recruiter's Stall facing " + facing.getName() + "."), true);
        return 1;
    }
}
