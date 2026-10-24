package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.jetbrains.annotations.Nullable;

public final class ItemStores {

    private ItemStores() {
    }

    @Nullable
    public static ItemStore at(Level level, BlockPos pos) {
        Container container = HopperBlockEntity.getContainerAt(level, pos);
        if (container != null) {
            return new ContainerStore(container);
        }
        return level instanceof ServerLevel serverLevel ? Services.COMPAT.findItemStore(serverLevel, pos) : null;
    }
}
