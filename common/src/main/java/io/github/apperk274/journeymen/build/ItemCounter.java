package io.github.apperk274.journeymen.build;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ItemCounter {

    private final Map<Key, Long> counts = new LinkedHashMap<>();

    public void add(ItemStack stack, long count) {
        if (stack.isEmpty() || count <= 0) {
            return;
        }
        counts.merge(new Key(stack.copyWithCount(1)), count, Long::sum);
    }

    public long count(ItemStack stack) {
        return counts.getOrDefault(new Key(stack.copyWithCount(1)), 0L);
    }

    public boolean isEmpty() {
        return counts.isEmpty();
    }

    public List<Map.Entry<ItemStack, Long>> sorted() {
        return counts.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey().stack, entry.getValue()))
                .sorted(Map.Entry.<ItemStack, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(entry -> BuiltInRegistries.ITEM.getKey(entry.getKey().getItem()).toString()))
                .toList();
    }

    private record Key(ItemStack stack) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && ItemStack.isSameItemSameComponents(stack, key.stack);
        }

        @Override
        public int hashCode() {
            return ItemStack.hashItemAndComponents(stack);
        }
    }
}
