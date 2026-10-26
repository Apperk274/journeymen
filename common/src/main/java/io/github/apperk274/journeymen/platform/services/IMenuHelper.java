package io.github.apperk274.journeymen.platform.services;

import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.ForemanEntity;
import io.github.apperk274.journeymen.menu.BuilderMenu;
import io.github.apperk274.journeymen.menu.ForemanMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;

public interface IMenuHelper {

    MenuType<BuilderMenu> builderMenu();

    MenuType<ForemanMenu> foremanMenu();

    void openBuilderMenu(ServerPlayer player, BuilderEntity builder, int tab);

    void openForemanMenu(ServerPlayer player, ForemanEntity foreman, int tab);
}
