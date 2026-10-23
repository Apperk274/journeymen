package io.github.apperk274.journeymen.mixin;

import io.github.apperk274.journeymen.client.PlacementMode;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MinecraftMixin {

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void journeymen$confirmPlacement(CallbackInfo ci) {
        if (PlacementMode.onUse()) {
            ci.cancel();
        }
    }
}
