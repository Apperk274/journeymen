package io.github.apperk274.journeymen.menu;

import com.mojang.datafixers.util.Pair;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.platform.Services;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

public class BuilderMenu extends AbstractContainerMenu {

    public static final int INVENTORY_TAB = 0;
    public static final int ORDERS_TAB = 1;
    public static final int TAB_COUNT = 2;
    public static final int BUILDER_SLOTS = BuilderEntity.INVENTORY_SIZE;
    public static final int ARMOR_SLOTS = 4;
    public static final int PLAYER_SLOTS = 36;
    private static final EquipmentSlot[] ARMOR_ORDER = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final ResourceLocation[] ARMOR_ICONS = {InventoryMenu.EMPTY_ARMOR_SLOT_HELMET, InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
            InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS, InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS};

    private final int builderId;
    @Nullable
    private final BuilderEntity builder;
    private final boolean clientSide;
    private int activeTab;

    public record OpenData(int builderId, int tab) {

        public static final StreamCodec<ByteBuf, OpenData> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, OpenData::builderId,
                ByteBufCodecs.VAR_INT, OpenData::tab,
                OpenData::new);
    }

    public BuilderMenu(int containerId, Inventory playerInventory, BuilderEntity builder, int tab) {
        this(containerId, playerInventory, builder.getId(), tab, builder, builder.getInventory(), new ArmorContainer(builder), false);
    }

    public BuilderMenu(int containerId, Inventory playerInventory, OpenData data) {
        this(containerId, playerInventory, data.builderId(), data.tab(), findBuilder(playerInventory.player, data.builderId()),
                new SimpleContainer(BUILDER_SLOTS), new SimpleContainer(ARMOR_SLOTS), true);
    }

    private BuilderMenu(int containerId, Inventory playerInventory, int builderId, int tab, @Nullable BuilderEntity builder, Container inventory,
                        Container armor, boolean clientSide) {
        super(Services.MENUS.builderMenu(), containerId);
        this.builderId = builderId;
        this.builder = builder;
        this.clientSide = clientSide;
        this.activeTab = tab >= 0 && tab < TAB_COUNT ? tab : INVENTORY_TAB;
        for (int i = 0; i < ARMOR_SLOTS; i++) {
            EquipmentSlot equipmentSlot = ARMOR_ORDER[i];
            ResourceLocation icon = ARMOR_ICONS[i];
            addSlot(new TabSlot(armor, i, 8 + i * 18, 20) {
                @Override
                public boolean mayPlace(ItemStack stack) {
                    return equipmentSlotFor(stack) == equipmentSlot;
                }

                @Override
                public int getMaxStackSize() {
                    return 1;
                }

                @Override
                public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
                    return Pair.of(InventoryMenu.BLOCK_ATLAS, icon);
                }
            });
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new TabSlot(inventory, column + row * 9, 8 + column * 18, 50 + row * 18));
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new TabSlot(playerInventory, column + row * 9 + 9, 8 + column * 18, 122 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new TabSlot(playerInventory, column, 8 + column * 18, 180));
        }
    }

    @Nullable
    private static BuilderEntity findBuilder(Player player, int id) {
        Entity entity = player.level().getEntity(id);
        return entity instanceof BuilderEntity builder ? builder : null;
    }

    private EquipmentSlot equipmentSlotFor(ItemStack stack) {
        EquipmentSlot slot = builder != null ? builder.getEquipmentSlotForItem(stack) : EquipmentSlot.MAINHAND;
        return slot.getType() == EquipmentSlot.Type.HUMANOID_ARMOR ? slot : EquipmentSlot.MAINHAND;
    }

    public int getBuilderId() {
        return builderId;
    }

    @Nullable
    public BuilderEntity getBuilder() {
        return builder;
    }

    public int getActiveTab() {
        return activeTab;
    }

    public void setActiveTab(int tab) {
        this.activeTab = tab;
    }

    @Override
    public boolean stillValid(Player player) {
        return clientSide || builder != null && builder.isAlive() && builder.canBeCommandedBy(player) && player.distanceToSqr(builder) <= 64.0;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        int builderEnd = ARMOR_SLOTS + BUILDER_SLOTS;
        if (index < builderEnd) {
            if (!moveItemStackTo(stack, builderEnd, builderEnd + PLAYER_SLOTS, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            EquipmentSlot equipmentSlot = equipmentSlotFor(stack);
            boolean moved = false;
            if (equipmentSlot != EquipmentSlot.MAINHAND) {
                for (int i = 0; i < ARMOR_SLOTS; i++) {
                    if (ARMOR_ORDER[i] == equipmentSlot && !slots.get(i).hasItem()) {
                        moved = moveItemStackTo(stack, i, i + 1, false);
                        break;
                    }
                }
            }
            if (!moved && !moveItemStackTo(stack, ARMOR_SLOTS, builderEnd, false)) {
                return ItemStack.EMPTY;
            }
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    private class TabSlot extends Slot {

        TabSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean isActive() {
            return !clientSide || activeTab == INVENTORY_TAB;
        }
    }

    private static final class ArmorContainer implements Container {

        private final BuilderEntity builder;

        private ArmorContainer(BuilderEntity builder) {
            this.builder = builder;
        }

        @Override
        public int getContainerSize() {
            return ARMOR_SLOTS;
        }

        @Override
        public boolean isEmpty() {
            for (EquipmentSlot slot : ARMOR_ORDER) {
                if (!builder.getItemBySlot(slot).isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getItem(int index) {
            return builder.getItemBySlot(ARMOR_ORDER[index]);
        }

        @Override
        public ItemStack removeItem(int index, int count) {
            ItemStack stack = getItem(index);
            ItemStack removed = stack.split(count);
            if (stack.isEmpty()) {
                builder.setItemSlot(ARMOR_ORDER[index], ItemStack.EMPTY);
            }
            return removed;
        }

        @Override
        public ItemStack removeItemNoUpdate(int index) {
            ItemStack stack = getItem(index);
            builder.setItemSlot(ARMOR_ORDER[index], ItemStack.EMPTY);
            return stack;
        }

        @Override
        public void setItem(int index, ItemStack stack) {
            builder.setItemSlot(ARMOR_ORDER[index], stack);
        }

        @Override
        public void setChanged() {
        }

        @Override
        public boolean stillValid(Player player) {
            return builder.isAlive();
        }

        @Override
        public void clearContent() {
            for (EquipmentSlot slot : ARMOR_ORDER) {
                builder.setItemSlot(slot, ItemStack.EMPTY);
            }
        }
    }
}
