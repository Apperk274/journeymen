package io.github.apperk274.journeymen.build;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

public record ContainerStore(Container container, int size) implements ItemStore {

    public ContainerStore(Container container) {
        this(container, container.getContainerSize());
    }

    @Override
    public ItemStack get(int slot) {
        return container.getItem(slot);
    }

    @Override
    public ItemStack take(int slot, int count) {
        ItemStack taken = container.removeItem(slot, count);
        container.setChanged();
        return taken;
    }

    @Override
    public ItemStack insert(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty() || !accepts(slot, stack)) {
            return stack;
        }
        ItemStack existing = container.getItem(slot);
        int room = existing.isEmpty() ? maxStack(slot, stack) : maxStack(slot, stack) - existing.getCount();
        if (!existing.isEmpty() && !ItemStack.isSameItemSameComponents(existing, stack) || room <= 0) {
            return stack;
        }
        int moved = Math.min(room, stack.getCount());
        if (!simulate) {
            container.setItem(slot, existing.isEmpty() ? stack.copyWithCount(moved) : existing.copyWithCount(existing.getCount() + moved));
            container.setChanged();
        }
        return stack.copyWithCount(stack.getCount() - moved);
    }

    @Override
    public boolean accepts(int slot, ItemStack stack) {
        return container.canPlaceItem(slot, stack);
    }

    @Override
    public int maxStack(int slot, ItemStack stack) {
        return Math.min(container.getMaxStackSize(stack), stack.getMaxStackSize());
    }

    @Override
    public void setChanged() {
        container.setChanged();
    }
}
