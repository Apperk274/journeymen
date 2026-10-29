package io.github.apperk274.journeymen.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Map;

public final class ToolChoice {

    public static final int HAND = -1;
    public static final int NONE = -2;

    private ToolChoice() {
    }

    public static boolean needsTool(BlockState state, ServerLevel level, BlockPos pos) {
        return state.requiresCorrectToolForDrops() && state.getDestroySpeed(level, pos) > 0.0F;
    }

    public static int choose(Container inventory, BlockState state, ServerLevel level, BlockPos pos, boolean allowBreakingEnchanted,
                             Map<BlockState, Integer> requiredRemaining) {
        boolean required = needsTool(state, level, pos);
        if (WaterRules.meltsWhenBroken(state)) {
            int iceTool = iceTool(inventory, state, level, pos, allowBreakingEnchanted, required);
            if (iceTool != NONE) {
                return iceTool;
            }
        }
        int best = HAND;
        float bestSpeed = 1.0F;
        int bestVersatility = Integer.MAX_VALUE;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty() || stack.get(DataComponents.TOOL) == null) {
                continue;
            }
            int cost = ToolCheck.damagePerBlock(stack, state, level, pos);
            int usable = ToolCheck.usableDurability(stack, allowBreakingEnchanted);
            if (cost > usable) {
                continue;
            }
            float speed = stack.getDestroySpeed(state);
            if (required) {
                if (!stack.isCorrectToolForDrops(state)) {
                    continue;
                }
                int versatility = versatility(stack, state, requiredRemaining);
                if (best == HAND || versatility < bestVersatility || versatility == bestVersatility && speed > bestSpeed) {
                    best = slot;
                    bestSpeed = speed;
                    bestVersatility = versatility;
                }
            } else if (speed > bestSpeed && usable - cost >= reserved(stack, requiredRemaining)) {
                best = slot;
                bestSpeed = speed;
            }
        }
        return required && best == HAND ? NONE : best;
    }

    private static int iceTool(Container inventory, BlockState state, ServerLevel level, BlockPos pos, boolean allowBreakingEnchanted,
                               boolean required) {
        int best = NONE;
        float bestSpeed = 0.0F;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!WaterRules.keepsIceFromMelting(stack) || required && !stack.isCorrectToolForDrops(state)
                    || ToolCheck.damagePerBlock(stack, state, level, pos) > ToolCheck.usableDurability(stack, allowBreakingEnchanted)
                    || ToolCheck.usableDurability(stack, allowBreakingEnchanted) < 1) {
                continue;
            }
            float speed = stack.getDestroySpeed(state);
            if (best == NONE || speed > bestSpeed) {
                best = slot;
                bestSpeed = speed;
            }
        }
        return best;
    }

    private static int versatility(ItemStack stack, BlockState current, Map<BlockState, Integer> requiredRemaining) {
        int count = 0;
        for (BlockState state : requiredRemaining.keySet()) {
            if (!state.equals(current) && stack.isCorrectToolForDrops(state)) {
                count++;
            }
        }
        return count;
    }

    private static int reserved(ItemStack stack, Map<BlockState, Integer> requiredRemaining) {
        Tool tool = stack.get(DataComponents.TOOL);
        if (tool == null || !stack.isDamageableItem()) {
            return 0;
        }
        int reserved = 0;
        for (Map.Entry<BlockState, Integer> entry : requiredRemaining.entrySet()) {
            if (stack.isCorrectToolForDrops(entry.getKey())) {
                reserved += entry.getValue() * tool.damagePerBlock();
            }
        }
        return reserved;
    }
}
