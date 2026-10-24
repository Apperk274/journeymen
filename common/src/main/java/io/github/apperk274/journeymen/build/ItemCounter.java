package io.github.apperk274.journeymen.build;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ItemCounter {

    private final Map<ItemMatch.Key, Long> counts = new LinkedHashMap<>();
    private final Map<ItemMatch.Key, ItemStack> examples = new LinkedHashMap<>();

    public void add(ItemStack stack, long count) {
        if (stack.isEmpty() || count <= 0) {
            return;
        }
        ItemMatch.Key key = ItemMatch.key(stack);
        counts.merge(key, count, Long::sum);
        examples.putIfAbsent(key, stack.copyWithCount(1));
    }

    public long count(ItemStack stack) {
        return stack.isEmpty() ? 0 : counts.getOrDefault(ItemMatch.key(stack), 0L);
    }

    public long remove(ItemStack stack, long count) {
        if (stack.isEmpty()) {
            return 0;
        }
        ItemMatch.Key key = ItemMatch.key(stack);
        long available = counts.getOrDefault(key, 0L);
        long removed = Math.min(available, count);
        if (removed == available) {
            counts.remove(key);
            examples.remove(key);
        } else {
            counts.put(key, available - removed);
        }
        return removed;
    }

    public boolean isEmpty() {
        return counts.isEmpty();
    }

    public List<Map.Entry<ItemStack, Long>> sorted() {
        return counts.entrySet().stream()
                .map(entry -> Map.entry(examples.get(entry.getKey()), entry.getValue()))
                .sorted(Map.Entry.<ItemStack, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(entry -> BuiltInRegistries.ITEM.getKey(entry.getKey().getItem()).toString()))
                .toList();
    }
}
