package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.mixin.BaseContainerBlockEntityAccessor;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import org.jetbrains.annotations.Nullable;

public final class ItemStores {

    private ItemStores() {
    }

    @Nullable
    public static ItemStore at(Level level, BlockPos pos) {
        if (isLocked(level, pos)) {
            return null;
        }
        Container container = HopperBlockEntity.getContainerAt(level, pos);
        if (container != null) {
            return new ContainerStore(container);
        }
        return level instanceof ServerLevel serverLevel ? Services.COMPAT.findItemStore(serverLevel, pos) : null;
    }

    public static boolean isLocked(Level level, BlockPos pos) {
        if (isLocked(level.getBlockEntity(pos))) {
            return true;
        }
        BlockPos other = Crew.otherChestHalf(level, pos);
        return other != null && isLocked(level.getBlockEntity(other));
    }

    private static boolean isLocked(@Nullable BlockEntity blockEntity) {
        return blockEntity instanceof BaseContainerBlockEntityAccessor container && !container.journeymen$getLockKey().unlocksWith(ItemStack.EMPTY);
    }
}
