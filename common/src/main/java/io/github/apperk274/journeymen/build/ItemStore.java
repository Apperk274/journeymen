package io.github.apperk274.journeymen.build;

import net.minecraft.world.item.ItemStack;

public interface ItemStore {

    int size();

    ItemStack get(int slot);

    ItemStack take(int slot, int count);

    ItemStack insert(int slot, ItemStack stack, boolean simulate);

    boolean accepts(int slot, ItemStack stack);

    int maxStack(int slot, ItemStack stack);

    void setChanged();

    default ItemStack insert(ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (int slot = 0; slot < size() && !remaining.isEmpty(); slot++) {
            if (ItemStack.isSameItemSameComponents(get(slot), remaining)) {
                remaining = insert(slot, remaining, false);
            }
        }
        for (int slot = 0; slot < size() && !remaining.isEmpty(); slot++) {
            if (get(slot).isEmpty()) {
                remaining = insert(slot, remaining, false);
            }
        }
        return remaining;
    }
}
