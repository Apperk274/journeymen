package io.github.apperk274.journeymen.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

public final class ScrollBar {

    public static final int WIDTH = 6;
    private static final int MIN_THUMB = 32;
    private static final ResourceLocation THUMB = ResourceLocation.withDefaultNamespace("widget/scroller");
    private static final ResourceLocation TRACK = ResourceLocation.withDefaultNamespace("widget/scroller_background");

    private int x;
    private int y;
    private int height;
    private int total;
    private int visible = 1;
    private int offset;
    private boolean dragging;
    private double grab;

    public void place(int x, int y, int height) {
        this.x = x;
        this.y = y;
        this.height = Math.max(1, height);
    }

    public void setContent(int total, int visible) {
        this.total = Math.max(0, total);
        this.visible = Math.max(1, visible);
        setOffset(offset);
        if (!isVisible()) {
            dragging = false;
        }
    }

    public int offset() {
        return offset;
    }

    public void setOffset(int offset) {
        this.offset = Mth.clamp(offset, 0, maxOffset());
    }

    public boolean isVisible() {
        return total > visible;
    }

    public void scroll(double amount) {
        if (amount != 0) {
            setOffset(offset - (int) Math.signum(amount));
        }
    }

    public boolean isOver(double mouseX, double mouseY) {
        return isVisible() && mouseX >= x && mouseX < x + WIDTH && mouseY >= y && mouseY < y + height;
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0 || !isOver(mouseX, mouseY)) {
            return false;
        }
        int thumbY = thumbY();
        int thumbHeight = thumbHeight();
        dragging = true;
        if (mouseY >= thumbY && mouseY < thumbY + thumbHeight) {
            grab = mouseY - thumbY;
        } else {
            grab = thumbHeight / 2.0;
            drag(mouseY);
        }
        return true;
    }

    public boolean mouseDragged(double mouseY, int button) {
        if (!dragging || button != 0) {
            return false;
        }
        drag(mouseY);
        return true;
    }

    public boolean mouseReleased(int button) {
        boolean wasDragging = dragging && button == 0;
        if (button == 0) {
            dragging = false;
        }
        return wasDragging;
    }

    public void render(GuiGraphics graphics) {
        if (!isVisible()) {
            return;
        }
        graphics.blitSprite(TRACK, x, y, WIDTH, height);
        graphics.blitSprite(THUMB, x, thumbY(), WIDTH, thumbHeight());
    }

    private void drag(double mouseY) {
        int range = height - thumbHeight();
        if (range > 0) {
            setOffset((int) Math.round((mouseY - grab - y) * maxOffset() / range));
        }
    }

    private int maxOffset() {
        return Math.max(0, total - visible);
    }

    private int thumbHeight() {
        int max = Math.max(1, height - 8);
        return Mth.clamp((int) ((long) height * visible / Math.max(1, total)), Math.min(MIN_THUMB, height / 2), max);
    }

    private int thumbY() {
        int max = maxOffset();
        return max == 0 ? y : y + (height - thumbHeight()) * offset / max;
    }
}
