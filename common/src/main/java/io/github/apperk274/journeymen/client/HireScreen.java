package io.github.apperk274.journeymen.client;

import io.github.apperk274.journeymen.entity.JourneymanNames;
import io.github.apperk274.journeymen.hire.HirePrice;
import io.github.apperk274.journeymen.network.HireBuilderPayload;
import io.github.apperk274.journeymen.network.OpenHireScreenPayload;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

public class HireScreen extends Screen {

    private static final int WIDTH = 200;

    private final OpenHireScreenPayload data;
    private EditBox nameBox;
    private Button hireButton;

    public HireScreen(OpenHireScreenPayload data) {
        super(Component.literal("Recruiter"));
        this.data = data;
    }

    @Override
    protected void init() {
        int left = (width - WIDTH) / 2;
        int top = height / 2 - 50;
        nameBox = new EditBox(font, left, top + 60, WIDTH, 20, Component.literal("Name"));
        nameBox.setMaxLength(JourneymanNames.MAX_LENGTH);
        nameBox.setFilter(text -> text.chars().allMatch(c -> Character.isLetterOrDigit(c) && c < 128 || c == '_' || c == '-'));
        nameBox.setValue(data.suggestedName());
        addRenderableWidget(nameBox);
        setInitialFocus(nameBox);
        hireButton = addRenderableWidget(Button.builder(Component.literal("Hire"), button -> hire())
                .bounds(left, top + 88, WIDTH / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> onClose())
                .bounds(left + WIDTH / 2 + 2, top + 88, WIDTH / 2 - 2, 20).build());
        updateButtons();
    }

    @Override
    public void tick() {
        super.tick();
        updateButtons();
    }

    private void updateButtons() {
        hireButton.active = canAfford() && !atLimit() && !nameBox.getValue().isBlank();
    }

    private boolean canAfford() {
        return minecraft.player.getAbilities().instabuild || have() >= data.price().getCount();
    }

    private boolean atLimit() {
        return data.max() > 0 && data.owned() >= data.max();
    }

    private int have() {
        HirePrice cost = HirePrice.parse(data.cost());
        return cost == null ? minecraft.player.getInventory().countItem(data.price().getItem()) : cost.count(minecraft.player.getInventory());
    }

    private void hire() {
        Services.NETWORK.sendToServer(new HireBuilderPayload(data.recruiterId(), nameBox.getValue()));
        onClose();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        int left = (width - WIDTH) / 2;
        int top = height / 2 - 50;
        graphics.drawCenteredString(font, Component.literal("Hire a foreman").withStyle(ChatFormatting.BOLD), width / 2, top - 6, 0xFFFFFF);
        ItemStack price = data.price();
        if (price.isEmpty()) {
            graphics.drawString(font, Component.literal("Free").withStyle(ChatFormatting.GREEN), left + 20, top + 12, 0xFFFFFF);
        } else {
            graphics.renderItem(price, left, top + 8);
            graphics.drawString(font, Component.literal(price.getCount() + " × ").append(price.getHoverName()), left + 20, top + 12, 0xFFFFFF);
            Component haveLine = minecraft.player.getAbilities().instabuild ? Component.literal("Free in creative").withStyle(ChatFormatting.GREEN)
                    : Component.literal("You have " + have()).withStyle(canAfford() ? ChatFormatting.GRAY : ChatFormatting.RED);
            graphics.drawString(font, haveLine, left + 20, top + 24, 0xFFFFFF);
        }
        String foremen = data.max() > 0 ? data.owned() + " of " + data.max() + " foremen" : data.owned() + " foremen";
        graphics.drawString(font, Component.literal("You have " + foremen).withStyle(atLimit() ? ChatFormatting.RED : ChatFormatting.GRAY),
                left, top + 36, 0xFFFFFF);
        graphics.drawString(font, Component.literal("Name").withStyle(ChatFormatting.GRAY), left, top + 50, 0xFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
