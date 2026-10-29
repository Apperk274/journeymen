package io.github.apperk274.journeymen.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import io.github.apperk274.journeymen.entity.JourneymanEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(FarmBlock.class)
public class FarmBlockMixin {

    @WrapWithCondition(method = "fallOn", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/FarmBlock;turnToDirt(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;)V"))
    private boolean journeymen$keepFarmland(Entity entity, BlockState state, Level level, BlockPos pos) {
        return !(entity instanceof JourneymanEntity);
    }
}
