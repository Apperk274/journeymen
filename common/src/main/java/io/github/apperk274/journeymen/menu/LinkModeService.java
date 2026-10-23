package io.github.apperk274.journeymen.menu;

import io.github.apperk274.journeymen.build.AssignedChest;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.network.ScreenPayloads;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LinkModeService {

    private static final int SESSION_TICKS = 1200;
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private record Session(int builderId, boolean supply, long expiresAt) {
    }

    private LinkModeService() {
    }

    public static void start(ServerPlayer player, BuilderEntity builder, boolean supply) {
        SESSIONS.put(player.getUUID(), new Session(builder.getId(), supply, player.level().getGameTime() + SESSION_TICKS));
        player.sendSystemMessage(Component.literal("Right-click chests to link them as " + (supply ? "supply" : "dump") + " chests for "
                + builder.getBuilderName() + ". Right-click a linked chest again to unlink it. Sneak + right-click to finish.")
                .withStyle(ChatFormatting.YELLOW));
        send(player, builder, true, supply);
    }

    public static boolean onUseBlock(ServerPlayer player, BlockPos pos) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return false;
        }
        Entity entity = player.level().getEntity(session.builderId());
        if (!(entity instanceof BuilderEntity builder) || !builder.isAlive() || player.level().getGameTime() > session.expiresAt()) {
            stop(player, entity instanceof BuilderEntity builder ? builder : null, "Linking chests has ended.");
            return false;
        }
        if (player.isShiftKeyDown()) {
            stop(player, builder, "Finished linking chests for " + builder.getBuilderName() + ".");
            return true;
        }
        if (!(player.level().getBlockEntity(pos) instanceof Container)) {
            player.displayClientMessage(Component.literal("That isn't a chest or other container.").withStyle(ChatFormatting.RED), true);
            return true;
        }
        AssignedChest existing = builder.chestAt(pos);
        if (existing != null) {
            builder.removeChest(existing.pos());
            player.displayClientMessage(Component.literal(builder.getBuilderName() + " no longer uses this " + existing.role().id() + " chest."), true);
        } else {
            String problem = builder.addChest(pos, session.supply() ? AssignedChest.Role.SUPPLY : AssignedChest.Role.DUMP);
            player.displayClientMessage(problem != null
                    ? Component.literal(problem).withStyle(ChatFormatting.RED)
                    : Component.literal(builder.getBuilderName() + " uses this as a " + (session.supply() ? "supply" : "dump") + " chest.")
                            .withStyle(ChatFormatting.GREEN), true);
        }
        SESSIONS.put(player.getUUID(), new Session(session.builderId(), session.supply(), player.level().getGameTime() + SESSION_TICKS));
        send(player, builder, true, session.supply());
        return true;
    }

    public static void forget(ServerPlayer player) {
        SESSIONS.remove(player.getUUID());
    }

    public static void tick(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        if (session != null && player.level().getGameTime() > session.expiresAt()) {
            Entity entity = player.level().getEntity(session.builderId());
            stop(player, entity instanceof BuilderEntity builder ? builder : null, "Linking chests has ended.");
        }
    }

    private static void stop(ServerPlayer player, BuilderEntity builder, String message) {
        Session session = SESSIONS.remove(player.getUUID());
        player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.YELLOW));
        Services.NETWORK.sendToPlayer(player, new ScreenPayloads.LinkMode(session == null ? -1 : session.builderId(), false,
                session != null && session.supply(), List.of()));
    }

    private static void send(ServerPlayer player, BuilderEntity builder, boolean active, boolean supply) {
        List<ScreenPayloads.ChestEntry> chests = new ArrayList<>();
        for (AssignedChest chest : builder.getChests()) {
            chests.add(new ScreenPayloads.ChestEntry(chest.pos(), chest.role() == AssignedChest.Role.SUPPLY));
        }
        Services.NETWORK.sendToPlayer(player, new ScreenPayloads.LinkMode(builder.getId(), active, supply, chests));
    }
}
