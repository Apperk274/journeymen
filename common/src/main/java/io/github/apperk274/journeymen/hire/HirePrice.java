package io.github.apperk274.journeymen.hire;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public record HirePrice(@Nullable TagKey<Item> tag, Item example) {

    @Nullable
    public static HirePrice parse(String spec) {
        String value = spec.strip();
        if (value.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(value.substring(1));
            if (id == null) {
                return null;
            }
            TagKey<Item> tag = TagKey.create(Registries.ITEM, id);
            Optional<Item> first = BuiltInRegistries.ITEM.getTag(tag)
                    .flatMap(set -> set.stream().map(Holder::value).filter(item -> item != Items.AIR).findFirst());
            return first.map(item -> new HirePrice(tag, item)).orElse(null);
        }
        ResourceLocation id = ResourceLocation.tryParse(value);
        Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).filter(found -> found != Items.AIR).orElse(null);
        return item == null ? null : new HirePrice(null, item);
    }

    public static boolean isValidSpec(String spec) {
        String value = spec.strip();
        return ResourceLocation.tryParse(value.startsWith("#") ? value.substring(1) : value) != null;
    }

    public boolean matches(ItemStack stack) {
        return tag != null ? stack.is(tag) : stack.is(example);
    }

    public ItemStack display(Inventory inventory, int count) {
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (matches(stack)) {
                return new ItemStack(stack.getItem(), count);
            }
        }
        return new ItemStack(example, count);
    }

    public int count(Inventory inventory) {
        int count = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (matches(stack)) {
                count += stack.getCount();
            }
        }
        return count;
    }
}
