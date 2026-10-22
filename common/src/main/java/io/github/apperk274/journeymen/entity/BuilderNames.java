package io.github.apperk274.journeymen.entity;

import net.minecraft.util.RandomSource;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

public final class BuilderNames {

    public static final int MAX_LENGTH = 24;

    private static final List<String> NAMES = List.of(
            "Ada", "Alfred", "Agnes", "Albert", "Alma", "Arthur", "Beatrice", "Bernard", "Bertha", "Cecil",
            "Clara", "Conrad", "Dora", "Edgar", "Edith", "Edmund", "Elsie", "Ernest", "Esther", "Felix",
            "Florence", "Frida", "Gerald", "Greta", "Gustav", "Harold", "Hazel", "Herbert", "Hilda", "Hugo",
            "Ida", "Irene", "Ivan", "Jasper", "Josephine", "Karl", "Lena", "Leopold", "Lotte", "Ludwig",
            "Mabel", "Magnus", "Martha", "Mathilde", "Milo", "Nora", "Oskar", "Otto", "Pearl", "Percy",
            "Rosa", "Rupert", "Ruth", "Silas", "Stella", "Theo", "Tilda", "Ulrich", "Vera", "Walter",
            "Wilma", "Winifred");

    private BuilderNames() {
    }

    public static String random(RandomSource random, Set<String> taken) {
        Set<String> lowerTaken = taken.stream().map(name -> name.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        List<String> free = NAMES.stream().filter(name -> !lowerTaken.contains(name.toLowerCase(Locale.ROOT))).toList();
        if (!free.isEmpty()) {
            return free.get(random.nextInt(free.size()));
        }
        String base = NAMES.get(random.nextInt(NAMES.size()));
        for (int number = 2; ; number++) {
            String candidate = base + "_" + number;
            if (!lowerTaken.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
        }
    }

    @Nullable
    public static String validate(String name) {
        if (name.isBlank()) {
            return "A builder's name can't be empty.";
        }
        if (name.length() > MAX_LENGTH) {
            return "A builder's name can be at most " + MAX_LENGTH + " characters long.";
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '_' || c == '-')) {
                return "A builder's name can only contain letters, digits, _ and -.";
            }
        }
        return null;
    }
}
