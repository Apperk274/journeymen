package io.github.apperk274.journeymen.client;

import io.github.apperk274.journeymen.build.BuildOptions;
import io.github.apperk274.journeymen.menu.ForemanMenu;
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
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class ForemanScreen extends AbstractContainerScreen<ForemanMenu> {

    private static final int TEXT = ScreenParts.TEXT;
    private static final int ROW = 11;
    private static final int LIST_TOP = 20;
    private static final int LIST_BOTTOM = 150;
    private static final int CREW_BOTTOM = 139;
    private static final int FILES_RIGHT = 120;
    private static final int MATERIALS_LEFT = 126;
    private static final int RIGHT = 248;
    private static final int ROWS = (LIST_BOTTOM - LIST_TOP) / ROW;
    private static final int CREW_ROWS = (CREW_BOTTOM - LIST_TOP) / ROW;
    private static final int ADOPT_WIDTH = 40;

    private record CrewRow(Component text, @Nullable String adopt) {
    }

    private int tab;
    private boolean builtWithJob;
    @Nullable
    private String selected;
    private final ScrollBar fileBar = new ScrollBar();
    private final ScrollBar materialBar = new ScrollBar();
    private final ScrollBar chestBar = new ScrollBar();
    private final ScrollBar crewBar = new ScrollBar();
    private boolean corner;
    private boolean allowBreakingEnchanted;
    private String renameText = "";
    private String hireName = "";
    private String shownSuggestion = "";
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
    private Button hireButton;
    @Nullable
    private EditBox renameBox;
    @Nullable
    private EditBox hireBox;
    @Nullable
    private Checkbox cornerBox;
    @Nullable
    private Checkbox enchantedBox;

    public ForemanScreen(ForemanMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        this.imageWidth = 256;
        this.imageHeight = 204;
        this.tab = menu.getActiveTab();
    }

    public int panelLeft() {
        return leftPos;
    }

    public int panelTop() {
        return topPos;
    }

    @Nullable
    private ScreenPayloads.ForemanState state() {
        return ClientState.FOREMEN.get(menu.getForemanId());
    }

    private void send(String action, String text, BlockPos pos) {
        Services.NETWORK.sendToServer(new ScreenPayloads.Action(menu.getForemanId(), action, text, pos));
    }

    private void send(String action) {
        send(action, "", BlockPos.ZERO);
    }

    @Override
    protected void init() {
        super.init();
        topPos = ScreenParts.top(height, imageHeight);
        followButton = null;
        stayButton = null;
        placeButton = null;
        dismissButton = null;
        cancelButton = null;
        hireButton = null;
        renameBox = null;
        hireBox = null;
        cornerBox = null;
        enchantedBox = null;
        for (int i = 0; i < ForemanMenu.TAB_COUNT; i++) {
            int index = i;
            Button button = addRenderableWidget(Button.builder(Component.literal(ForemanMenu.TAB_NAMES[i]), b -> switchTab(index))
                    .bounds(leftPos + i * 64, topPos - ScreenParts.TAB_HEIGHT, 62, 20).build());
            button.active = i != tab;
        }
        fileBar.place(leftPos + FILES_RIGHT - ScrollBar.WIDTH, topPos + LIST_TOP, LIST_BOTTOM - LIST_TOP);
        materialBar.place(leftPos + RIGHT - ScrollBar.WIDTH, topPos + LIST_TOP, LIST_BOTTOM - LIST_TOP);
        chestBar.place(leftPos + RIGHT - ScrollBar.WIDTH, topPos + LIST_TOP, LIST_BOTTOM - LIST_TOP);
        crewBar.place(leftPos + RIGHT - ScrollBar.WIDTH, topPos + LIST_TOP, CREW_BOTTOM - LIST_TOP);
        ScreenPayloads.ForemanState state = state();
        builtWithJob = state != null && state.working();
        switch (tab) {
            case ForemanMenu.JOB_TAB -> initJobTab();
            case ForemanMenu.CHESTS_TAB -> initChestsTab();
            case ForemanMenu.CREW_TAB -> initCrewTab();
            case ForemanMenu.ORDERS_TAB -> initOrdersTab();
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
        if (index == ForemanMenu.JOB_TAB) {
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
        cornerBox = addRenderableWidget(ScreenParts.checkbox(font, Component.literal("Corner"), leftPos + 8, topPos + 155, corner,
                (box, value) -> corner = value));
        enchantedBox = addRenderableWidget(ScreenParts.checkbox(font, Component.literal("Allow breaking enchanted"), leftPos + 70, topPos + 155,
                allowBreakingEnchanted, (box, value) -> allowBreakingEnchanted = value));
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

    private void initCrewTab() {
        ScreenPayloads.ForemanState state = state();
        if (hireName.isEmpty() && state != null) {
            hireName = state.hiring().suggestedName();
            shownSuggestion = hireName;
        }
        hireBox = addRenderableWidget(ScreenParts.nameBox(font, leftPos + 8, topPos + 178, 118, hireName, text -> hireName = text));
        hireButton = addRenderableWidget(Button.builder(Component.literal("Hire builder"), b -> send("hire", hireName, BlockPos.ZERO))
                .bounds(leftPos + 130, topPos + 178, 118, 20).build());
    }

    private void initOrdersTab() {
        followButton = addRenderableWidget(Button.builder(Component.literal("Follow me"), b -> send("follow"))
                .bounds(leftPos + 8, topPos + 22, 118, 20).build());
        stayButton = addRenderableWidget(Button.builder(Component.literal("Stay here"), b -> send("stay"))
                .bounds(leftPos + 130, topPos + 22, 118, 20).build());
        ScreenPayloads.ForemanState state = state();
        if (renameText.isEmpty() && state != null) {
            renameText = state.name();
        }
        renameBox = addRenderableWidget(ScreenParts.nameBox(font, leftPos + 8, topPos + 62, 180, renameText, text -> renameText = text));
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
        PlacementMode.start(menu.getForemanId(), info, (corner ? BuildOptions.CORNER + " " : "")
                + (allowBreakingEnchanted ? BuildOptions.ALLOW_BREAKING_ENCHANTED : ""));
        onClose();
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        ScreenPayloads.ForemanState state = state();
        if (tab == ForemanMenu.JOB_TAB && state != null && state.working() != builtWithJob) {
            rebuildWidgets();
            return;
        }
        if (state != null && hireBox != null) {
            String suggestion = state.hiring().suggestedName();
            if (!suggestion.equals(shownSuggestion) && (hireName.isEmpty() || hireName.equals(shownSuggestion))) {
                shownSuggestion = suggestion;
                hireBox.setValue(suggestion);
            }
        }
        updateButtons();
    }

    private static boolean crewFull(ScreenPayloads.ForemanState state) {
        return state.hiring().maxCrew() > 0 && state.crew().size() >= state.hiring().maxCrew();
    }

    private void updateButtons() {
        ScreenPayloads.ForemanState state = state();
        if (followButton != null && stayButton != null) {
            followButton.active = state != null && !state.working() && !state.following();
            stayButton.active = state != null && !state.working() && state.following();
        }
        if (placeButton != null) {
            ScreenPayloads.SchematicInfo info = ClientState.info;
            placeButton.active = info != null && info.file().equals(selected);
        }
        if (hireButton != null) {
            hireButton.active = state != null && state.hiring().problem().isEmpty() && state.hiring().affordable() && !hireName.isBlank();
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
        for (EditBox box : new EditBox[]{renameBox, hireBox}) {
            if (box != null && box.isFocused() && keyCode != 256) {
                return box.keyPressed(keyCode, scanCode, modifiers) || box.canConsumeInput();
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private List<ScrollBar> activeBars() {
        return switch (tab) {
            case ForemanMenu.JOB_TAB -> builtWithJob ? List.of() : List.of(fileBar, materialBar);
            case ForemanMenu.CHESTS_TAB -> List.of(chestBar);
            case ForemanMenu.CREW_TAB -> List.of(crewBar);
            default -> List.of();
        };
    }

    private void updateBars() {
        ScreenPayloads.ForemanState state = state();
        fileBar.setContent(ClientState.schematics.size(), ROWS);
        materialBar.setContent(materialLines().size(), ROWS);
        chestBar.setContent(state == null ? 0 : state.chests().size(), ROWS);
        crewBar.setContent(state == null ? 0 : crewRows(state).size(), CREW_ROWS);
    }

    private int filesRight() {
        return fileBar.isVisible() ? FILES_RIGHT - ScrollBar.WIDTH - 1 : FILES_RIGHT;
    }

    private int removeRight() {
        return chestBar.isVisible() ? RIGHT - ScrollBar.WIDTH - 2 : RIGHT;
    }

    private int crewRight() {
        return crewBar.isVisible() ? RIGHT - ScrollBar.WIDTH - 2 : RIGHT;
    }

    private List<CrewRow> crewRows(ScreenPayloads.ForemanState state) {
        List<CrewRow> rows = new ArrayList<>();
        if (state.crew().isEmpty()) {
            rows.add(new CrewRow(Component.literal("No builders yet."), null));
        }
        for (ScreenPayloads.CrewEntry entry : state.crew()) {
            rows.add(new CrewRow(Component.literal(entry.name()).withStyle(ChatFormatting.WHITE)
                    .append(Component.literal(": " + entry.doing()).withStyle(entry.available() ? ChatFormatting.WHITE : ChatFormatting.GOLD)), null));
        }
        if (!state.orphans().isEmpty()) {
            rows.add(new CrewRow(Component.literal("Your builders without a foreman:").withStyle(ChatFormatting.YELLOW), null));
            for (String orphan : state.orphans()) {
                rows.add(new CrewRow(Component.literal(orphan).withStyle(ChatFormatting.WHITE), orphan));
            }
        }
        return rows;
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
        ScreenPayloads.ForemanState state = state();
        if (button == 0 && tab == ForemanMenu.JOB_TAB && !builtWithJob && x >= 8 && x < FILES_RIGHT && y >= LIST_TOP && y < LIST_BOTTOM) {
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
        if (button == 0 && tab == ForemanMenu.CHESTS_TAB && x >= removeRight() - 12 && x < removeRight() && y >= LIST_TOP && y < LIST_BOTTOM) {
            int index = chestBar.offset() + (int) ((y - LIST_TOP) / ROW);
            if (state != null && index < state.chests().size()) {
                ScreenParts.click();
                send("remove_chest", "", state.chests().get(index).pos());
            }
            return true;
        }
        if (button == 0 && tab == ForemanMenu.CREW_TAB && state != null && !crewFull(state)
                && x >= crewRight() - ADOPT_WIDTH && x < crewRight() && y >= LIST_TOP && y < CREW_BOTTOM) {
            int index = crewBar.offset() + (int) ((y - LIST_TOP) / ROW);
            List<CrewRow> rows = crewRows(state);
            if (index < rows.size() && rows.get(index).adopt() != null) {
                ScreenParts.click();
                send("adopt", rows.get(index).adopt(), BlockPos.ZERO);
                return true;
            }
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
        for (ScrollBar bar : List.of(fileBar, materialBar, chestBar, crewBar)) {
            released |= bar.mouseReleased(button);
        }
        return released || super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        double x = mouseX - leftPos;
        updateBars();
        if (tab == ForemanMenu.JOB_TAB && !builtWithJob) {
            (x < FILES_RIGHT ? fileBar : materialBar).scroll(scrollY);
            return true;
        }
        if (tab == ForemanMenu.CHESTS_TAB) {
            chestBar.scroll(scrollY);
            return true;
        }
        if (tab == ForemanMenu.CREW_TAB) {
            crewBar.scroll(scrollY);
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
        ScreenParts.panel(graphics, x, y, imageWidth, imageHeight);
        if (tab == ForemanMenu.JOB_TAB && !builtWithJob) {
            ScreenParts.inset(graphics, x + 7, y + LIST_TOP - 1, x + FILES_RIGHT + 1, y + LIST_BOTTOM + 1);
            ScreenParts.inset(graphics, x + MATERIALS_LEFT - 1, y + LIST_TOP - 1, x + RIGHT + 1, y + LIST_BOTTOM + 1);
        } else if (tab == ForemanMenu.CHESTS_TAB) {
            ScreenParts.inset(graphics, x + 7, y + LIST_TOP - 1, x + RIGHT + 1, y + LIST_BOTTOM + 1);
        } else if (tab == ForemanMenu.CREW_TAB) {
            ScreenParts.inset(graphics, x + 7, y + LIST_TOP - 1, x + RIGHT + 1, y + CREW_BOTTOM + 1);
        }
        updateBars();
        activeBars().forEach(bar -> bar.render(graphics));
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        ScreenPayloads.ForemanState state = state();
        Component name = state == null ? title : Component.literal(state.name());
        graphics.drawString(font, name, 8, 6, TEXT, false);
        if (state == null) {
            graphics.drawString(font, "Loading…", 8, 22, TEXT, false);
            return;
        }
        String crew = "Foreman · crew of " + state.crew().size();
        graphics.drawString(font, crew, RIGHT - font.width(crew), 6, 0x555555, false);
        switch (tab) {
            case ForemanMenu.CHESTS_TAB -> renderChests(graphics, state, mouseX - leftPos, mouseY - topPos);
            case ForemanMenu.CREW_TAB -> renderCrew(graphics, state, mouseX - leftPos, mouseY - topPos);
            case ForemanMenu.ORDERS_TAB -> renderOrders(graphics, state);
            default -> renderJob(graphics, state, mouseX - leftPos, mouseY - topPos);
        }
    }

    private static int percent(ScreenPayloads.ForemanState state) {
        return state.total() <= 0 ? 0 : (int) (100L * state.done() / state.total());
    }

    private void drawLines(GuiGraphics graphics, List<Component> lines, int y) {
        for (Component line : lines) {
            for (FormattedCharSequence part : font.split(line, RIGHT - 8)) {
                graphics.drawString(font, part, 8, y, TEXT, false);
                y += 10;
            }
            y += 2;
        }
    }

    private void renderJob(GuiGraphics graphics, ScreenPayloads.ForemanState state, int mouseX, int mouseY) {
        if (builtWithJob) {
            List<Component> lines = new ArrayList<>();
            lines.add(Component.literal("Working on " + state.jobFile()));
            lines.add(Component.literal(state.jobWhere()).withStyle(ChatFormatting.DARK_GRAY));
            lines.add(Component.literal(state.phase()));
            if (state.total() > 0) {
                lines.add(Component.literal(percent(state) + "% done"));
            }
            if (!state.detail().isEmpty()) {
                lines.add(Component.literal("Waiting: " + state.detail()).withStyle(ChatFormatting.DARK_RED));
            }
            lines.add(Component.literal("Cancelling leaves the build as it is; starting the same job again continues it.")
                    .withStyle(ChatFormatting.DARK_GRAY));
            drawLines(graphics, lines, 22);
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
            graphics.drawString(font, ScreenParts.fit(font, Component.literal(file), right - 12), 10, y + 2, 0xFFFFFF, false);
            if (hovered && font.width(file) > right - 12) {
                setTooltipForNextRenderPass(Component.literal(file));
            }
        }
        List<FormattedCharSequence> lines = materialLines();
        int materialScroll = materialBar.offset();
        for (int i = 0; i < ROWS && materialScroll + i < lines.size(); i++) {
            graphics.drawString(font, lines.get(materialScroll + i), MATERIALS_LEFT + 1, LIST_TOP + 2 + i * ROW, 0xFFFFFF, false);
        }
    }

    private void renderChests(GuiGraphics graphics, ScreenPayloads.ForemanState state, int mouseX, int mouseY) {
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
        for (FormattedCharSequence part : font.split(Component.literal("Link mode: right-click chests to add or remove them, then right-click "
                + state.name() + " to finish."), RIGHT - 8)) {
            graphics.drawString(font, part, 8, y, TEXT, false);
            y += 10;
        }
    }

    private void renderCrew(GuiGraphics graphics, ScreenPayloads.ForemanState state, int mouseX, int mouseY) {
        List<CrewRow> rows = crewRows(state);
        int scroll = crewBar.offset();
        int right = crewRight();
        boolean full = crewFull(state);
        for (int i = 0; i < CREW_ROWS && scroll + i < rows.size(); i++) {
            CrewRow row = rows.get(scroll + i);
            int y = LIST_TOP + i * ROW;
            int textRight = row.adopt() != null ? right - ADOPT_WIDTH - 2 : right;
            graphics.drawString(font, ScreenParts.fit(font, row.text(), textRight - 12), 10, y + 2, 0xFFFFFF, false);
            if (font.width(row.text()) > textRight - 12 && mouseX >= 8 && mouseX < textRight && mouseY >= y && mouseY < y + ROW) {
                setTooltipForNextRenderPass(row.text());
            }
            if (row.adopt() != null) {
                boolean hovered = mouseX >= right - ADOPT_WIDTH && mouseX < right && mouseY >= y && mouseY < y + ROW;
                ScreenParts.smallButton(graphics, font, "Adopt", right - ADOPT_WIDTH, y, ADOPT_WIDTH, ROW, !full, hovered);
            }
        }
        ScreenPayloads.Hiring hiring = state.hiring();
        int y = CREW_BOTTOM + 5;
        ItemStack price = hiring.price();
        if (!price.isEmpty()) {
            graphics.renderItem(price, 8, y);
        }
        MutableComponent priceLine = Component.literal("Hire a builder: ");
        if (!price.isEmpty()) {
            priceLine.append(price.getCount() + " × ").append(price.getHoverName());
        }
        graphics.drawString(font, font.plainSubstrByWidth(priceLine.getString(), RIGHT - 28), 28, y, TEXT, false);
        Component second;
        if (!hiring.problem().isEmpty()) {
            second = Component.literal(hiring.problem()).withStyle(ChatFormatting.DARK_RED);
        } else {
            String crewSize = hiring.maxCrew() > 0 ? state.crew().size() + "/" + hiring.maxCrew() : String.valueOf(state.crew().size());
            String builders = hiring.maxBuilders() > 0 ? hiring.builders() + "/" + hiring.maxBuilders() : String.valueOf(hiring.builders());
            second = (hiring.free() ? Component.literal("Free in creative").withStyle(ChatFormatting.DARK_GREEN)
                    : Component.literal("You have " + hiring.have()).withStyle(hiring.affordable() ? ChatFormatting.DARK_GREEN : ChatFormatting.DARK_RED))
                    .append(Component.literal(" · crew " + crewSize + " · builders " + builders).withStyle(ChatFormatting.DARK_GRAY));
        }
        List<FormattedCharSequence> parts = font.split(second, RIGHT - 28);
        if (!parts.isEmpty()) {
            graphics.drawString(font, parts.get(0), 28, y + 10, TEXT, false);
        }
        graphics.drawString(font, "Name of the new builder", 8, 168, TEXT, false);
    }

    private void renderOrders(GuiGraphics graphics, ScreenPayloads.ForemanState state) {
        graphics.drawString(font, "Name", 8, 52, TEXT, false);
        List<Component> lines = new ArrayList<>();
        lines.add(Component.literal("Owner: " + state.owner()));
        lines.add(Component.literal("Health " + Math.round(state.health()) + "/" + Math.round(state.maxHealth())));
        lines.add(Component.literal(state.working() ? "Busy with " + state.jobFile() + ", so he can't follow you." : "Doing: " + state.phase()));
        drawLines(graphics, lines, 124);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        for (Checkbox box : new Checkbox[]{cornerBox, enchantedBox}) {
            if (box != null) {
                ScreenParts.checkboxLabel(graphics, font, box);
            }
        }
        renderTooltip(graphics, mouseX, mouseY);
    }
}
