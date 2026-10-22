package io.github.apperk274.journeymen.build;

import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.effect.MobEffectUtil;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

public final class BreakSpeed {

    private BreakSpeed() {
    }

    public static float progressPerTick(LivingEntity miner, ItemStack tool, BlockState state, Level level, BlockPos pos) {
        float hardness = state.getDestroySpeed(level, pos);
        if (hardness < 0.0F) {
            return 0.0F;
        }
        if (hardness == 0.0F) {
            return 1.0F;
        }
        boolean correct = !state.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(state);
        return destroySpeed(miner, tool, state) / hardness / (correct ? 30.0F : 100.0F);
    }

    private static float destroySpeed(LivingEntity miner, ItemStack tool, BlockState state) {
        float speed = tool.isEmpty() ? 1.0F : tool.getDestroySpeed(state);
        if (speed > 1.0F) {
            speed += miningEfficiency(tool);
        }
        if (MobEffectUtil.hasDigSpeed(miner)) {
            speed *= 1.0F + (MobEffectUtil.getDigSpeedAmplification(miner) + 1) * 0.2F;
        }
        if (miner.hasEffect(MobEffects.DIG_SLOWDOWN)) {
            speed *= switch (miner.getEffect(MobEffects.DIG_SLOWDOWN).getAmplifier()) {
                case 0 -> 0.3F;
                case 1 -> 0.09F;
                case 2 -> 0.0027F;
                default -> 8.1E-4F;
            };
        }
        if (miner.isEyeInFluid(FluidTags.WATER)) {
            speed *= 0.2F;
        }
        if (!miner.onGround()) {
            speed /= 5.0F;
        }
        return speed;
    }

    private static float miningEfficiency(ItemStack tool) {
        float[] efficiency = {0.0F};
        EnchantmentHelper.forEachModifier(tool, EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.equals(Attributes.MINING_EFFICIENCY)) {
                efficiency[0] += (float) modifier.amount();
            }
        });
        return efficiency[0];
    }
}
