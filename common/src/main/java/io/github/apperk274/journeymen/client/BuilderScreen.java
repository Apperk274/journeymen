package io.github.apperk274.journeymen.client;

import io.github.apperk274.journeymen.menu.BuilderMenu;
import io.github.apperk274.journeymen.network.ScreenPayloads;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class BuilderScreen extends AbstractContainerScreen<BuilderMenu> {

    private static final String[] TABS = {"Inventory", "Orders"};
    private static final int TEXT = ScreenParts.TEXT;
    private static final int RIGHT = 248;

    private int tab;
    private String renameText = "";
    private boolean dismissArmed;
    @Nullable
    private Button followButton;
    @Nullable
    private Button stayButton;
    @Nullable
    private Button dismissButton;
    @Nullable
    private EditBox renameBox;

    public BuilderScreen(BuilderMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 256;
        this.imageHeight = 204;
        this.inventoryLabelY = 112;
        this.tab = menu.getActiveTab();
    }

    public int panelLeft() {
        return leftPos;
    }

    public int panelTop() {
        return topPos;
    }

    @Nullable
    private ScreenPayloads.BuilderState state() {
        return ClientState.BUILDERS.get(menu.getBuilderId());
    }

    private void send(String action, String text) {
        Services.NETWORK.sendToServer(new ScreenPayloads.Action(menu.getBuilderId(), action, text, BlockPos.ZERO));
    }

    @Override
    protected void init() {
        super.init();
        topPos = ScreenParts.top(height, imageHeight);
        followButton = null;
        stayButton = null;
        dismissButton = null;
        renameBox = null;
        for (int i = 0; i < TABS.length; i++) {
            int index = i;
            Button button = addRenderableWidget(Button.builder(Component.literal(TABS[i]), b -> switchTab(index))
                    .bounds(leftPos + i * 64, topPos - ScreenParts.TAB_HEIGHT, 62, 20).build());
            button.active = i != tab;
        }
        if (tab == BuilderMenu.ORDERS_TAB) {
            initOrdersTab();
        }
        updateButtons();
    }

    private void switchTab(int index) {
        tab = index;
        dismissArmed = false;
        menu.setActiveTab(index);
        rebuildWidgets();
    }

    private void initOrdersTab() {
        followButton = addRenderableWidget(Button.builder(Component.literal("Follow me"), b -> send("follow", ""))
                .bounds(leftPos + 8, topPos + 22, 118, 20).build());
        stayButton = addRenderableWidget(Button.builder(Component.literal("Stay here"), b -> send("stay", ""))
                .bounds(leftPos + 130, topPos + 22, 118, 20).build());
        ScreenPayloads.BuilderState state = state();
        if (renameText.isEmpty() && state != null) {
            renameText = state.name();
        }
        renameBox = addRenderableWidget(ScreenParts.nameBox(font, leftPos + 8, topPos + 62, 180, renameText, text -> renameText = text));
        addRenderableWidget(Button.builder(Component.literal("Rename"), b -> send("rename", renameText))
                .bounds(leftPos + 192, topPos + 62, 56, 20).build());
        dismissButton = addRenderableWidget(Button.builder(Component.literal("Dismiss"), b -> {
            if (dismissArmed) {
                send("dismiss", "");
            } else {
                dismissArmed = true;
            }
        }).bounds(leftPos + 8, topPos + 178, 118, 20).build());
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateButtons();
    }

    private void updateButtons() {
        ScreenPayloads.BuilderState state = state();
        if (followButton != null && stayButton != null) {
            followButton.active = state != null && !state.working() && !state.following();
            stayButton.active = state != null && !state.working() && state.following();
        }
        if (dismissButton != null) {
            dismissButton.setMessage(dismissArmed
                    ? Component.literal("Really dismiss?").withStyle(ChatFormatting.RED)
                    : Component.literal("Dismiss"));
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (renameBox != null && renameBox.isFocused() && keyCode != 256) {
            return renameBox.keyPressed(keyCode, scanCode, modifiers) || renameBox.canConsumeInput();
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        ScreenParts.panel(graphics, leftPos, topPos, imageWidth, imageHeight);
        if (tab == BuilderMenu.INVENTORY_TAB) {
            for (Slot slot : menu.slots) {
                ScreenParts.slot(graphics, leftPos + slot.x - 1, topPos + slot.y - 1);
            }
        }
    }

    private static String crewOf(ScreenPayloads.BuilderState state) {
        return state.foreman().isEmpty() ? "orphan" : state.foreman() + "'s crew";
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        ScreenPayloads.BuilderState state = state();
        Component name = state == null ? title : Component.literal(state.name());
        graphics.drawString(font, name, 8, 6, TEXT, false);
        if (state == null) {
            graphics.drawString(font, "Loading…", 8, 22, TEXT, false);
            return;
        }
        String crew = crewOf(state);
        graphics.drawString(font, crew, RIGHT - font.width(crew), 6, state.foreman().isEmpty() ? 0xAA0000 : 0x555555, false);
        if (tab == BuilderMenu.ORDERS_TAB) {
            renderOrders(graphics, state);
        } else {
            renderInventory(graphics, state);
        }
    }

    private void renderInventory(GuiGraphics graphics, ScreenPayloads.BuilderState state) {
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, TEXT, false);
        graphics.drawString(font, "Armor", 84, 25, TEXT, false);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Health " + Math.round(state.health()) + "/" + Math.round(state.maxHealth())));
        lines.add(Component.literal(state.task()));
        int y = 22;
        for (Component line : lines) {
            for (FormattedCharSequence part : font.split(line, RIGHT - 176)) {
                graphics.drawString(font, part, 176, y, TEXT, false);
                y += 10;
            }
        }
    }

    private void renderOrders(GuiGraphics graphics, ScreenPayloads.BuilderState state) {
        graphics.drawString(font, "Name", 8, 52, TEXT, false);
        int y = 94;
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Owner: " + state.owner()));
        lines.add(Component.literal(state.foreman().isEmpty()
                ? "Foreman: none. Another foreman of yours can adopt it from his Crew tab."
                : "Foreman: " + state.foreman()));
        lines.add(Component.literal("Doing: " + state.task()));
        if (state.working()) {
            lines.add(Component.literal("Busy with its foreman's job, so it can't follow you.").withStyle(ChatFormatting.DARK_GRAY));
        }
        for (Component line : lines) {
            for (FormattedCharSequence part : font.split(line, RIGHT - 8)) {
                graphics.drawString(font, part, 8, y, TEXT, false);
                y += 10;
            }
            y += 2;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
