package io.github.apperk274.journeymen.client;

import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.build.ChunkKeeper;
import io.github.apperk274.journeymen.config.JourneymenConfig;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;

public final class ConfigScreenTest {

    private static final String FILE = "journeymen-config-test.properties";

    private enum Step {
        OFF, START, MODS, FILTER, SELECTED, MENU_SCREEN, INVALID, MENU_SAVED, LOADING, SP_OPEN, SP_SAVED, SP_CHECK, READONLY, SCROLLED, DONE
    }

    private static Step step;
    private static Properties settings;
    private static int ticks;
    private static Map<String, String> backup;

    private ConfigScreenTest() {
    }

    public static void tick() {
        if (step == null) {
            step = Step.OFF;
            Path file = Services.PLATFORM.getGameDirectory().resolve(FILE);
            if (Services.PLATFORM.isDevelopmentEnvironment() && Files.isRegularFile(file)) {
                settings = new Properties();
                try (Reader reader = Files.newBufferedReader(file)) {
                    settings.load(reader);
                } catch (IOException e) {
                    Constants.LOG.error("[config test] could not read {}", file, e);
                    return;
                }
                step = Step.START;
            }
        }
        if (step == Step.OFF || step == Step.DONE) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ticks++;
        if (ticks > 1200) {
            finish("stuck at " + step);
            return;
        }
        Screen screen = minecraft.screen;
        switch (step) {
            case START -> {
                if (minecraft.level == null && minecraft.getOverlay() == null && screen instanceof TitleScreen && ticks > 20) {
                    minecraft.options.pauseOnLostFocus = false;
                    backup = JourneymenConfig.fileValues();
                    Button mods = button(screen, "Mods");
                    if (mods == null) {
                        finish("no Mods button");
                        return;
                    }
                    mods.onPress();
                    next(Step.MODS);
                }
            }
            case MODS -> {
                if (screen != null && !(screen instanceof TitleScreen) && ticks > 20) {
                    for (GuiEventListener child : screen.children()) {
                        if (child instanceof EditBox search) {
                            search.setValue(Constants.MOD_NAME);
                        }
                    }
                    next(Step.FILTER);
                }
            }
            case FILTER -> {
                if (ticks > 20) {
                    shot(minecraft, "mods_filtered");
                    for (GuiEventListener child : screen.children()) {
                        if (child instanceof AbstractSelectionList<?> list) {
                            double x = list.getRowLeft() + 10;
                            double y = list.getY() + 10;
                            screen.mouseClicked(x, y, 0);
                            screen.mouseReleased(x, y, 0);
                            break;
                        }
                    }
                    next(Step.SELECTED);
                }
            }
            case SELECTED -> {
                if (screen instanceof ConfigScreen && ticks > 10) {
                    log("the mod entry's click opened the config screen");
                    next(Step.MENU_SCREEN);
                } else if (ticks > 20) {
                    shot(minecraft, "mods");
                    Button config = null;
                    for (GuiEventListener child : screen.children()) {
                        if (child instanceof Button button && button.visible && button.getMessage().getString().contains("onfig")) {
                            config = button;
                        }
                    }
                    log("config button: " + (config == null ? "none" : "'" + config.getMessage().getString() + "' active=" + config.active));
                    if (config == null || !config.active) {
                        finish("config button missing or disabled");
                        return;
                    }
                    config.onPress();
                    next(Step.MENU_SCREEN);
                }
            }
            case MENU_SCREEN -> {
                if (screen instanceof ConfigScreen config && ticks > 20) {
                    log("opened from the mods list in mode " + config.getMode());
                    shot(minecraft, "menu");
                    config.setValue("max_chests", "5000");
                    config.setValue("speed_multiplier", "fast");
                    config.setValue("hire_cost_item", "minecraft:no_such_item");
                    log("can save with invalid values: " + config.canSave());
                    next(Step.INVALID);
                }
            }
            case INVALID -> {
                if (screen instanceof ConfigScreen config && ticks > 10) {
                    shot(minecraft, "menu_invalid");
                    config.setValue("max_chests", "200");
                    config.setValue("speed_multiplier", "2.5");
                    config.setValue("hire_cost_item", "minecraft:diamond");
                    log("can save with valid values: " + config.canSave());
                    config.pressSave();
                    next(Step.MENU_SAVED);
                }
            }
            case MENU_SAVED -> {
                if (ticks > 10) {
                    log("after save the screen is " + (screen == null ? "none" : screen.getClass().getSimpleName()));
                    logValues("main menu save");
                    minecraft.setScreen(new TitleScreen());
                    PreviewBenchmark.createWorld(minecraft, settings.getProperty("world", "jm-config-test"));
                    next(Step.LOADING);
                }
            }
            case LOADING -> {
                if (minecraft.player != null && minecraft.level != null && screen == null && ticks > 60) {
                    PreviewBenchmark.command("gamerule doDaylightCycle false");
                    PreviewBenchmark.command("time set noon");
                    minecraft.setScreen(new ConfigScreen(null));
                    next(Step.SP_OPEN);
                }
            }
            case SP_OPEN -> {
                if (screen instanceof ConfigScreen config && ticks > 20) {
                    log("opened in world in mode " + config.getMode());
                    shot(minecraft, "singleplayer");
                    config.setValue("max_crew_size", "2");
                    config.setValue("keep_chunks_loaded", "true");
                    config.pressSave();
                    next(Step.SP_SAVED);
                }
            }
            case SP_SAVED -> {
                if (ticks > 10) {
                    logValues("single player save");
                    IntegratedServer server = minecraft.getSingleplayerServer();
                    if (server != null) {
                        server.execute(() -> log("server thread sees max_crew_size=" + JourneymenConfig.MAX_CREW_SIZE.get()
                                + " keep_chunks_loaded=" + JourneymenConfig.KEEP_CHUNKS_LOADED.get()));
                    }
                    PreviewBenchmark.command("journeymen foreman summon CfgBoss");
                    PreviewBenchmark.command("journeymen builder summon CfgBoss Cfga");
                    PreviewBenchmark.command("journeymen builder summon CfgBoss Cfgb");
                    PreviewBenchmark.command("journeymen builder summon CfgBoss Cfgc");
                    next(Step.SP_CHECK);
                }
            }
            case SP_CHECK -> {
                if (ticks > 40) {
                    minecraft.setScreen(new ConfigScreen(null, ConfigScreen.Mode.SERVER));
                    next(Step.READONLY);
                }
            }
            case READONLY -> {
                if (screen instanceof ConfigScreen && ticks > 20) {
                    shot(minecraft, "server_readonly");
                    ConfigScreen config = new ConfigScreen(null);
                    minecraft.setScreen(config);
                    config.scrollToEnd();
                    next(Step.SCROLLED);
                }
            }
            case SCROLLED -> {
                if (ticks > 20) {
                    shot(minecraft, "scrolled");
                    minecraft.setScreen(null);
                    finish("done");
                }
            }
            default -> {
            }
        }
    }

