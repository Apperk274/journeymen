package io.github.apperk274.journeymen.client;

import io.github.apperk274.journeymen.entity.BuilderNames;
import io.github.apperk274.journeymen.menu.BuilderMenu;
import io.github.apperk274.journeymen.network.ScreenPayloads;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
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

    private static final String[] TABS = {"Inventory", "Job", "Chests", "Orders"};
    private static final int JOB_TAB = 1;
    private static final int CHESTS_TAB = 2;
    private static final int ORDERS_TAB = 3;
    private static final int TEXT = 0x404040;
    private static final int ROW = 11;
    private static final int LIST_TOP = 20;
    private static final int LIST_BOTTOM = 150;
    private static final int FILES_RIGHT = 120;
    private static final int MATERIALS_LEFT = 126;
    private static final int RIGHT = 248;

    private int tab = BuilderMenu.INVENTORY_TAB;
    private boolean builtWithJob;
    @Nullable
    private String selected;
    private int fileScroll;
    private int materialScroll;
    private int chestScroll;
    private boolean corner;
    private boolean allowBreakingEnchanted;
    private String renameText = "";
    private boolean dismissArmed;
    private boolean cancelArmed;
    @Nullable
    private Button followButton;
    @Nullable
    private Button stayButton;
    @Nullable
    private Button placeButton;
    @Nullable
    private Button dismissButton;
    @Nullable
    private Button cancelButton;
    @Nullable
    private EditBox renameBox;

    public BuilderScreen(BuilderMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 256;
        this.imageHeight = 204;
        this.inventoryLabelY = 112;
    }

    @Nullable
    private ScreenPayloads.BuilderState state() {
        return ClientState.STATES.get(menu.getBuilderId());
    }

    private void send(String action, String text, BlockPos pos) {
        Services.NETWORK.sendToServer(new ScreenPayloads.BuilderAction(menu.getBuilderId(), action, text, pos));
    }

    private void send(String action) {
        send(action, "", BlockPos.ZERO);
    }

    @Override
    protected void init() {
        super.init();
        followButton = null;
        stayButton = null;
        placeButton = null;
        dismissButton = null;
        cancelButton = null;
        renameBox = null;
        for (int i = 0; i < TABS.length; i++) {
            int index = i;
            Button button = addRenderableWidget(Button.builder(Component.literal(TABS[i]), b -> switchTab(index))
                    .bounds(leftPos + i * 64, topPos - 21, 62, 20).build());
            button.active = i != tab;
        }
        ScreenPayloads.BuilderState state = state();
        builtWithJob = state != null && state.hasJob();
        switch (tab) {
            case JOB_TAB -> initJobTab();
            case CHESTS_TAB -> initChestsTab();
            case ORDERS_TAB -> initOrdersTab();
            default -> {
            }
        }
        updateButtons();
    }

    private void switchTab(int index) {
        tab = index;
        dismissArmed = false;
        cancelArmed = false;
        menu.setActiveTab(index);
        if (index == JOB_TAB) {
            send("schematic_list");
        }
        rebuildWidgets();
    }

    private void initJobTab() {
        if (builtWithJob) {
            cancelButton = addRenderableWidget(Button.builder(Component.literal("Cancel job"), b -> {
                if (cancelArmed) {
                    send("cancel_job");
                    cancelArmed = false;
                } else {
                    cancelArmed = true;
                }
            }).bounds(leftPos + 8, topPos + 178, 118, 20).build());
            return;
        }
        addRenderableWidget(Checkbox.builder(Component.literal("Corner"), font).pos(leftPos + 8, topPos + 155).selected(corner)
                .onValueChange((box, value) -> corner = value).build());
        addRenderableWidget(Checkbox.builder(Component.literal("Allow breaking enchanted"), font).pos(leftPos + 70, topPos + 155)
                .selected(allowBreakingEnchanted).onValueChange((box, value) -> allowBreakingEnchanted = value).build());
        placeButton = addRenderableWidget(Button.builder(Component.literal("Place…"), b -> place())
                .bounds(leftPos + 8, topPos + 178, 118, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Refresh"), b -> send("schematic_list"))
                .bounds(leftPos + 130, topPos + 178, 118, 20).build());
    }

    private void initChestsTab() {
        addRenderableWidget(Button.builder(Component.literal("Link supply chests"), b -> send("link_supply"))
                .bounds(leftPos + 8, topPos + 178, 118, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Link dump chests"), b -> send("link_dump"))
                .bounds(leftPos + 130, topPos + 178, 118, 20).build());
    }

    private void initOrdersTab() {
        followButton = addRenderableWidget(Button.builder(Component.literal("Follow me"), b -> send("follow"))
                .bounds(leftPos + 8, topPos + 22, 118, 20).build());
        stayButton = addRenderableWidget(Button.builder(Component.literal("Stay here"), b -> send("stay"))
                .bounds(leftPos + 130, topPos + 22, 118, 20).build());
        ScreenPayloads.BuilderState state = state();
        if (renameText.isEmpty() && state != null) {
            renameText = state.name();
        }
        renameBox = new EditBox(font, leftPos + 8, topPos + 62, 180, 20, Component.literal("Name"));
        renameBox.setMaxLength(BuilderNames.MAX_LENGTH);
        renameBox.setFilter(text -> text.chars().allMatch(c -> Character.isLetterOrDigit(c) && c < 128 || c == '_' || c == '-'));
        renameBox.setValue(renameText);
        renameBox.setResponder(text -> renameText = text);
        addRenderableWidget(renameBox);
        addRenderableWidget(Button.builder(Component.literal("Rename"), b -> send("rename", renameText, BlockPos.ZERO))
                .bounds(leftPos + 192, topPos + 62, 56, 20).build());
        Button notices = addRenderableWidget(Button.builder(Component.literal("Read notices"), b -> send("notices"))
                .bounds(leftPos + 8, topPos + 94, 118, 20).build());
        notices.active = state != null && minecraft.player != null && state.owner().equals(minecraft.player.getGameProfile().getName());
        dismissButton = addRenderableWidget(Button.builder(Component.literal("Dismiss"), b -> {
            if (dismissArmed) {
                send("dismiss");
            } else {
                dismissArmed = true;
            }
        }).bounds(leftPos + 8, topPos + 178, 118, 20).build());
    }

    private void place() {
        ScreenPayloads.SchematicInfo info = ClientState.info;
        if (info == null || !info.file().equals(selected)) {
            return;
        }
        PlacementMode.start(menu.getBuilderId(), info, corner, allowBreakingEnchanted);
        onClose();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        ScreenPayloads.BuilderState state = state();
        if (tab == JOB_TAB && state != null && state.hasJob() != builtWithJob) {
            rebuildWidgets();
            return;
        }
        updateButtons();
    }

    private void updateButtons() {
        ScreenPayloads.BuilderState state = state();
        if (followButton != null && stayButton != null) {
            followButton.active = state != null && !state.hasJob() && !state.following();
            stayButton.active = state != null && state.following();
        }
        if (placeButton != null) {
            ScreenPayloads.SchematicInfo info = ClientState.info;
            placeButton.active = info != null && info.file().equals(selected);
        }
        if (dismissButton != null) {
            dismissButton.setMessage(dismissArmed
                    ? Component.literal("Really dismiss?").withStyle(ChatFormatting.RED)
                    : Component.literal("Dismiss"));
        }
        if (cancelButton != null) {
            cancelButton.setMessage(cancelArmed
                    ? Component.literal("Really cancel?").withStyle(ChatFormatting.RED)
                    : Component.literal("Cancel job"));
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
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double x = mouseX - leftPos;
        double y = mouseY - topPos;
        if (button == 0 && tab == JOB_TAB && !builtWithJob && x >= 8 && x < FILES_RIGHT && y >= LIST_TOP && y < LIST_BOTTOM) {
            int index = fileScroll + (int) ((y - LIST_TOP) / ROW);
            List<String> files = ClientState.schematics;
            if (index < files.size()) {
                selected = files.get(index);
                materialScroll = 0;
                if (ClientState.info == null || !ClientState.info.file().equals(selected)) {
                    ClientState.info = null;
                    send("schematic_info", selected, BlockPos.ZERO);
                }
            }
            return true;
        }
        if (button == 0 && tab == CHESTS_TAB && x >= RIGHT - 12 && x < RIGHT && y >= LIST_TOP && y < LIST_BOTTOM) {
            ScreenPayloads.BuilderState state = state();
            int index = chestScroll + (int) ((y - LIST_TOP) / ROW);
            if (state != null && index < state.chests().size()) {
                send("remove_chest", "", state.chests().get(index).pos());
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double x = mouseX - leftPos;
        int step = scrollY > 0 ? -1 : scrollY < 0 ? 1 : 0;
        int rows = (LIST_BOTTOM - LIST_TOP) / ROW;
        if (tab == JOB_TAB && !builtWithJob) {
            if (x < FILES_RIGHT) {
                fileScroll = clamp(fileScroll + step, ClientState.schematics.size() - rows);
            } else {
                materialScroll = clamp(materialScroll + step, materialLines().size() - rows);
            }
            return true;
        }
        if (tab == CHESTS_TAB) {
            ScreenPayloads.BuilderState state = state();
            chestScroll = clamp(chestScroll + step, state == null ? 0 : state.chests().size() - rows);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(Math.max(0, max), value));
    }

    private List<FormattedCharSequence> materialLines() {
        ScreenPayloads.SchematicInfo info = ClientState.info;
        List<FormattedCharSequence> lines = new ArrayList<>();
        if (selected == null) {
            lines.addAll(font.split(Component.literal("Pick a schematic on the left."), RIGHT - MATERIALS_LEFT));
        } else if (info == null || !info.file().equals(selected)) {
            lines.addAll(font.split(Component.literal("Loading…"), RIGHT - MATERIALS_LEFT));
        } else {
            for (Component line : info.materials()) {
                lines.addAll(font.split(line, RIGHT - MATERIALS_LEFT));
            }
            if (!info.complete()) {
                lines.addAll(font.split(Component.literal("Too big to preview; only its outline is shown.").withStyle(ChatFormatting.GRAY),
                        RIGHT - MATERIALS_LEFT));
            }
        }
        return lines;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        graphics.fill(x, y, x + imageWidth, y + imageHeight, 0xFF000000);
        graphics.fill(x + 1, y + 1, x + imageWidth - 1, y + imageHeight - 1, 0xFFC6C6C6);
        graphics.fill(x + 1, y + 1, x + imageWidth - 2, y + 2, 0xFFFFFFFF);
        graphics.fill(x + 1, y + 1, x + 2, y + imageHeight - 2, 0xFFFFFFFF);
        graphics.fill(x + 2, y + imageHeight - 2, x + imageWidth - 1, y + imageHeight - 1, 0xFF555555);
        graphics.fill(x + imageWidth - 2, y + 2, x + imageWidth - 1, y + imageHeight - 1, 0xFF555555);
        if (tab == BuilderMenu.INVENTORY_TAB) {
            for (Slot slot : menu.slots) {
                slotBackground(graphics, x + slot.x - 1, y + slot.y - 1);
            }
        } else if (tab == JOB_TAB && !builtWithJob || tab == CHESTS_TAB) {
            inset(graphics, x + 7, y + LIST_TOP - 1, x + (tab == JOB_TAB ? FILES_RIGHT : RIGHT) + 1, y + LIST_BOTTOM + 1);
            if (tab == JOB_TAB) {
                inset(graphics, x + MATERIALS_LEFT - 1, y + LIST_TOP - 1, x + RIGHT + 1, y + LIST_BOTTOM + 1);
            }
        }
    }

    private static void slotBackground(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 18, y + 18, 0xFF373737);
        graphics.fill(x + 1, y + 1, x + 18, y + 18, 0xFFFFFFFF);
        graphics.fill(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
    }

    private static void inset(GuiGraphics graphics, int x1, int y1, int x2, int y2) {
        graphics.fill(x1, y1, x2, y2, 0xFF373737);
        graphics.fill(x1 + 1, y1 + 1, x2, y2, 0xFFFFFFFF);
        graphics.fill(x1 + 1, y1 + 1, x2 - 1, y2 - 1, 0xFF8B8B8B);
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
        switch (tab) {
            case JOB_TAB -> renderJob(graphics, state, mouseX - leftPos, mouseY - topPos);
            case CHESTS_TAB -> renderChests(graphics, state, mouseX - leftPos, mouseY - topPos);
            case ORDERS_TAB -> renderOrders(graphics, state);
            default -> renderInventory(graphics, state);
        }
    }

    private void renderInventory(GuiGraphics graphics, ScreenPayloads.BuilderState state) {
        graphics.drawString(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, TEXT, false);
        graphics.drawString(font, "Armor", 84, 25, TEXT, false);
        int y = 22;
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Health " + Math.round(state.health()) + "/" + Math.round(state.maxHealth())));
        lines.add(Component.literal(state.hasJob() ? state.jobFile() : "No job"));
        lines.add(Component.literal(state.phase()));
        if (state.total() > 0) {
            lines.add(Component.literal(percent(state) + "% done"));
        }
        for (Component line : lines) {
            for (FormattedCharSequence part : font.split(line, RIGHT - 176)) {
                graphics.drawString(font, part, 176, y, TEXT, false);
                y += 10;
            }
        }
    }

    private static int percent(ScreenPayloads.BuilderState state) {
        return state.total() <= 0 ? 0 : (int) (100L * state.done() / state.total());
    }

    private void renderJob(GuiGraphics graphics, ScreenPayloads.BuilderState state, int mouseX, int mouseY) {
        if (builtWithJob) {
            int y = 22;
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal("Working on " + state.jobFile()));
            lines.add(Component.literal(state.phase()));
            if (state.total() > 0) {
                lines.add(Component.literal(percent(state) + "% done"));
            }
            if (!state.detail().isEmpty()) {
                lines.add(Component.literal("Waiting: " + state.detail()).withStyle(ChatFormatting.DARK_RED));
            }
            lines.add(Component.literal("Cancelling leaves the build as it is; starting the same job again continues it.")
                    .withStyle(ChatFormatting.DARK_GRAY));
            for (Component line : lines) {
                for (FormattedCharSequence part : font.split(line, RIGHT - 8)) {
                    graphics.drawString(font, part, 8, y, TEXT, false);
                    y += 10;
                }
                y += 2;
            }
            return;
        }
        List<String> files = ClientState.schematics;
        int rows = (LIST_BOTTOM - LIST_TOP) / ROW;
        if (files.isEmpty()) {
            int y = LIST_TOP + 2;
            for (FormattedCharSequence part : font.split(Component.literal("No schematics in the server's schematics folder."), FILES_RIGHT - 12)) {
                graphics.drawString(font, part, 10, y, 0xFFFFFF, false);
                y += 10;
            }
        }
        for (int i = 0; i < rows && fileScroll + i < files.size(); i++) {
            String file = files.get(fileScroll + i);
            int y = LIST_TOP + i * ROW;
            boolean hovered = mouseX >= 8 && mouseX < FILES_RIGHT && mouseY >= y && mouseY < y + ROW;
            if (file.equals(selected)) {
                graphics.fill(8, y, FILES_RIGHT, y + ROW, 0xFF5A7FBF);
            } else if (hovered) {
                graphics.fill(8, y, FILES_RIGHT, y + ROW, 0xFF6F6F6F);
            }
            graphics.drawString(font, font.plainSubstrByWidth(file, FILES_RIGHT - 12), 10, y + 2, 0xFFFFFF, false);
        }
        List<FormattedCharSequence> lines = materialLines();
        for (int i = 0; i < rows && materialScroll + i < lines.size(); i++) {
            graphics.drawString(font, lines.get(materialScroll + i), MATERIALS_LEFT + 1, LIST_TOP + 2 + i * ROW, 0xFFFFFF, false);
        }
    }

    private void renderChests(GuiGraphics graphics, ScreenPayloads.BuilderState state, int mouseX, int mouseY) {
        List<ScreenPayloads.ChestEntry> chests = state.chests();
        int rows = (LIST_BOTTOM - LIST_TOP) / ROW;
        if (chests.isEmpty()) {
            graphics.drawString(font, "No chests yet.", 10, LIST_TOP + 2, 0xFFFFFF, false);
        }
        for (int i = 0; i < rows && chestScroll + i < chests.size(); i++) {
            ScreenPayloads.ChestEntry chest = chests.get(chestScroll + i);
            int y = LIST_TOP + i * ROW;
            BlockPos pos = chest.pos();
            Component line = Component.literal(chest.supply() ? "Supply " : "Dump ")
                    .withStyle(chest.supply() ? ChatFormatting.GREEN : ChatFormatting.GOLD)
                    .append(Component.literal(pos.getX() + " " + pos.getY() + " " + pos.getZ()).withStyle(ChatFormatting.WHITE));
            graphics.drawString(font, line, 10, y + 2, 0xFFFFFF, false);
            boolean hovered = mouseX >= RIGHT - 12 && mouseX < RIGHT && mouseY >= y && mouseY < y + ROW;
            graphics.drawString(font, "✕", RIGHT - 9, y + 2, hovered ? 0xFFFF5555 : 0xFFFFFFFF, false);
        }
        int y = LIST_BOTTOM + 5;
        for (FormattedCharSequence part : font.split(Component.literal("Link mode: right-click chests to add or remove them, sneak + right-click to finish."),
                RIGHT - 8)) {
            graphics.drawString(font, part, 8, y, TEXT, false);
            y += 10;
        }
    }

    private void renderOrders(GuiGraphics graphics, ScreenPayloads.BuilderState state) {
        graphics.drawString(font, "Name", 8, 52, TEXT, false);
        graphics.drawString(font, "Owner: " + state.owner(), 8, 124, TEXT, false);
        if (state.hasJob()) {
            graphics.drawString(font, "Busy with a job, so it can't follow you.", 8, 136, TEXT, false);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
