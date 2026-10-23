package io.github.apperk274.journeymen.platform.services;

import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.menu.BuilderMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;

public interface IMenuHelper {

    MenuType<BuilderMenu> builderMenu();

    void openBuilderMenu(ServerPlayer player, BuilderEntity builder);
}
