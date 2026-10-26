package io.github.apperk274.journeymen.client;

import io.github.apperk274.journeymen.entity.JourneymanNames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;

import java.util.function.Consumer;

final class ScreenParts {

    static final int TEXT = 0x404040;
    static final int TAB_HEIGHT = 21;
    private static final ResourceLocation BUTTON = ResourceLocation.withDefaultNamespace("widget/button");
    private static final ResourceLocation BUTTON_HIGHLIGHTED = ResourceLocation.withDefaultNamespace("widget/button_highlighted");
    private static final ResourceLocation BUTTON_DISABLED = ResourceLocation.withDefaultNamespace("widget/button_disabled");

    private ScreenParts() {
    }

    static int top(int height, int imageHeight) {
        return Math.max((height - imageHeight) / 2, Math.min(TAB_HEIGHT + 1, height - imageHeight));
    }

    static void panel(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xFF000000);
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, 0xFFC6C6C6);
        graphics.fill(x + 1, y + 1, x + width - 2, y + 2, 0xFFFFFFFF);
        graphics.fill(x + 1, y + 1, x + 2, y + height - 2, 0xFFFFFFFF);
        graphics.fill(x + 2, y + height - 2, x + width - 1, y + height - 1, 0xFF555555);
        graphics.fill(x + width - 2, y + 2, x + width - 1, y + height - 1, 0xFF555555);
    }

    static void inset(GuiGraphics graphics, int x1, int y1, int x2, int y2) {
        graphics.fill(x1, y1, x2, y2, 0xFF373737);
        graphics.fill(x1 + 1, y1 + 1, x2, y2, 0xFFFFFFFF);
        graphics.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, 0xFF8B8B8B);
    }

    static void slot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 18, y + 18, 0xFF373737);
        graphics.fill(x + 1, y + 1, x + 18, y + 18, 0xFFFFFFFF);
        graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
    }

    static void smallButton(GuiGraphics graphics, Font font, String label, int x, int y, int width, int height, boolean active, boolean hovered) {
        graphics.blitSprite(!active ? BUTTON_DISABLED : hovered ? BUTTON_HIGHLIGHTED : BUTTON, x, y, width, height);
        graphics.drawCenteredString(font, label, x + width / 2, y + (height - 8) / 2, active ? 0xFFFFFF : 0xA0A0A0);
    }

    static FormattedCharSequence fit(Font font, Component text, int width) {
        if (font.width(text) <= width) {
            return text.getVisualOrderText();
        }
        FormattedText ellipsis = Component.literal("…");
        return Language.getInstance().getVisualOrder(FormattedText.composite(font.substrByWidth(text, width - font.width(ellipsis)), ellipsis));
    }

    static Checkbox checkbox(Font font, Component label, int x, int y, boolean selected, Checkbox.OnValueChange onValueChange) {
        Checkbox box = Checkbox.builder(Component.empty(), font).pos(x, y).selected(selected).onValueChange(onValueChange).build();
        box.setMessage(label);
        box.setWidth(Checkbox.getBoxSize(font) + 4 + font.width(label));
        return box;
    }

    static void checkboxLabel(GuiGraphics graphics, Font font, Checkbox box) {
        graphics.drawString(font, box.getMessage(), box.getX() + Checkbox.getBoxSize(font) + 4, box.getY() + (Checkbox.getBoxSize(font) - 8) / 2,
                box.active ? TEXT : 0x808080, false);
    }

    static void click() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    static EditBox nameBox(Font font, int x, int y, int width, String value, Consumer<String> responder) {
        EditBox box = new EditBox(font, x, y, width, 20, Component.literal("Name"));
        box.setMaxLength(JourneymanNames.MAX_LENGTH);
        box.setFilter(text -> text.chars().allMatch(c -> Character.isLetterOrDigit(c) && c < 128 || c == '_' || c == '-'));
        box.setValue(value);
        box.setResponder(responder);
        return box;
    }
}
