package io.github.apperk274.journeymen.client;

import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.network.ScreenPayloads;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

public final class ScreenTest {

    private static final String FILE = "journeymen-screen-test.properties";
    private static final int CHESTS = 14;

    private enum Step { OFF, START, LOADING, SETUP, OPEN, JOB_TAB, PICK, JOB_SHOT, CHESTS_TAB, CHESTS_SHOT, CHECK, CONFIRM_SHOT, SCROLLED_SHOT, SUPPLY, SUPPLIED_SHOT, DONE }

    private static Step step;
    private static Properties settings;
    private static int ticks;
    private static int builderId = -1;
    private static BlockPos origin = BlockPos.ZERO;

    private ScreenTest() {
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
                    Constants.LOG.error("[screen test] could not read {}", file, e);
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
                if (minecraft.level == null && minecraft.getOverlay() == null && screen instanceof TitleScreen) {
                    minecraft.options.pauseOnLostFocus = false;
                    PreviewBenchmark.createWorld(minecraft, settings.getProperty("world", "jm-screen-test"));
                    next(Step.LOADING);
                }
            }
            case LOADING -> {
                if (minecraft.player != null && minecraft.level != null && screen == null && ticks > 60) {
                    PreviewBenchmark.command("gamerule doDaylightCycle false");
                    PreviewBenchmark.command("gamerule doWeatherCycle false");
                    PreviewBenchmark.command("time set noon");
                    PreviewBenchmark.command("journeymen builder summon Shot");
                    for (int i = 0; i < CHESTS; i++) {
                        PreviewBenchmark.command("setblock ~" + (i - 7) + " ~ ~-3 minecraft:chest");
                        PreviewBenchmark.command("journeymen builder chest Shot add ~" + (i - 7) + " ~ ~-3 supply");
                    }
                    origin = minecraft.player.blockPosition().offset(-3, 0, 4);
                    next(Step.SETUP);
                }
            }
            case SETUP -> {
                if (ticks > 60) {
                    for (Entity entity : minecraft.level.entitiesForRendering()) {
                        if (entity instanceof BuilderEntity) {
                            builderId = entity.getId();
                        }
                    }
                    if (builderId < 0) {
                        finish("no builder found");
                        return;
                    }
                    Services.NETWORK.sendToServer(new ScreenPayloads.BuilderAction(builderId, "schematic_list", "", BlockPos.ZERO));
                    minecraft.gameMode.interact(minecraft.player, minecraft.level.getEntity(builderId), InteractionHand.MAIN_HAND);
                    next(Step.OPEN);
                }
            }
            case OPEN -> {
                if (screen instanceof BuilderScreen && ticks > 10) {
                    clickTab(screen, 1);
                    next(Step.JOB_TAB);
                }
            }
            case JOB_TAB -> {
                List<String> files = ClientState.schematics;
                String wanted = settings.getProperty("schematic", "cottage.schem");
                int index = files.indexOf(wanted);
                if (ticks > 10 && index >= 0) {
                    int rows = 11;
                    int offset = Math.max(0, Math.min(index, files.size() - rows));
                    for (int i = 0; i < offset; i++) {
                        screen.mouseScrolled(left(screen) + 20, top(screen) + 40, 0, -1);
                    }
                    click(screen, left(screen) + 20, top(screen) + 20 + (index - offset) * 11 + 5);
                    next(Step.PICK);
                }
            }
            case PICK -> {
                ScreenPayloads.SchematicInfo info = ClientState.info;
                if (info != null && info.file().equals(settings.getProperty("schematic", "cottage.schem")) && ticks > 10) {
                    int barX = left(screen) + 248 - 3;
                    int trackTop = top(screen) + 20;
                    screen.mouseClicked(barX, trackTop + 2, 0);
                    screen.mouseDragged(barX, trackTop + 60, 0, 0, 58);
                    screen.mouseReleased(barX, trackTop + 60, 0);
                    for (int i = 0; i < 3; i++) {
                        screen.mouseScrolled(left(screen) + 20, top(screen) + 40, 0, -1);
                    }
                    next(Step.JOB_SHOT);
                }
            }
            case JOB_SHOT -> {
                if (ticks > 20) {
                    shot(minecraft, "job");
                    clickTab(screen, 2);
                    next(Step.CHESTS_TAB);
                }
            }
            case CHESTS_TAB -> {
                if (ticks > 10) {
                    screen.mouseScrolled(left(screen) + 40, top(screen) + 40, 0, -1);
                    next(Step.CHESTS_SHOT);
                }
            }
            case CHESTS_SHOT -> {
                if (ticks > 20) {
                    shot(minecraft, "chests");
                    minecraft.setScreen(null);
                    Services.NETWORK.sendToServer(new ScreenPayloads.BuilderAction(builderId, "check",
                            settings.getProperty("schematic", "cottage.schem") + "\nnone\n", origin));
                    next(Step.CHECK);
                }
            }
            case CHECK -> {
                if (screen instanceof ConfirmScreen && ticks > 20) {
                    shot(minecraft, "confirm");
                    int barX = (screen.width - 300) / 2 + 307;
                    screen.mouseClicked(barX, 32, 0);
                    screen.mouseDragged(barX, screen.height, 0, 0, screen.height);
                    screen.mouseReleased(barX, screen.height, 0);
                    next(Step.SCROLLED_SHOT);
                }
            }
            case SCROLLED_SHOT -> {
                if (ticks > 20) {
                    shot(minecraft, "confirm_scrolled");
                    Button supply = button(screen, "Supply missing (creative)");
                    if (supply == null) {
                        finish("no supply button");
                        return;
                    }
                    supply.onPress();
                    next(Step.SUPPLY);
                }
            }
            case SUPPLY -> {
                Button start = button(screen, "Start");
                boolean active = start != null && start.active;
                if (active && ticks > 10 || ticks > 300) {
                    log("start button active: " + active);
                    next(Step.SUPPLIED_SHOT);
                }
            }
            case SUPPLIED_SHOT -> {
                if (ticks > 20) {
                    shot(minecraft, "confirm_supplied");
                    finish("done");
                }
            }
            default -> {
            }
        }
    }

    private static int left(Screen screen) {
        return (screen.width - 256) / 2;
    }

    private static int top(Screen screen) {
        return (screen.height - 204) / 2;
    }

    private static void clickTab(Screen screen, int tab) {
        click(screen, left(screen) + tab * 64 + 31, top(screen) - 11);
    }

    private static void click(Screen screen, double x, double y) {
        screen.mouseClicked(x, y, 0);
        screen.mouseReleased(x, y, 0);
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
        Screenshot.grab(minecraft.gameDirectory, settings.getProperty("out", "screen") + "_" + name + ".png", minecraft.getMainRenderTarget(),
                message -> log(message.getString()));
    }

    private static void next(Step next) {
        log("step " + next);
        step = next;
        ticks = 0;
    }

    private static void finish(String reason) {
        log("finished: " + reason);
        step = Step.DONE;
        if (!"false".equals(settings.getProperty("quit", "true"))) {
            Minecraft.getInstance().stop();
        }
    }

    private static void log(String message) {
        Constants.LOG.info("[screen test] {}", message);
    }
}
