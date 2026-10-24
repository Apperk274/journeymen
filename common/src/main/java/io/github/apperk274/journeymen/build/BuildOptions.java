package io.github.apperk274.journeymen.build;

import net.minecraft.nbt.CompoundTag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public record BuildOptions(boolean corner, boolean allowBreakingEnchanted, boolean skipUnplaceable, boolean skipMissing) {

    public static final BuildOptions DEFAULT = new BuildOptions(false, false, false, false);
    public static final String CORNER = "corner";
    public static final String ALLOW_BREAKING_ENCHANTED = "allow-breaking-enchanted";
    public static final String SKIP_UNPLACEABLE = "skip-unplaceable";
    public static final String SKIP_MISSING = "skip-missing";
    public static final List<String> ALL = List.of(CORNER, ALLOW_BREAKING_ENCHANTED, SKIP_UNPLACEABLE, SKIP_MISSING);

    public static BuildOptions parse(String text) throws IllegalArgumentException {
        boolean corner = false;
        boolean allowBreakingEnchanted = false;
        boolean skipUnplaceable = false;
        boolean skipMissing = false;
        for (String word : text.trim().split("\\s+")) {
            switch (word.toLowerCase(Locale.ROOT)) {
                case "" -> {
                }
                case CORNER -> corner = true;
                case ALLOW_BREAKING_ENCHANTED -> allowBreakingEnchanted = true;
                case SKIP_UNPLACEABLE -> skipUnplaceable = true;
                case SKIP_MISSING -> skipMissing = true;
                default -> throw new IllegalArgumentException("Unknown option '" + word + "'. Options: " + String.join(", ", ALL));
            }
        }
        return new BuildOptions(corner, allowBreakingEnchanted, skipUnplaceable, skipMissing);
    }

    public String describe() {
        List<String> words = new ArrayList<>();
        if (corner) {
            words.add(CORNER);
        }
        if (allowBreakingEnchanted) {
            words.add(ALLOW_BREAKING_ENCHANTED);
        }
        if (skipUnplaceable) {
            words.add(SKIP_UNPLACEABLE);
        }
        if (skipMissing) {
            words.add(SKIP_MISSING);
        }
        return words.isEmpty() ? "none" : String.join(" ", words);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("Corner", corner);
        tag.putBoolean("AllowBreakingEnchanted", allowBreakingEnchanted);
        tag.putBoolean("SkipUnplaceable", skipUnplaceable);
        tag.putBoolean("SkipMissing", skipMissing);
        return tag;
    }

    public static BuildOptions load(CompoundTag tag) {
        return new BuildOptions(tag.getBoolean("Corner"), tag.getBoolean("AllowBreakingEnchanted"), tag.getBoolean("SkipUnplaceable"),
                tag.getBoolean("SkipMissing"));
    }
}
