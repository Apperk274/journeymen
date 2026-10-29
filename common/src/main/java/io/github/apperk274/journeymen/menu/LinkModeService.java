package io.github.apperk274.journeymen.menu;

import io.github.apperk274.journeymen.build.AssignedChest;
import io.github.apperk274.journeymen.build.ItemStores;
import io.github.apperk274.journeymen.build.OwnerRights;
import io.github.apperk274.journeymen.entity.ForemanEntity;
import io.github.apperk274.journeymen.entity.JourneymanEntity;
import io.github.apperk274.journeymen.network.ScreenPayloads;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class LinkModeService {

    private static final int SESSION_TICKS = 1200;
    private static final int CHECK_INTERVAL = 20;
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private record Session(ServerPlayer player, int foremanId, boolean supply, long expiresAt) {
    }

    private LinkModeService() {
    }

    public static void start(ServerPlayer player, ForemanEntity foreman, boolean supply) {
        SESSIONS.put(player.getUUID(), new Session(player, foreman.getId(), supply, player.level().getGameTime() + SESSION_TICKS));
        player.sendSystemMessage(Component.literal("Right-click chests to link them as " + (supply ? "supply" : "dump") + " chests for "
                + foreman.getJourneymanName() + ". Right-click a linked chest again to unlink it. Right-click "
                + foreman.getJourneymanName() + " to finish.")
                .withStyle(ChatFormatting.YELLOW));
        send(player, foreman, true, supply);
    }

    public static boolean onUseBlock(ServerPlayer player, BlockPos pos) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || OwnerRights.isProbing()) {
            return false;
        }
        Entity entity = player.level().getEntity(session.foremanId());
        if (!(entity instanceof ForemanEntity foreman) || !foreman.isAlive() || player.level().getGameTime() > session.expiresAt()) {
            stop(player, "Linking chests has ended.");
            return false;
        }
        if (ItemStores.at(player.level(), pos) == null && !ItemStores.isLocked(player.level(), pos)) {
            player.displayClientMessage(Component.literal("That isn't a chest or other container.").withStyle(ChatFormatting.RED), true);
            return true;
        }
        AssignedChest existing = foreman.chestAt(player.level(), pos);
        if (existing != null) {
            foreman.removeChest(player.level().dimension(), existing.pos());
            player.displayClientMessage(Component.literal(foreman.getJourneymanName() + " no longer uses this " + existing.role().id() + " chest."), true);
        } else {
            String problem = foreman.addChest(player.level(), pos, session.supply() ? AssignedChest.Role.SUPPLY : AssignedChest.Role.DUMP);
            player.displayClientMessage(problem != null
                    ? Component.literal(problem).withStyle(ChatFormatting.RED)
                    : Component.literal(foreman.getJourneymanName() + " uses this as a " + (session.supply() ? "supply" : "dump") + " chest.")
                            .withStyle(ChatFormatting.GREEN), true);
        }
        SESSIONS.put(player.getUUID(), new Session(player, session.foremanId(), session.supply(), player.level().getGameTime() + SESSION_TICKS));
        send(player, foreman, true, session.supply());
        return true;
    }

    public static boolean finish(ServerPlayer player, JourneymanEntity clicked) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null) {
            return false;
        }
        boolean same = clicked instanceof ForemanEntity && session.foremanId() == clicked.getId();
        stop(player, same ? "Finished linking chests for " + clicked.getJourneymanName() + "." : "Linking chests has ended.");
        return same;
    }

    public static boolean isLinking(ServerPlayer player) {
        return SESSIONS.containsKey(player.getUUID());
    }

    public static void forget(ServerPlayer player) {
        SESSIONS.remove(player.getUUID());
    }

    public static void reset() {
        SESSIONS.clear();
    }

    public static void tick(MinecraftServer server) {
        if (SESSIONS.isEmpty() || server.getTickCount() % CHECK_INTERVAL != 0) {
            return;
        }
        for (Map.Entry<UUID, Session> entry : List.copyOf(SESSIONS.entrySet())) {
            ServerPlayer listed = server.getPlayerList().getPlayer(entry.getKey());
            ServerPlayer player = listed != null ? listed : entry.getValue().player();
            if (player.hasDisconnected()) {
                SESSIONS.remove(entry.getKey());
                continue;
            }
            Entity entity = player.level().getEntity(entry.getValue().foremanId());
            if (!(entity instanceof ForemanEntity foreman) || !foreman.isAlive()) {
                stop(player, "Linking chests has ended: the foreman isn't here anymore.");
            } else if (player.level().getGameTime() > entry.getValue().expiresAt()) {
                stop(player, "Linking chests has ended.");
            }
        }
    }

    public static void foremanGone(ForemanEntity foreman) {
        if (SESSIONS.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, Session> entry : List.copyOf(SESSIONS.entrySet())) {
            Session session = entry.getValue();
            if (session.foremanId() == foreman.getId()) {
                MinecraftServer server = foreman.getServer();
                ServerPlayer listed = server == null ? null : server.getPlayerList().getPlayer(entry.getKey());
                ServerPlayer player = listed != null ? listed : session.player();
                if (player.hasDisconnected()) {
                    SESSIONS.remove(entry.getKey());
                } else {
                    stop(player, "Linking chests for " + foreman.getJourneymanName() + " has ended: he isn't here anymore.");
                }
            }
        }
    }

    private static void stop(ServerPlayer player, String message) {
        Session session = SESSIONS.remove(player.getUUID());
        player.sendSystemMessage(Component.literal(message).withStyle(ChatFormatting.YELLOW));
        Services.NETWORK.sendToPlayer(player, new ScreenPayloads.LinkMode(session == null ? -1 : session.foremanId(), false,
                session != null && session.supply(), List.of()));
    }

    private static void send(ServerPlayer player, ForemanEntity foreman, boolean active, boolean supply) {
        Services.NETWORK.sendToPlayer(player, new ScreenPayloads.LinkMode(foreman.getId(), active, supply, chestEntries(foreman)));
    }

    public static List<ScreenPayloads.ChestEntry> chestEntries(@Nullable ForemanEntity foreman) {
        List<ScreenPayloads.ChestEntry> chests = new ArrayList<>();
        if (foreman != null) {
            for (AssignedChest chest : foreman.getChests()) {
                chests.add(new ScreenPayloads.ChestEntry(chest.pos(), chest.role() == AssignedChest.Role.SUPPLY,
                        chest.isIn(foreman.level().dimension()) ? "" : chest.dimension().location().toString()));
            }
        }
        return chests;
    }
}
