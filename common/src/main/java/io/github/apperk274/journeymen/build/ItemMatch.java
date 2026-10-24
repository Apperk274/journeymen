package io.github.apperk274.journeymen.build;

import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public final class ItemMatch {

    private ItemMatch() {
    }

    public sealed interface Key permits ItemKey, FluidKey {
    }

    public record ItemKey(Item item, Map<DataComponentType<?>, Optional<?>> components) implements Key {
    }

    public record FluidKey(Fluid fluid) implements Key {
    }

    public static Key key(ItemStack stack) {
        Fluid fluid = bucketFluid(stack);
        if (fluid != null) {
            return new FluidKey(fluid);
        }
        return new ItemKey(stack.getItem(), vanillaComponents(stack));
    }

    public static boolean same(ItemStack a, ItemStack b) {
        return !a.isEmpty() && !b.isEmpty() && key(a).equals(key(b));
    }

    public static Component name(ItemStack stack) {
        Fluid fluid = bucketFluid(stack);
        if (fluid != null) {
            return Component.empty().append(fluid.defaultFluidState().createLegacyBlock().getBlock().getName())
                    .append(Component.literal(" bucket (any kind)"));
        }
        return stack.getHoverName();
    }

    public static boolean hasVanillaData(ItemStack stack) {
        return !vanillaComponents(stack).isEmpty();
    }

    @Nullable
    public static Fluid bucketFluid(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }
        Fluid fluid = Services.COMPAT.bucketFluid(stack);
        return fluid instanceof FlowingFluid flowing ? flowing.getSource() : fluid;
    }

    private static Map<DataComponentType<?>, Optional<?>> vanillaComponents(ItemStack stack) {
        Map<DataComponentType<?>, Optional<?>> result = new HashMap<>();
        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : stack.getComponentsPatch().entrySet()) {
            ResourceLocation id = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(entry.getKey());
            if (id != null && id.getNamespace().equals(ResourceLocation.DEFAULT_NAMESPACE)) {
                result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }
}
