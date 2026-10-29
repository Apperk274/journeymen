package io.github.apperk274.journeymen.platform.services;

import io.github.apperk274.journeymen.build.BlockRequirements;
import io.github.apperk274.journeymen.build.ItemStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.Nullable;

public interface ICompatHelper {

    @Nullable
    ItemStore findItemStore(ServerLevel level, BlockPos pos);

    @Nullable
    Fluid bucketFluid(ItemStack stack);

    @Nullable
    ItemStack drainBucket(ItemStack container, Fluid fluid);

    @Nullable
    ItemStack fillBucket(ItemStack container, Fluid fluid);

    @Nullable
    BlockState toolModifiedState(ServerLevel level, BlockState state, BlockRequirements.ToolUse use);

    boolean canDo(ItemStack stack, BlockRequirements.ToolUse use);
}