    private static void logValues(String when) {
        log(when + ": live speed_multiplier=" + JourneymenConfig.SPEED_MULTIPLIER.get() + " max_chests=" + JourneymenConfig.MAX_CHESTS.get()
                + " hire_cost_item=" + JourneymenConfig.HIRE_COST_ITEM.get() + " max_crew_size=" + JourneymenConfig.MAX_CREW_SIZE.get()
                + " keep_chunks_loaded=" + JourneymenConfig.KEEP_CHUNKS_LOADED.get());
        try {
            List<String> lines = Files.readAllLines(JourneymenConfig.file(), StandardCharsets.UTF_8);
            log(when + ": file has " + lines.stream().filter(line -> line.startsWith("#")).count() + " comment lines and "
                    + lines.stream().filter(line -> !line.startsWith("#") && line.contains(" = ")).toList());
        } catch (IOException e) {
            log(when + ": could not read the file: " + e.getMessage());
        }
    }

    @Nullable
    private static Button button(@Nullable Screen screen, String label) {
        if (screen == null) {
            return null;
        }
        for (GuiEventListener child : screen.children()) {
            if (child instanceof Button button && button.getMessage().getString().equals(label)) {
                return button;
            }
        }
        return null;
    }

    private static void shot(Minecraft minecraft, String name) {
        Screenshot.grab(minecraft.gameDirectory, settings.getProperty("out", "config") + "_" + name + ".png", minecraft.getMainRenderTarget(),
                message -> log(message.getString()));
    }

    private static void next(Step next) {
        log("step " + next);
        step = next;
        ticks = 0;
    }

    private static void finish(String reason) {
        if (backup != null) {
            JourneymenConfig.Saved restored = JourneymenConfig.save(backup);
            IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
            if (server != null) {
                server.execute(() -> ChunkKeeper.update(server));
            }
            log("restored the config: " + restored);
        }
        log("finished: " + reason);
        step = Step.DONE;
        if (!"false".equals(settings.getProperty("quit", "true"))) {
            Minecraft.getInstance().stop();
        }
    }

    private static void log(String message) {
        Constants.LOG.info("[config test] {}", message);
    }
}
