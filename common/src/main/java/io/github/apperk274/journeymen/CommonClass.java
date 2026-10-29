package io.github.apperk274.journeymen;

import io.github.apperk274.journeymen.build.BlockDrops;
import io.github.apperk274.journeymen.build.ChunkKeeper;
import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.entity.JourneymanEntity;
import io.github.apperk274.journeymen.entity.JourneymanRegistry;
import io.github.apperk274.journeymen.menu.LinkModeService;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

public class CommonClass {

    public static void init() {
        Constants.LOG.info("{} initializing on {} ({})", Constants.MOD_NAME, Services.PLATFORM.getPlatformName(), Services.PLATFORM.getEnvironmentName());
        JourneymenConfig.load();
    }

    public static void onServerStarting(MinecraftServer server) {
        BlockDrops.setServer(server);
    }

    public static void onServerStopped() {
        BlockDrops.setServer(null);
        ChunkKeeper.reset();
    }

    public static void onServerTick(MinecraftServer server) {
        ChunkKeeper.tick(server);
    }

    public static void onPlayerJoin(ServerPlayer player) {
        JourneymanRegistry registry = JourneymanRegistry.get(player.server);
        registry.updateOwnerName(player.getUUID(), player.getGameProfile().getName());
        for (JourneymanRegistry.Entry entry : registry.ownedBy(player.getUUID())) {
            JourneymanEntity journeyman = registry.loaded(player.server, entry.id());
            if (journeyman != null) {
                journeyman.refreshOwnerName(player.getGameProfile().getName());
            }
        }
        LinkModeService.forget(player);
        List<String> notices = registry.takeNotices(player.getUUID());
        if (!notices.isEmpty()) {
            player.sendSystemMessage(Component.literal("While you were away, your journeymen said:").withStyle(ChatFormatting.GOLD));
            notices.forEach(notice -> player.sendSystemMessage(Component.literal("  " + notice)));
        }
    }
}
