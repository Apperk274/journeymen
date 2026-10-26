package io.github.apperk274.journeymen.menu;

import io.github.apperk274.journeymen.command.Status;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.JourneymanRegistry;
import io.github.apperk274.journeymen.network.ScreenPayloads;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.server.level.ServerPlayer;

public final class BuilderScreenService {

    private static final String[] TAB_NAMES = {"Inventory", "Orders"};

    private BuilderScreenService() {
    }

    public static void open(ServerPlayer player, BuilderEntity builder, int tab) {
        ScreenActions.logOpen(player, builder, TAB_NAMES[tab]);
        Services.MENUS.openBuilderMenu(player, builder, tab);
        sendState(player, builder);
    }

    public static void tick(BuilderEntity builder) {
        for (ServerPlayer player : builder.getServer().getPlayerList().getPlayers()) {
            if (player.containerMenu instanceof BuilderMenu menu && menu.getBuilder() == builder) {
                sendState(player, builder);
            }
        }
    }

    public static void sendState(ServerPlayer player, BuilderEntity builder) {
        String foreman = JourneymanRegistry.get(player.server).get(builder.getForemanId()).map(JourneymanRegistry.Entry::name).orElse("");
        Services.NETWORK.sendToPlayer(player, new ScreenPayloads.BuilderState(builder.getId(), builder.getJourneymanName(), builder.getOwnerName(),
                foreman, builder.isFollowing(), builder.getJob() != null, builder.getHealth(), builder.getMaxHealth(), Status.task(builder)));
    }
}
