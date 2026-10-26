package io.github.apperk274.journeymen.platform;

import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.ForemanEntity;
import io.github.apperk274.journeymen.menu.BuilderMenu;
import io.github.apperk274.journeymen.menu.ForemanMenu;
import io.github.apperk274.journeymen.platform.services.IMenuHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;

public class NeoForgeMenuHelper implements IMenuHelper {

    public static final MenuType<BuilderMenu> BUILDER_MENU =
            IMenuTypeExtension.create((containerId, inventory, data) -> new BuilderMenu(containerId, inventory, BuilderMenu.OpenData.CODEC.decode(data)));
    public static final MenuType<ForemanMenu> FOREMAN_MENU =
            IMenuTypeExtension.create((containerId, inventory, data) -> new ForemanMenu(containerId, inventory, ForemanMenu.OpenData.CODEC.decode(data)));

    @Override
    public MenuType<BuilderMenu> builderMenu() {
        return BUILDER_MENU;
    }

    @Override
    public MenuType<ForemanMenu> foremanMenu() {
        return FOREMAN_MENU;
    }

    @Override
    public void openBuilderMenu(ServerPlayer player, BuilderEntity builder, int tab) {
        player.openMenu(new SimpleMenuProvider((containerId, inventory, p) -> new BuilderMenu(containerId, inventory, builder, tab),
                builder.getDisplayName()), data -> BuilderMenu.OpenData.CODEC.encode(data, new BuilderMenu.OpenData(builder.getId(), tab)));
    }

    @Override
    public void openForemanMenu(ServerPlayer player, ForemanEntity foreman, int tab) {
        player.openMenu(new SimpleMenuProvider((containerId, inventory, p) -> new ForemanMenu(containerId, foreman, tab),
                foreman.getDisplayName()), data -> ForemanMenu.OpenData.CODEC.encode(data, new ForemanMenu.OpenData(foreman.getId(), tab)));
    }
}
