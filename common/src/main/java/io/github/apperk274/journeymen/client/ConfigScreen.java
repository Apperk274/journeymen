package io.github.apperk274.journeymen.client;

import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.build.ChunkKeeper;
import io.github.apperk274.journeymen.config.JourneymenConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ConfigScreen extends Screen {

    private static final int MAX_WIDTH = 360;
    private static final int LINE = 10;
    private static final int RESET_WIDTH = 46;

    public enum Mode {
        MENU, SINGLE_PLAYER, SERVER
    }

    @Nullable
    private final Screen parent;
    private final Mode mode;
    private final Map<String, String> original;
    private final Map<String, String> values;
    private final List<Row> rows = new ArrayList<>();
    private final ScrollBar scrollBar = new ScrollBar();
    private List<Component> status = List.of();
    private Button saveButton;

    public ConfigScreen(@Nullable Screen parent) {
        this(parent, mode(Minecraft.getInstance()));
    }

    public ConfigScreen(@Nullable Screen parent, Mode mode) {
        super(Component.literal(Constants.MOD_NAME + " config"));
        this.parent = parent;
        this.mode = mode;
        this.original = JourneymenConfig.fileValues();
        this.values = new LinkedHashMap<>(original);
    }

    public static Mode mode(Minecraft minecraft) {
        if (minecraft.level == null) {
            return Mode.MENU;
        }
        return minecraft.getSingleplayerServer() != null ? Mode.SINGLE_PLAYER : Mode.SERVER;
    }

    public Mode getMode() {
        return mode;
    }

    public boolean editable() {
        return mode != Mode.SERVER;
    }

    @Override
    protected void init() {
        rows.clear();
        int width = listWidth();
        for (JourneymenConfig.Entry<?> entry : JourneymenConfig.entries()) {
            rows.add(new Row(entry, font.split(Component.literal(entry.comment()), width)));
        }
        int bottom = height - 28;
        if (editable()) {
            saveButton = addRenderableWidget(Button.builder(Component.literal("Save"), button -> save())
                    .bounds(this.width / 2 - 122, bottom, 120, 20).build());
            addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
                    .bounds(this.width / 2 + 2, bottom, 120, 20).build());
        } else {
            saveButton = null;
            addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                    .bounds(this.width / 2 - 60, bottom, 120, 20).build());
        }
        scrollBar.place(left() + width + 4, listTop(), listBottom() - listTop());
        layoutRows();
        updateButtons();
    }

    private int listWidth() {
        return Math.min(MAX_WIDTH, width - 40);
    }

    private int left() {
        return (width - listWidth()) / 2;
    }

    private int listTop() {
        return mode == Mode.SERVER ? 48 : 38;
    }

    private int listBottom() {
        return height - 46 - status.size() * LINE;
    }

    private Component note() {
        return switch (mode) {
            case MENU -> Component.literal("Saved to config/" + Constants.MOD_ID + ".toml for this game's worlds.").withStyle(ChatFormatting.GRAY);
            case SINGLE_PLAYER -> Component.literal("Saved to config/" + Constants.MOD_ID + ".toml and applied to this world at once.").withStyle(ChatFormatting.GRAY);
            case SERVER -> Component.literal("Read-only: this server uses the settings in its own config/" + Constants.MOD_ID + ".toml.")
                    .withStyle(ChatFormatting.YELLOW);
        };
    }

    private List<String> invalidKeys() {
        return rows.stream().filter(row -> !row.valid()).map(row -> row.entry.key()).toList();
    }

    private void layoutRows() {
        int bottom = listBottom();
        int space = bottom - listTop();
        int fitting = 0;
        int used = 0;
        for (int i = rows.size() - 1; i >= 0; i--) {
            used += rows.get(i).height();
            if (used > space) {
                break;
            }
            fitting++;
        }
        scrollBar.place(left() + listWidth() + 4, listTop(), space);
        scrollBar.setContent(rows.size(), Math.max(1, fitting));
        int offset = scrollBar.offset();
        int y = listTop();
        boolean full = false;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            boolean shown = i >= offset && !full && (y + row.height() <= bottom || i == offset);
            if (i >= offset && !shown) {
                full = true;
            }
            row.place(shown ? y : -1);
            if (shown) {
                y += row.height();
            }
        }
    }

    private boolean allValid() {
        return rows.stream().allMatch(Row::valid);
    }

    private boolean changed() {
        return !values.equals(original);
    }

    private void updateButtons() {
        if (saveButton != null) {
            saveButton.active = allValid() && changed();
        }
        rows.forEach(Row::updateReset);
    }

    private void save() {
        Map<String, String> changes = new LinkedHashMap<>();
        values.forEach((key, value) -> {
            if (!value.equals(original.get(key))) {
                changes.put(key, original.get(key) + " -> " + value);
            }
        });
        JourneymenConfig.Saved saved = JourneymenConfig.save(values);
        if (!saved.saved()) {
            setStatus(saved.problems());
            return;
        }
        IntegratedServer server = minecraft.getSingleplayerServer();
        if (server != null) {
            server.execute(() -> ChunkKeeper.update(server));
        }
        Constants.LOG.info("Config saved from the config screen ({}): {}", mode, changes);
        if (!saved.problems().isEmpty()) {
            setStatus(saved.problems());
            return;
        }
        onClose();
    }

    private void setStatus(List<String> problems) {
        status = problems.stream().map(problem -> (Component) Component.literal(problem).withStyle(ChatFormatting.RED)).toList();
        rebuildWidgets();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        scrollBar.scroll(scrollY);
        layoutRows();
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (scrollBar.mouseClicked(mouseX, mouseY, button)) {
            layoutRows();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (scrollBar.mouseDragged(mouseY, button)) {
            layoutRows();
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return scrollBar.mouseReleased(button) || super.mouseReleased(mouseX, mouseY, button);
    }

    public void scrollToEnd() {
        scrollBar.setOffset(rows.size());
        layoutRows();
    }

    public void setValue(String key, String value) {
        for (Row row : rows) {
            if (row.entry.key().equals(key)) {
                row.set(value);
            }
        }
    }

    public void pressSave() {
        if (saveButton != null && saveButton.active) {
            saveButton.onPress();
        }
    }

    public boolean canSave() {
        return saveButton != null && saveButton.active;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, 10, 0xFFFFFF);
        graphics.drawCenteredString(font, note(), width / 2, 22, 0xFFFFFF);
        if (mode == Mode.SERVER) {
            graphics.drawCenteredString(font, Component.literal("Shown: this game's own file, used in single player.").withStyle(ChatFormatting.GRAY),
                    width / 2, 32, 0xFFFFFF);
        }
        int left = left();
        int right = left + listWidth();
        for (Row row : rows) {
            if (row.y < 0) {
                continue;
            }
            int y = row.y;
            boolean changed = !values.get(row.entry.key()).equals(original.get(row.entry.key()));
            graphics.drawString(font, Component.literal(row.entry.key() + (changed ? " *" : "")).withStyle(changed ? ChatFormatting.YELLOW : ChatFormatting.WHITE),
                    left, y + 6, 0xFFFFFF);
            int lineY = y + 24;
            for (FormattedCharSequence line : row.comment) {
                graphics.drawString(font, line, left, lineY, 0xC8C8C8);
                lineY += LINE;
            }
            graphics.drawString(font, row.statusLine(), left, lineY, 0xFFFFFF);
            graphics.fill(left, y + row.height() - 3, right, y + row.height() - 2, 0x40FFFFFF);
        }
        int statusY = listBottom() + 4;
        for (Component line : status) {
            graphics.drawCenteredString(font, line, width / 2, statusY, 0xFFFFFF);
            statusY += LINE;
        }
        List<String> invalid = invalidKeys();
        if (!invalid.isEmpty()) {
            Component line = Component.literal((invalid.size() == 1 ? "Not valid: " : invalid.size() + " not valid: ") + String.join(", ", invalid))
                    .withStyle(ChatFormatting.RED);
            graphics.drawString(font, ScreenParts.fit(font, line, listWidth()), left, height - 40, 0xFFFFFF);
        }
        scrollBar.render(graphics);
    }

    private final class Row {

        private final JourneymenConfig.Entry<?> entry;
        private final List<FormattedCharSequence> comment;
        private final AbstractWidget control;
        private final Button reset;
        private int y = -1;

        private Row(JourneymenConfig.Entry<?> entry, List<FormattedCharSequence> comment) {
            this.entry = entry;
            this.comment = comment;
            String value = values.get(entry.key());
            if (entry.type() == JourneymenConfig.Type.BOOLEAN && (value.equals("true") || value.equals("false"))) {
                CycleButton<Boolean> toggle = CycleButton.onOffBuilder(Boolean.parseBoolean(value)).displayOnlyValue()
                        .create(0, 0, 80, 20, Component.literal(entry.key()), (button, on) -> changed(String.valueOf(on)));
                toggle.active = editable();
                control = toggle;
            } else {
                EditBox box = new EditBox(font, 0, 0, entry.type() == JourneymenConfig.Type.TEXT ? 130 : 80, 20, Component.literal(entry.key()));
                box.setMaxLength(entry.type() == JourneymenConfig.Type.TEXT ? 128 : 32);
                box.setValue(value);
                box.setEditable(editable());
                box.setResponder(this::changed);
                control = box;
            }
            reset = Button.builder(Component.literal("Default"), button -> set(entry.defaultText())).size(RESET_WIDTH, 20).build();
            addRenderableWidget(control);
            addRenderableWidget(reset);
            colour();
        }

        private int height() {
            return 24 + comment.size() * LINE + LINE + 6;
        }

        private boolean valid() {
            return entry.isValid(values.get(entry.key()));
        }

        private void place(int y) {
            this.y = y;
            boolean shown = y >= 0;
            int right = left() + listWidth();
            control.visible = shown;
            reset.visible = shown;
            if (!shown && getFocused() == control) {
                setFocused(null);
            }
            control.setPosition(right - RESET_WIDTH - 4 - control.getWidth(), Math.max(y, 0));
            reset.setPosition(right - RESET_WIDTH, Math.max(y, 0));
        }

        private void set(String value) {
            if (control instanceof EditBox box) {
                box.setValue(value);
            } else if (control instanceof CycleButton<?> toggle) {
                @SuppressWarnings("unchecked")
                CycleButton<Boolean> booleans = (CycleButton<Boolean>) toggle;
                booleans.setValue(Boolean.parseBoolean(value));
                changed(value);
            }
        }

        private void changed(String value) {
            values.put(entry.key(), value);
            colour();
            updateButtons();
        }

        private void colour() {
            if (control instanceof EditBox box) {
                box.setTextColor(valid() ? 0xE0E0E0 : 0xFF5555);
            }
        }

        private void updateReset() {
            reset.active = editable() && !values.get(entry.key()).trim().equals(entry.defaultText());
        }

        private Component statusLine() {
            String value = values.get(entry.key());
            if (!valid()) {
                return Component.literal("Not valid: " + (value.isBlank() ? "empty" : value) + " (" + entry.range() + ", default " + entry.defaultText() + ")")
                        .withStyle(ChatFormatting.RED);
            }
            if (entry == JourneymenConfig.HIRE_COST_ITEM && !value.trim().startsWith("#")) {
                ResourceLocation id = ResourceLocation.tryParse(value.trim());
                if (id != null && !BuiltInRegistries.ITEM.containsKey(id)) {
                    return Component.literal("No item " + id + " in this game; hiring refuses until it exists.").withStyle(ChatFormatting.GOLD);
                }
            }
            return Component.literal(capitalise(entry.range()) + " · default " + entry.defaultText()).withStyle(ChatFormatting.GRAY);
        }
    }

    private static String capitalise(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }
}
