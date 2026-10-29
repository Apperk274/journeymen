package io.github.apperk274.journeymen.client;

import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.entity.JourneymanEntity;
import io.github.apperk274.journeymen.network.ScreenPayloads;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
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

    private enum Step {
        OFF, START, LOADING, SETUP, OPEN, PICK, JOB_SHOT, CHESTS_TAB, CHESTS_SHOT, CREW_TAB, CREW_SHOT, ORDERS_TAB, ORDERS_SHOT,
        BUILDER_OPEN, BUILDER_SHOT, BUILDER_ORDERS, BUILDER_ORDERS_SHOT, CHECK, CONFIRM_SHOT, SCROLLED_SHOT, SUPPLY, SUPPLIED_SHOT, STARTED,
        RUNNING, RUNNING_SHOT, CREW_RUNNING, CREW_RUNNING_SHOT, LATE_RESULT, GONE_REPLY, LINK_OPEN, LINK_STARTED, LINK_ENDED, DIMENSION,
        DONE
    }

    private static Step step;
    private static Properties settings;
    private static int ticks;
    private static int foremanId = -1;
    private static int builderId = -1;
    private static BlockPos origin = BlockPos.ZERO;
    private static final int GONE_ID = Integer.MAX_VALUE - 7;
    @Nullable
    private static ScreenPayloads.SchematicInfo late;

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
        String schematic = settings.getProperty("schematic", "cottage.schem");
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
                    PreviewBenchmark.command("journeymen foreman summon Boss");
                    PreviewBenchmark.command("journeymen builder summon Boss Shot");
                    PreviewBenchmark.command("journeymen builder summon Boss Ann");
                    PreviewBenchmark.command("journeymen foreman summon Gone");
                    PreviewBenchmark.command("journeymen builder summon Gone Orph");
                    PreviewBenchmark.command("journeymen builder summon Gone Ivy");
                    PreviewBenchmark.command("journeymen foreman kill Gone");
                    for (int i = 0; i < CHESTS; i++) {
                        PreviewBenchmark.command("setblock ~" + (i - 7) + " ~ ~-3 minecraft:chest");
                        PreviewBenchmark.command("journeymen foreman chest Boss add ~" + (i - 7) + " ~ ~-3 supply");
                    }
                    origin = minecraft.player.blockPosition().offset(-3, 0, 4);
                    next(Step.SETUP);
                }
            }
            case SETUP -> {
                if (ticks > 60) {
                    foremanId = find(minecraft, "Boss");
                    builderId = find(minecraft, "Shot");
                    if (foremanId < 0 || builderId < 0) {
                        finish("no foreman or builder found");
                        return;
                    }
                    interact(minecraft, foremanId);
                    next(Step.OPEN);
                }
            }
            case OPEN -> {
                List<String> files = ClientState.schematics;
                int index = files.indexOf(schematic);
                if (screen instanceof ForemanScreen foremanScreen && ticks > 10 && index >= 0) {
                    int rows = 11;
                    int offset = Math.max(0, Math.min(index, files.size() - rows));
                    for (int i = 0; i < offset; i++) {
                        screen.mouseScrolled(foremanScreen.panelLeft() + 20, foremanScreen.panelTop() + 40, 0, -1);
                    }
                    click(screen, foremanScreen.panelLeft() + 20, foremanScreen.panelTop() + 20 + (index - offset) * 11 + 5);
                    next(Step.PICK);
                }
            }
            case PICK -> {
                ScreenPayloads.SchematicInfo info = ClientState.info;
                if (screen instanceof ForemanScreen foremanScreen && info != null && info.file().equals(schematic) && ticks > 10) {
                    int barX = foremanScreen.panelLeft() + 248 - 3;
                    int trackTop = foremanScreen.panelTop() + 20;
                    screen.mouseClicked(barX, trackTop + 2, 0);
                    screen.mouseDragged(barX, trackTop + 60, 0, 0, 58);
                    screen.mouseReleased(barX, trackTop + 60, 0);
                    for (int i = 0; i < 3; i++) {
                        screen.mouseScrolled(foremanScreen.panelLeft() + 20, foremanScreen.panelTop() + 40, 0, -1);
                    }
                    next(Step.JOB_SHOT);
                }
            }
            case JOB_SHOT -> {
                if (ticks > 20) {
                    shot(minecraft, "job");
                    press(screen, "Chests");
                    next(Step.CHESTS_TAB);
                }
            }
            case CHESTS_TAB -> {
                if (screen instanceof ForemanScreen foremanScreen && ticks > 10) {
                    screen.mouseScrolled(foremanScreen.panelLeft() + 40, foremanScreen.panelTop() + 40, 0, -1);
                    next(Step.CHESTS_SHOT);
                }
            }
            case CHESTS_SHOT -> {
                if (ticks > 20) {
                    shot(minecraft, "chests");
                    press(screen, "Crew");
                    next(Step.CREW_TAB);
                }
            }
            case CREW_TAB -> {
                if (ticks > 20) {
                    next(Step.CREW_SHOT);
                }
            }
            case CREW_SHOT -> {
                if (ticks > 10) {
                    shot(minecraft, "crew");
                    press(screen, "Orders");
                    next(Step.ORDERS_TAB);
                }
            }
            case ORDERS_TAB -> {
                if (ticks > 20) {
                    next(Step.ORDERS_SHOT);
                }
            }
            case ORDERS_SHOT -> {
                if (ticks > 10) {
                    shot(minecraft, "orders");
                    minecraft.setScreen(null);
                    next(Step.BUILDER_OPEN);
                }
            }
            case BUILDER_OPEN -> {
                if (ticks > 10) {
                    interact(minecraft, builderId);
                    next(Step.BUILDER_SHOT);
                }
            }
            case BUILDER_SHOT -> {
                if (screen instanceof BuilderScreen && ticks > 30) {
                    shot(minecraft, "builder_inventory");
                    press(screen, "Orders");
                    next(Step.BUILDER_ORDERS);
                }
            }
            case BUILDER_ORDERS -> {
                if (ticks > 20) {
                    next(Step.BUILDER_ORDERS_SHOT);
                }
            }
            case BUILDER_ORDERS_SHOT -> {
                if (ticks > 10) {
                    shot(minecraft, "builder_orders");
                    minecraft.setScreen(null);
                    if (ClientState.info == null) {
                        finish("no schematic info for placement mode");
                        return;
                    }
                    PlacementMode.resume(foremanId, ClientState.info, Rotation.NONE, "");
                    Services.NETWORK.sendToServer(new ScreenPayloads.Action(foremanId, "check", schematic + "\nnone\n", origin));
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
                    if ("false".equals(settings.getProperty("start", "true"))) {
                        finish("done");
                        return;
                    }
                    Button start = button(screen, "Start");
                    if (start == null || !start.active) {
                        finish("start not possible");
                        return;
                    }
                    start.onPress();
                    next(Step.STARTED);
                }
            }
            case STARTED -> {
                if (screen == null && ticks > 60) {
                    interact(minecraft, foremanId);
                    next(Step.RUNNING);
                }
            }
            case RUNNING -> {
                ScreenPayloads.ForemanState state = ClientState.FOREMEN.get(foremanId);
                if (screen instanceof ForemanScreen && state != null && state.working() && ticks > 30) {
                    next(Step.RUNNING_SHOT);
                }
            }
            case RUNNING_SHOT -> {
                if (ticks > 10) {
                    shot(minecraft, "job_running");
                    press(screen, "Crew");
                    next(Step.CREW_RUNNING);
                }
            }
            case CREW_RUNNING -> {
                if (ticks > 40) {
                    next(Step.CREW_RUNNING_SHOT);
                }
            }
            case CREW_RUNNING_SHOT -> {
                if (ticks > 10) {
                    shot(minecraft, "crew_running");
                    minecraft.setScreen(null);
                    late = ClientState.info;
                    if (late == null) {
                        finish("no schematic info for placement mode");
                        return;
                    }
                    PlacementMode.resume(foremanId, late, Rotation.NONE, "");
                    Services.NETWORK.sendToServer(new ScreenPayloads.Action(foremanId, "check", schematic + "\nnone\n", origin));
                    PlacementMode.stop();
                    minecraft.setScreen(new ChatScreen(""));
                    next(Step.LATE_RESULT);
                }
            }
            case LATE_RESULT -> {
                if (ticks > 100) {
                    log("late check result: the open screen is " + (screen == null ? "none" : screen.getClass().getSimpleName()));
                    minecraft.setScreen(null);
                    PlacementMode.resume(GONE_ID, late, Rotation.NONE, "");
                    Services.NETWORK.sendToServer(new ScreenPayloads.Action(GONE_ID, "check", schematic + "\nnone\n", origin));
                    next(Step.GONE_REPLY);
                }
            }
            case GONE_REPLY -> {
                if (!PlacementMode.isActive() || ticks > 100) {
                    log("placement mode after a gone reply: " + (PlacementMode.isActive() ? "still on" : "off"));
                    PlacementMode.stop();
                    interact(minecraft, foremanId);
                    next(Step.LINK_OPEN);
                }
            }
            case LINK_OPEN -> {
                if (screen instanceof ForemanScreen && ticks > 20) {
                    press(screen, "Chests");
                    press(screen, "Link supply chests");
                    next(Step.LINK_STARTED);
                }
            }
            case LINK_STARTED -> {
                if (ClientState.link != null && ticks > 10 || ticks > 100) {
                    log("link outlines while linking: " + (ClientState.link != null ? "on" : "off"));
                    PreviewBenchmark.command("journeymen foreman kill Boss");
                    next(Step.LINK_ENDED);
                }
            }
            case LINK_ENDED -> {
                if (ClientState.link == null || ticks > 100) {
                    log("link outlines after the foreman died: " + (ClientState.link != null ? "on" : "off"));
                    PlacementMode.resume(foremanId, late, Rotation.NONE, "");
                    PreviewBenchmark.command("execute in minecraft:the_nether run tp @s ~ 120 ~");
                    next(Step.DIMENSION);
                }
            }
            case DIMENSION -> {
                boolean nether = minecraft.level != null && minecraft.level.dimension() == Level.NETHER;
                if (nether && ticks > 40 || ticks > 400) {
                    log("placement mode after changing dimension: " + (PlacementMode.isActive() ? "still on" : "off"));
                    finish("done");
                }
            }
            default -> {
            }
        }
    }

    private static int find(Minecraft minecraft, String name) {
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof JourneymanEntity && entity.getCustomName() != null && entity.getCustomName().getString().equals(name)) {
                return entity.getId();
            }
        }
        return -1;
    }

    private static void interact(Minecraft minecraft, int id) {
        Entity entity = minecraft.level.getEntity(id);
        if (entity != null) {
            minecraft.gameMode.interact(minecraft.player, entity, InteractionHand.MAIN_HAND);
        }
    }

    private static void press(@Nullable Screen screen, String label) {
        Button button = button(screen, label);
        if (button != null) {
            button.onPress();
        } else {
            log("no button " + label);
        }
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
