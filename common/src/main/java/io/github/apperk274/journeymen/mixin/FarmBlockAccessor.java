package io.github.apperk274.journeymen.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.FarmBlock;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(FarmBlock.class)
public interface FarmBlockAccessor {

    @Invoker("isNearWater")
    static boolean journeymen$isNearWater(LevelReader level, BlockPos pos) {
        throw new AssertionError();
    }
}
