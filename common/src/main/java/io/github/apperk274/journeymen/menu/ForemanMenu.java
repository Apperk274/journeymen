package io.github.apperk274.journeymen.menu;

import io.github.apperk274.journeymen.entity.ForemanEntity;
import io.github.apperk274.journeymen.platform.Services;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public class ForemanMenu extends AbstractContainerMenu {

    public static final int JOB_TAB = 0;
    public static final int CHESTS_TAB = 1;
    public static final int CREW_TAB = 2;
    public static final int ORDERS_TAB = 3;
    public static final int TAB_COUNT = 4;
    public static final String[] TAB_NAMES = {"Job", "Chests", "Crew", "Orders"};

    private final int foremanId;
    @Nullable
    private final ForemanEntity foreman;
    private final boolean clientSide;
    private int activeTab;
    @Nullable
    private String suggestedName;

    public record OpenData(int foremanId, int tab) {

        public static final StreamCodec<ByteBuf, OpenData> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, OpenData::foremanId,
                ByteBufCodecs.VAR_INT, OpenData::tab,
                OpenData::new);
    }

    public ForemanMenu(int containerId, ForemanEntity foreman, int tab) {
        this(containerId, foreman.getId(), tab, foreman, false);
    }

    public ForemanMenu(int containerId, Inventory playerInventory, OpenData data) {
        this(containerId, data.foremanId(), data.tab(), null, true);
    }

    private ForemanMenu(int containerId, int foremanId, int tab, @Nullable ForemanEntity foreman, boolean clientSide) {
        super(Services.MENUS.foremanMenu(), containerId);
        this.foremanId = foremanId;
        this.foreman = foreman;
        this.clientSide = clientSide;
        this.activeTab = tab >= 0 && tab < TAB_COUNT ? tab : JOB_TAB;
    }

    public static String tabName(int tab) {
        return tab >= 0 && tab < TAB_COUNT ? TAB_NAMES[tab] : "?";
    }

    public int getForemanId() {
        return foremanId;
    }

    @Nullable
    public ForemanEntity getForeman() {
        return foreman;
    }

    public int getActiveTab() {
        return activeTab;
    }

    public void setActiveTab(int tab) {
        this.activeTab = tab;
    }

    @Nullable
    public String getSuggestedName() {
        return suggestedName;
    }

    public void setSuggestedName(@Nullable String suggestedName) {
        this.suggestedName = suggestedName;
    }

    @Override
    public boolean stillValid(Player player) {
        return clientSide || foreman != null && foreman.isAlive() && foreman.canBeCommandedBy(player) && player.distanceToSqr(foreman) <= 64.0;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
