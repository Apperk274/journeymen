package io.github.apperk274.journeymen.client;

import io.github.apperk274.journeymen.build.BuildOptions;
import io.github.apperk274.journeymen.entity.JourneymanNames;
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
    private static final int TEXT = 0x404040;
    private static final int ROW = 11;
    private static final int LIST_TOP = 20;
    private static final int LIST_BOTTOM = 150;
    private static final int FILES_RIGHT = 120;
    private static final int MATERIALS_LEFT = 126;
    private static final int RIGHT = 248;
    private static final int ROWS = (LIST_BOTTOM - LIST_TOP) / ROW;

    private int tab;
    private boolean builtWithJob;
    @Nullable
    private String selected;
    private final ScrollBar fileBar = new ScrollBar();
    private final ScrollBar materialBar = new ScrollBar();
    private final ScrollBar chestBar = new ScrollBar();
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
        this.tab = menu.getActiveTab();
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
        fileBar.place(leftPos + FILES_RIGHT - ScrollBar.WIDTH, topPos + LIST_TOP, LIST_BOTTOM - LIST_TOP);
        materialBar.place(leftPos + RIGHT - ScrollBar.WIDTH, topPos + LIST_TOP, LIST_BOTTOM - LIST_TOP);
        chestBar.place(leftPos + RIGHT - ScrollBar.WIDTH, topPos + LIST_TOP, LIST_BOTTOM - LIST_TOP);
        ScreenPayloads.BuilderState state = state();
        builtWithJob = state != null && state.hasJob();
        switch (tab) {
            case BuilderMenu.JOB_TAB -> initJobTab();
            case BuilderMenu.CHESTS_TAB -> initChestsTab();
            case BuilderMenu.ORDERS_TAB -> initOrdersTab();
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
        if (index == BuilderMenu.JOB_TAB) {
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
        renameBox.setMaxLength(JourneymanNames.MAX_LENGTH);
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
        PlacementMode.start(menu.getBuilderId(), info, (corner ? BuildOptions.CORNER + " " : "")
                + (allowBreakingEnchanted ? BuildOptions.ALLOW_BREAKING_ENCHANTED : ""));
        onClose();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        ScreenPayloads.BuilderState state = state();
        if (tab == BuilderMenu.JOB_TAB && state != null && state.hasJob() != builtWithJob) {
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

    private List<ScrollBar> activeBars() {
        if (tab == BuilderMenu.JOB_TAB && !builtWithJob) {
            return List.of(fileBar, materialBar);
        }
        return tab == BuilderMenu.CHESTS_TAB ? List.of(chestBar) : List.of();
    }

    private void updateBars() {
        ScreenPayloads.BuilderState state = state();
        fileBar.setContent(ClientState.schematics.size(), ROWS);
        materialBar.setContent(materialLines().size(), ROWS);
        chestBar.setContent(state == null ? 0 : state.chests().size(), ROWS);
    }

    private int filesRight() {
        return fileBar.isVisible() ? FILES_RIGHT - ScrollBar.WIDTH - 1 : FILES_RIGHT;
    }

    private int removeRight() {
        return chestBar.isVisible() ? RIGHT - ScrollBar.WIDTH - 2 : RIGHT;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        updateBars();
        for (ScrollBar bar : activeBars()) {
            if (bar.mouseClicked(mouseX, mouseY, button)) {
                return true;
            }
        }
        double x = mouseX - leftPos;
        double y = mouseY - topPos;
        if (button == 0 && tab == BuilderMenu.JOB_TAB && !builtWithJob && x >= 8 && x < FILES_RIGHT && y >= LIST_TOP && y < LIST_BOTTOM) {
            int index = fileBar.offset() + (int) ((y - LIST_TOP) / ROW);
            List<String> files = ClientState.schematics;
            if (index < files.size()) {
                selected = files.get(index);
                materialBar.setOffset(0);
                if (ClientState.info == null || !ClientState.info.file().equals(selected)) {
                    ClientState.info = null;
                    send("schematic_info", selected, BlockPos.ZERO);
                }
            }
            return true;
        }
        if (button == 0 && tab == BuilderMenu.CHESTS_TAB && x >= removeRight() - 12 && x < removeRight() && y >= LIST_TOP && y < LIST_BOTTOM) {
            ScreenPayloads.BuilderState state = state();
            int index = chestBar.offset() + (int) ((y - LIST_TOP) / ROW);
            if (state != null && index < state.chests().size()) {
                send("remove_chest", "", state.chests().get(index).pos());
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        for (ScrollBar bar : activeBars()) {
            if (bar.mouseDragged(mouseY, button)) {
                return true;
            }
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean released = false;
        for (ScrollBar bar : List.of(fileBar, materialBar, chestBar)) {
            released |= bar.mouseReleased(button);
        }
        return released || super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double x = mouseX - leftPos;
        updateBars();
        if (tab == BuilderMenu.JOB_TAB && !builtWithJob) {
            (x < FILES_RIGHT ? fileBar : materialBar).scroll(scrollY);
            return true;
        }
        if (tab == BuilderMenu.CHESTS_TAB) {
            chestBar.scroll(scrollY);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private List<FormattedCharSequence> materialLines() {
        List<FormattedCharSequence> lines = materialLines(RIGHT - MATERIALS_LEFT);
        return lines.size() > ROWS ? materialLines(RIGHT - MATERIALS_LEFT - ScrollBar.WIDTH - 2) : lines;
    }

    private List<FormattedCharSequence> materialLines(int width) {
        ScreenPayloads.SchematicInfo info = ClientState.info;
        List<FormattedCharSequence> lines = new ArrayList<>();
        if (selected == null) {
            lines.addAll(font.split(Component.literal("Pick a schematic on the left."), width));
        } else if (info == null || !info.file().equals(selected)) {
            lines.addAll(font.split(Component.literal("Loading…"), width));
        } else {
            for (Component line : info.materials()) {
                lines.addAll(font.split(line, width));
            }
            if (!info.complete()) {
                lines.addAll(font.split(Component.literal("Too big to preview; only its outline is shown.").withStyle(ChatFormatting.GRAY), width));
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
        } else if (tab == BuilderMenu.JOB_TAB && !builtWithJob || tab == BuilderMenu.CHESTS_TAB) {
            inset(graphics, x + 7, y + LIST_TOP - 1, x + (tab == BuilderMenu.JOB_TAB ? FILES_RIGHT : RIGHT) + 1, y + LIST_BOTTOM + 1);
            if (tab == BuilderMenu.JOB_TAB) {
                inset(graphics, x + MATERIALS_LEFT - 1, y + LIST_TOP - 1, x + RIGHT + 1, y + LIST_BOTTOM + 1);
            }
            updateBars();
            activeBars().forEach(bar -> bar.render(graphics));
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
            case BuilderMenu.JOB_TAB -> renderJob(graphics, state, mouseX - leftPos, mouseY - topPos);
            case BuilderMenu.CHESTS_TAB -> renderChests(graphics, state, mouseX - leftPos, mouseY - topPos);
            case BuilderMenu.ORDERS_TAB -> renderOrders(graphics, state);
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
        int fileScroll = fileBar.offset();
        int right = filesRight();
        if (files.isEmpty()) {
            int y = LIST_TOP + 2;
            for (FormattedCharSequence part : font.split(Component.literal("No schematics in the server's schematics folder."), FILES_RIGHT - 12)) {
                graphics.drawString(font, part, 10, y, 0xFFFFFF, false);
                y += 10;
            }
        }
        for (int i = 0; i < ROWS && fileScroll + i < files.size(); i++) {
            String file = files.get(fileScroll + i);
            int y = LIST_TOP + i * ROW;
            boolean hovered = mouseX >= 8 && mouseX < right && mouseY >= y && mouseY < y + ROW;
            if (file.equals(selected)) {
                graphics.fill(8, y, right, y + ROW, 0xFF5A7FBF);
            } else if (hovered) {
                graphics.fill(8, y, right, y + ROW, 0xFF6F6F6F);
            }
            graphics.drawString(font, font.plainSubstrByWidth(file, right - 12), 10, y + 2, 0xFFFFFF, false);
        }
        List<FormattedCharSequence> lines = materialLines();
        int materialScroll = materialBar.offset();
        for (int i = 0; i < ROWS && materialScroll + i < lines.size(); i++) {
            graphics.drawString(font, lines.get(materialScroll + i), MATERIALS_LEFT + 1, LIST_TOP + 2 + i * ROW, 0xFFFFFF, false);
        }
    }

    private void renderChests(GuiGraphics graphics, ScreenPayloads.BuilderState state, int mouseX, int mouseY) {
        List<ScreenPayloads.ChestEntry> chests = state.chests();
        int chestScroll = chestBar.offset();
        int right = removeRight();
        if (chests.isEmpty()) {
            graphics.drawString(font, "No chests yet.", 10, LIST_TOP + 2, 0xFFFFFF, false);
        }
        for (int i = 0; i < ROWS && chestScroll + i < chests.size(); i++) {
            ScreenPayloads.ChestEntry chest = chests.get(chestScroll + i);
            int y = LIST_TOP + i * ROW;
            BlockPos pos = chest.pos();
            Component line = Component.literal(chest.supply() ? "Supply " : "Dump ")
                    .withStyle(chest.supply() ? ChatFormatting.GREEN : ChatFormatting.GOLD)
                    .append(Component.literal(pos.getX() + " " + pos.getY() + " " + pos.getZ()).withStyle(ChatFormatting.WHITE));
            graphics.drawString(font, line, 10, y + 2, 0xFFFFFF, false);
            boolean hovered = mouseX >= right - 12 && mouseX < right && mouseY >= y && mouseY < y + ROW;
            graphics.drawString(font, "✕", right - 9, y + 2, hovered ? 0xFFFF5555 : 0xFFFFFFFF, false);
        }
        int y = LIST_BOTTOM + 5;
        for (FormattedCharSequence part : font.split(Component.literal("Link mode: right-click chests to add or remove them, then right-click the builder to finish."),
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
