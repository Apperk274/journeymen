package io.github.apperk274.journeymen.platform;

import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.menu.BuilderMenu;
import io.github.apperk274.journeymen.platform.services.IMenuHelper;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;

public class FabricMenuHelper implements IMenuHelper {

    public static final MenuType<BuilderMenu> BUILDER_MENU =
            new ExtendedScreenHandlerType<>((containerId, inventory, builderId) -> new BuilderMenu(containerId, inventory, builderId), ByteBufCodecs.VAR_INT);

    @Override
    public MenuType<BuilderMenu> builderMenu() {
        return BUILDER_MENU;
    }

    @Override
    public void openBuilderMenu(ServerPlayer player, BuilderEntity builder) {
        player.openMenu(new ExtendedScreenHandlerFactory<Integer>() {
            @Override
            public Integer getScreenOpeningData(ServerPlayer serverPlayer) {
                return builder.getId();
            }

            @Override
            public Component getDisplayName() {
                return builder.getDisplayName();
            }

            @Override
            public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player p) {
                return new BuilderMenu(containerId, inventory, builder);
            }
        });
    }
}
