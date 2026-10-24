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

    public ItemCounter copy() {
        ItemCounter copy = new ItemCounter();
        copy.counts.putAll(counts);
        copy.examples.putAll(examples);
        return copy;
    }

    public int slots() {
        int slots = 0;
        for (Map.Entry<ItemMatch.Key, Long> entry : counts.entrySet()) {
            slots += slotsFor(examples.get(entry.getKey()), entry.getValue());
        }
        return slots;
    }

    public int slotsBeyond(ItemCounter carried) {
        int slots = 0;
        for (Map.Entry<ItemMatch.Key, Long> entry : counts.entrySet()) {
            ItemStack example = examples.get(entry.getKey());
            slots += Math.max(0, slotsFor(example, entry.getValue()) - slotsFor(example, carried.count(example)));
        }
        return slots;
    }

    public static int slotsFor(ItemStack stack, long count) {
        int max = Math.max(1, stack.getMaxStackSize());
        return (int) ((count + max - 1) / max);
    }

    public boolean isEmpty() {
        return counts.isEmpty();
    }

    public List<Map.Entry<ItemStack, Long>> entries() {
        return counts.entrySet().stream()
                .map(entry -> Map.entry(examples.get(entry.getKey()), entry.getValue()))
                .toList();
    }

    public List<Map.Entry<ItemStack, Long>> sorted() {
        return counts.entrySet().stream()
                .map(entry -> Map.entry(examples.get(entry.getKey()), entry.getValue()))
                .sorted(Map.Entry.<ItemStack, Long>comparingByValue(Comparator.reverseOrder())
                        .thenComparing(entry -> BuiltInRegistries.ITEM.getKey(entry.getKey().getItem()).toString()))
                .toList();
    }
}
