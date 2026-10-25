package io.github.apperk274.journeymen.config;

import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.platform.Services;
import io.github.apperk274.journeymen.hire.HirePrice;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Function;
import java.util.function.Predicate;

public final class JourneymenConfig {

    private static final List<Entry<?>> ENTRIES = new ArrayList<>();

    public static final Entry<Double> SPEED_MULTIPLIER = add(new Entry<>("speed_multiplier",
            "How fast builders break and place blocks. 1.0 is a survival player's pace.",
            1.0, Double::parseDouble, value -> value > 0 && value <= 100));
    public static final Entry<Boolean> KEEP_CHUNKS_LOADED = add(new Entry<>("keep_chunks_loaded",
            "Whether a builder with an active job keeps the chunks it works in loaded, so it builds while nobody is nearby.",
            false, JourneymenConfig::parseBoolean, value -> true));
    public static final Entry<Integer> REDO_LIMIT = add(new Entry<>("redo_limit",
            "How many times a builder fixes the same position after someone changed it during a job.",
            3, Integer::parseInt, value -> value >= 0));
    public static final Entry<Integer> MAX_JOB_VOLUME = add(new Entry<>("max_job_volume",
            "Largest schematic volume (width x height x length) a builder accepts as a job.",
            256 * 256 * 256, Integer::parseInt, value -> value > 0));
    public static final Entry<Integer> MAX_SITE_DISTANCE = add(new Entry<>("max_site_distance",
            "How far, in blocks, a builder may be from a build site when it gets the job.",
            128, Integer::parseInt, value -> value > 0));
    public static final Entry<Integer> REMINDER_INTERVAL_SECONDS = add(new Entry<>("reminder_interval_seconds",
            "How often a paused builder reminds its owner, in seconds.",
            300, Integer::parseInt, value -> value > 0));
    public static final Entry<Integer> WAIT_REPORT_SECONDS = add(new Entry<>("wait_report_seconds",
            "How long a builder waits for someone standing in its way before it tells its owner, in seconds.",
            10, Integer::parseInt, value -> value >= 0));
    public static final Entry<Integer> STUCK_TELEPORT_SECONDS = add(new Entry<>("stuck_teleport_seconds",
            "How long a builder may be stuck without getting closer to where it is going (a chest, the build site, you) before it teleports to a safe spot next to it, in seconds (0 = never).",
            20, Integer::parseInt, value -> value >= 0));
    public static final Entry<Integer> MAX_CREW_SIZE = add(new Entry<>("max_crew_size",
            "How many builders can work on one job together (0 = no limit).",
            8, Integer::parseInt, value -> value >= 0));

    public static final Entry<String> HIRE_COST_ITEM = add(new Entry<>("hire_cost_item",
            "The item paid to a recruiter to hire a builder: an item id such as minecraft:emerald, or an item tag such as #c:gems/emerald.",
            "#c:gems/emerald", value -> value, HirePrice::isValidSpec));
    public static final Entry<Integer> HIRE_COST_BASE = add(new Entry<>("hire_cost_base",
            "How many of that item the first builder costs.",
            10, Integer::parseInt, value -> value >= 0));
    public static final Entry<Integer> HIRE_COST_STEP = add(new Entry<>("hire_cost_step",
            "How many more each further builder costs, for every builder the player already has.",
            5, Integer::parseInt, value -> value >= 0));
    public static final Entry<Integer> MAX_BUILDERS_PER_PLAYER = add(new Entry<>("max_builders_per_player",
            "How many builders one player can hire from recruiters (0 = no limit). Ops summoning by command are not limited.",
            5, Integer::parseInt, value -> value >= 0));

    private JourneymenConfig() {
    }

    public static Path file() {
        return Services.PLATFORM.getGameDirectory().resolve("config").resolve(Constants.MOD_ID + ".toml");
    }

    public static List<String> load() {
        List<String> problems = new ArrayList<>();
        Path file = file();
        Properties properties = new Properties();
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            } catch (IOException | IllegalArgumentException e) {
                problems.add("Could not read " + file + ": " + e.getMessage());
            }
        }
        for (Entry<?> entry : ENTRIES) {
            String problem = entry.read(properties.getProperty(entry.key));
            if (problem != null) {
                problems.add(problem);
            }
        }
        try {
            write(file);
        } catch (IOException e) {
            problems.add("Could not write " + file + ": " + e.getMessage());
        }
        problems.forEach(problem -> Constants.LOG.warn("Config: {}", problem));
        return problems;
    }

    private static void write(Path file) throws IOException {
        StringBuilder text = new StringBuilder("# Journeymen config. Changes apply after /journeymen config reload or a restart.\n");
        for (Entry<?> entry : ENTRIES) {
            text.append('\n')
                    .append("# ").append(entry.comment).append('\n')
                    .append("# Default: ").append(entry.defaultValue).append('\n')
                    .append(entry.key).append(" = ").append(entry.get()).append('\n');
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static Boolean parseBoolean(String value) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("expected true or false");
        };
    }

    private static <T> Entry<T> add(Entry<T> entry) {
        ENTRIES.add(entry);
        return entry;
    }

    public static final class Entry<T> {

        private final String key;
        private final String comment;
        private final T defaultValue;
        private final Function<String, T> parser;
        private final Predicate<T> validator;
        private volatile T value;

        private Entry(String key, String comment, T defaultValue, Function<String, T> parser, Predicate<T> validator) {
            this.key = key;
            this.comment = comment;
            this.defaultValue = defaultValue;
            this.parser = parser;
            this.validator = validator;
            this.value = defaultValue;
        }

        public T get() {
            return value;
        }

        private String read(String raw) {
            if (raw == null) {
                value = defaultValue;
                return null;
            }
            try {
                T parsed = parser.apply(raw.trim());
                if (validator.test(parsed)) {
                    value = parsed;
                    return null;
                }
            } catch (IllegalArgumentException ignored) {
            }
            value = defaultValue;
            return key + " = " + raw.trim() + " is not valid, using " + defaultValue;
        }
    }
}
