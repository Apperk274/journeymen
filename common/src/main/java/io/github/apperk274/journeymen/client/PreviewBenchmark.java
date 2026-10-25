package io.github.apperk274.journeymen.client;

import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.network.ScreenPayloads;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

public final class PreviewBenchmark {

    private static final String FILE = "journeymen-preview-test.properties";

    private enum Step { OFF, START, LOADING, SETUP, SUMMON, INFO, BASELINE, WAIT_MESH, MEASURE, MOVING, SHOT, DONE }

    private static Step step;
    private static Properties settings;
    private static int ticks;
    private static int builderId = -1;
    private static int rotationIndex;
    private static List<Rotation> rotations = List.of();
    private static List<String> schematics = List.of();
    private static int schematicIndex;
    private static final List<Long> FRAMES = new ArrayList<>();
    private static long lastFrame;
    private static boolean recording;
    private static int frameCount;
    private static long waitStart;

    private PreviewBenchmark() {
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
                    Constants.LOG.error("[preview test] could not read {}", file, e);
                    return;
                }
                rotations = Arrays.stream(settings.getProperty("rotations", "none").split(","))
                        .map(name -> Arrays.stream(Rotation.values()).filter(r -> r.getSerializedName().equals(name.strip())).findFirst().orElse(Rotation.NONE))
                        .toList();
                schematics = Arrays.stream(settings.getProperty("schematic", "").split(",")).map(String::strip).toList();
                step = Step.START;
            }
        }
        if (step == Step.OFF || step == Step.DONE) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        ticks++;
        switch (step) {
            case START -> {
                if (minecraft.level == null && minecraft.getOverlay() == null && minecraft.screen instanceof TitleScreen) {
                    minecraft.options.pauseOnLostFocus = false;
                    minecraft.options.framerateLimit().set(260);
                    minecraft.options.enableVsync().set(false);
                    createWorld(minecraft, settings.getProperty("world", "jm-preview-test"));
                    next(Step.LOADING);
                }
            }
            case LOADING -> {
                if (minecraft.player != null && minecraft.level != null && minecraft.screen == null && ticks > 60) {
                    command("gamerule doDaylightCycle false");
                    command("gamerule doWeatherCycle false");
                    command("time set noon");
                    command("attribute @s minecraft:player.block_interaction_range base set 256");
                    command("journeymen builder summon Bench");
                    next(Step.SETUP);
                }
            }
            case SETUP -> {
                fly(minecraft);
                if (ticks > 40) {
                    command("tp @s " + settings.getProperty("pos", "-60 40 -60") + " " + settings.getProperty("yaw", "-45") + " " + settings.getProperty("pitch", "40"));
                    next(Step.SUMMON);
                }
            }
            case SUMMON -> {
                fly(minecraft);
                if (ticks > 300) {
                    for (Entity entity : minecraft.level.entitiesForRendering()) {
                        if (entity instanceof BuilderEntity) {
                            builderId = entity.getId();
                        }
                    }
                    if (builderId < 0) {
                        finish("no builder found");
                        return;
                    }
                    requestInfo();
                }
            }
            case INFO -> {
                fly(minecraft);
                ScreenPayloads.SchematicInfo info = ClientState.info;
                if (info != null && info.file().equals(schematic())) {
                    log(schematic() + ": info received after " + (System.nanoTime() - waitStart) / 1_000_000 + " ms, complete=" + info.complete() + ", parts=" + info.parts());
                    minecraft.options.hideGui = true;
                    startRecording();
                    next(Step.BASELINE);
                } else if (ticks > 1200) {
                    finish("no schematic info");
                }
            }
            case BASELINE -> {
                fly(minecraft);
                if (frameCount >= frames()) {
                    report(schematic() + " baseline (no preview)");
                    rotationIndex = 0;
                    beginRotation();
                }
            }
            case WAIT_MESH -> {
                fly(minecraft);
                if (PlacementMode.isPreviewReady()) {
                    log(schematic() + " preview ready for " + rotations.get(rotationIndex).getSerializedName() + " after "
                            + (System.nanoTime() - waitStart) / 1_000_000 + " ms");
                    startRecording();
                    next(Step.MEASURE);
                } else if (ticks > 1200) {
                    finish("preview never became ready");
                }
            }
            case MEASURE -> {
                fly(minecraft);
                if (frameCount >= frames()) {
                    report(schematic() + " static preview " + rotations.get(rotationIndex).getSerializedName());
                    Screenshot.grab(minecraft.gameDirectory, settings.getProperty("out", "preview") + "_" + schematic().replaceAll("\\..*", "") + "_" + rotations.get(rotationIndex).getSerializedName() + ".png",
                            minecraft.getMainRenderTarget(), message -> log(message.getString()));
                    startRecording();
                    next(Step.MOVING);
                }
            }
            case MOVING -> {
                fly(minecraft);
                if (minecraft.player != null) {
                    float yaw = Float.parseFloat(settings.getProperty("yaw", "-45"));
                    minecraft.player.setYRot(yaw + 12.0F * (float) Math.sin(ticks / 6.0));
                }
                if (frameCount >= frames()) {
                    report(schematic() + " moving preview " + rotations.get(rotationIndex).getSerializedName());
                    if (minecraft.player != null) {
                        minecraft.player.setYRot(Float.parseFloat(settings.getProperty("yaw", "-45")));
                    }
                    rotationIndex++;
                    if (rotationIndex < rotations.size()) {
                        beginRotation();
                    } else if (schematicIndex + 1 < schematics.size()) {
                        PlacementMode.stop();
                        schematicIndex++;
                        requestInfo();
                    } else {
                        next(Step.SHOT);
                    }
                }
            }
            case SHOT -> {
                if (ticks > 20) {
                    finish("done");
                }
            }
            default -> {
            }
        }
    }

    static void createWorld(Minecraft minecraft, String name) {
        minecraft.createWorldOpenFlows().createFreshLevel(name,
                new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true, new GameRules(), WorldDataConfiguration.DEFAULT),
                new WorldOptions(1L, false, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                new TitleScreen());
    }

    public static void frame() {
        if (step == null || step == Step.OFF) {
            return;
        }
        long now = System.nanoTime();
        if (recording && lastFrame != 0) {
            FRAMES.add(now - lastFrame);
            frameCount++;
        }
        lastFrame = now;
    }

    private static String schematic() {
        return schematics.get(schematicIndex);
    }

    private static void requestInfo() {
        ClientState.info = null;
        Services.NETWORK.sendToServer(new ScreenPayloads.BuilderAction(builderId, "schematic_info", schematic(), BlockPos.ZERO));
        waitStart = System.nanoTime();
        next(Step.INFO);
    }

    private static void beginRotation() {
        ScreenPayloads.SchematicInfo info = ClientState.info;
        recording = false;
        PlacementMode.resume(builderId, info, rotations.get(rotationIndex), settings.getProperty("options", ""));
        waitStart = System.nanoTime();
        next(Step.WAIT_MESH);
    }

    private static int frames() {
        return Integer.parseInt(settings.getProperty("frames", "300"));
    }

    private static void startRecording() {
        FRAMES.clear();
        frameCount = 0;
        lastFrame = 0;
        recording = true;
    }

    private static void report(String label) {
        recording = false;
        long[] sorted = FRAMES.stream().mapToLong(Long::longValue).sorted().toArray();
        if (sorted.length == 0) {
            log(label + ": no frames");
            return;
        }
        double avg = Arrays.stream(sorted).average().orElse(0) / 1_000_000.0;
        double p50 = sorted[sorted.length / 2] / 1_000_000.0;
        double p95 = sorted[Math.min(sorted.length - 1, (int) (sorted.length * 0.95))] / 1_000_000.0;
        double max = sorted[sorted.length - 1] / 1_000_000.0;
        log(String.format("%s: %d frames, avg %.2f ms (%.0f fps), p50 %.2f ms, p95 %.2f ms, max %.2f ms",
                label, sorted.length, avg, 1000.0 / avg, p50, p95, max));
    }

    private static void fly(Minecraft minecraft) {
        if (minecraft.player != null && minecraft.player.getAbilities().mayfly && !minecraft.player.getAbilities().flying) {
            minecraft.player.getAbilities().flying = true;
            minecraft.player.onUpdateAbilities();
        }
    }

    static void command(String command) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.connection.sendCommand(command);
        }
    }

    private static void next(Step next) {
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
        Constants.LOG.info("[preview test] {}", message);
    }
}
