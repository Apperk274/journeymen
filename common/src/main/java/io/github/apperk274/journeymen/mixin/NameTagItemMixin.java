package io.github.apperk274.journeymen.mixin;

import io.github.apperk274.journeymen.entity.BuilderEntity;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.NameTagItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(NameTagItem.class)
public class NameTagItemMixin {

    @Inject(method = "interactLivingEntity", at = @At("HEAD"), cancellable = true)
    private void journeymen$renameBuilder(ItemStack stack, Player player, LivingEntity target, InteractionHand hand,
                                          CallbackInfoReturnable<InteractionResult> cir) {
        if (target instanceof BuilderEntity builder) {
            cir.setReturnValue(builder.renameWithNameTag(stack, player));
        }
    }
}
