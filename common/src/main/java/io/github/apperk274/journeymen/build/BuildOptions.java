package io.github.apperk274.journeymen.build;

import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record BuildOptions(boolean corner, boolean allowBreakingEnchanted) {

    public static final BuildOptions DEFAULT = new BuildOptions(false, false);
    public static final String CORNER = "corner";
    public static final String ALLOW_BREAKING_ENCHANTED = "allow-breaking-enchanted";
    public static final List<String> ALL = List.of(CORNER, ALLOW_BREAKING_ENCHANTED);

    public static BuildOptions parse(String text) throws IllegalArgumentException {
        boolean corner = false;
        boolean allowBreakingEnchanted = false;
        for (String word : text.trim().split("\\s+")) {
            switch (word.toLowerCase(Locale.ROOT)) {
                case "" -> {
                }
                case CORNER -> corner = true;
                case ALLOW_BREAKING_ENCHANTED -> allowBreakingEnchanted = true;
                default -> throw new IllegalArgumentException("Unknown option '" + word + "'. Options: " + String.join(", ", ALL));
            }
        }
        return new BuildOptions(corner, allowBreakingEnchanted);
    }

    public String describe() {
        List<String> words = new ArrayList<>();
        if (corner) {
            words.add(CORNER);
        }
        if (allowBreakingEnchanted) {
            words.add(ALLOW_BREAKING_ENCHANTED);
        }
        return words.isEmpty() ? "none" : String.join(" ", words);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Corner", corner);
        tag.putBoolean("AllowBreakingEnchanted", allowBreakingEnchanted);
        return tag;
    }

    public static BuildOptions load(CompoundTag tag) {
        return new BuildOptions(tag.getBoolean("Corner"), tag.getBoolean("AllowBreakingEnchanted"));
    }
}
