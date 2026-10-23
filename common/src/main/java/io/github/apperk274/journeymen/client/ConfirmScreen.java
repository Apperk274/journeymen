package io.github.apperk274.journeymen.client;

import io.github.apperk274.journeymen.network.ScreenPayloads;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.level.block.Rotation;

import java.util.ArrayList;
import java.util.List;

public class ConfirmScreen extends Screen {

    private static final int WIDTH = 300;

    private ScreenPayloads.CheckResult result;
    private final List<FormattedCharSequence> wrapped = new ArrayList<>();
    private int scroll;
    private Button startButton;

    public ConfirmScreen(ScreenPayloads.CheckResult result) {
        super(Component.literal("Start building?"));
        this.result = result;
    }

    public boolean matches(ScreenPayloads.CheckResult other) {
        return other.builderId() == result.builderId() && other.file().equals(result.file()) && other.origin().equals(result.origin());
    }

    public void update(ScreenPayloads.CheckResult updated) {
        this.result = updated;
        rebuildWidgets();
    }

    @Override
    protected void init() {
        wrapped.clear();
        List<Component> lines = new ArrayList<>(result.lines());
        if (!result.error().isEmpty()) {
            lines.add(Component.literal(result.error()).withStyle(ChatFormatting.RED));
        }
        for (Component line : lines) {
            wrapped.addAll(font.split(line, WIDTH));
        }
        int left = (width - WIDTH) / 2;
        int bottom = height - 32;
        startButton = addRenderableWidget(Button.builder(Component.literal("Start"), button -> start())
                .bounds(left, bottom, 96, 20).build());
        startButton.active = result.passed();
        addRenderableWidget(Button.builder(Component.literal("Move"), button -> back()).bounds(left + 102, bottom, 96, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> cancel()).bounds(left + 204, bottom, 96, 20).build());
    }

    private void start() {
        startButton.active = false;
        String text = result.file() + "\n" + result.rotation() + "\n" + result.options();
        Services.NETWORK.sendToServer(new ScreenPayloads.BuilderAction(result.builderId(), "start", text, result.origin()));
    }

    private void back() {
        ScreenPayloads.SchematicInfo info = ClientState.info;
        onClose();
        if (info != null && info.file().equals(result.file())) {
            Rotation rotation = Rotation.NONE;
            for (Rotation candidate : Rotation.values()) {
                if (candidate.getSerializedName().equals(result.rotation())) {
                    rotation = candidate;
                }
            }
            PlacementMode.resume(result.builderId(), info, rotation, result.options().contains("corner"),
                    result.options().contains("allow-breaking-enchanted"));
        }
    }

    private void cancel() {
        PlacementMode.stop();
        onClose();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scroll = Math.max(0, Math.min(Math.max(0, wrapped.size() - visibleLines()), scroll - (int) Math.signum(scrollY)));
        return true;
    }

    private int visibleLines() {
        return Math.max(1, (height - 32 - 40) / (font.lineHeight + 1));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 12, 0xFFFFFF);
        int left = (width - WIDTH) / 2;
        int y = 30;
        for (int i = scroll; i < wrapped.size() && i < scroll + visibleLines(); i++) {
            graphics.drawString(font, wrapped.get(i), left, y, 0xFFFFFF);
            y += font.lineHeight + 1;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
