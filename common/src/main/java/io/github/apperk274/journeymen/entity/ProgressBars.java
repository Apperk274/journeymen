package io.github.apperk274.journeymen.entity;

import io.github.apperk274.journeymen.build.Crew;
import io.github.apperk274.journeymen.build.OwnerRights;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class ProgressBars {

    public static final double RANGE = 64.0;
    private static final int CHECK_INTERVAL = 10;

    private static final Map<UUID, Bar> BARS = new HashMap<>();
    private static final Set<String> WATCHERS = new HashSet<>();

    private record Bar(String foreman, ServerBossEvent event) {
    }

    private ProgressBars() {
    }

    @Nullable
    public static ServerPlayer viewer(ForemanEntity foreman) {
        ServerPlayer owner = foreman.getOwnerPlayer();
        if (owner != null || foreman.getOwner() == null || !Services.PLATFORM.isDevelopmentEnvironment()
                || !WATCHERS.contains(foreman.getOwnerName()) || !(foreman.level() instanceof ServerLevel level)) {
            return owner;
        }
        return OwnerRights.actor(level, foreman.getOwner(), foreman.getOwnerName());
    }

    public static boolean inRange(ForemanEntity foreman, @Nullable ServerPlayer viewer) {
        if (viewer == null) {
            return false;
        }
        if (viewer != foreman.getOwnerPlayer()) {
            return true;
        }
        return viewer.level() == foreman.level() && viewer.distanceToSqr(foreman) <= RANGE * RANGE;
    }

    public static void show(ForemanEntity foreman, ServerPlayer viewer, Component title, BossEvent.BossBarColor color, float progress) {
        Bar bar = BARS.get(foreman.getUUID());
        if (bar == null) {
            bar = new Bar(foreman.qualifiedName(), new ServerBossEvent(title, color, BossEvent.BossBarOverlay.PROGRESS));
            BARS.put(foreman.getUUID(), bar);
        }
        ServerBossEvent event = bar.event();
        event.setName(title);
        event.setColor(color);
        event.setProgress(Math.max(0.0F, Math.min(1.0F, progress)));
        if (!event.getPlayers().contains(viewer)) {
            event.removeAllPlayers();
            event.addPlayer(viewer);
        }
    }

    public static void tick(MinecraftServer server) {
        if (BARS.isEmpty() || server.getTickCount() % CHECK_INTERVAL != 0) {
            return;
        }
        JourneymanRegistry registry = JourneymanRegistry.get(server);
        for (UUID id : List.copyOf(BARS.keySet())) {
            if (!(registry.loaded(server, id) instanceof ForemanEntity foreman) || !Crew.isTicking(foreman) || foreman.getJob() == null
                    || !inRange(foreman, viewer(foreman))) {
                remove(id);
            }
        }
    }

    public static void remove(UUID foreman) {
        Bar bar = BARS.remove(foreman);
        if (bar != null) {
            bar.event().removeAllPlayers();
        }
    }

    public static void watch(String ownerName) {
        WATCHERS.add(ownerName);
    }

    public static void reset() {
        BARS.values().forEach(bar -> bar.event().removeAllPlayers());
        BARS.clear();
        WATCHERS.clear();
    }

    public static List<String> describe() {
        List<String> lines = new ArrayList<>();
        BARS.forEach((id, bar) -> lines.add(bar.foreman() + ": " + bar.event().getName().getString() + " (" + bar.event().getColor().getName()
                + ", " + bar.event().getPlayers().size() + " viewer" + (bar.event().getPlayers().size() == 1 ? ")" : "s)")));
        lines.sort(null);
        return lines;
    }
}
