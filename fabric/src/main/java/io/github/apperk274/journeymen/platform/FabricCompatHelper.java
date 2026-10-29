package io.github.apperk274.journeymen.platform;

import io.github.apperk274.journeymen.build.BlockRequirements;
import io.github.apperk274.journeymen.build.ItemStore;
import io.github.apperk274.journeymen.platform.services.ICompatHelper;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FabricCompatHelper implements ICompatHelper {

    private static final Map<Block, Block> TILLED = Map.of(
            Blocks.GRASS_BLOCK, Blocks.FARMLAND,
            Blocks.DIRT_PATH, Blocks.FARMLAND,
            Blocks.DIRT, Blocks.FARMLAND,
            Blocks.COARSE_DIRT, Blocks.DIRT,
            Blocks.ROOTED_DIRT, Blocks.DIRT);
    private static final Set<Block> FLATTENABLE = Set.of(
            Blocks.GRASS_BLOCK, Blocks.DIRT, Blocks.PODZOL, Blocks.COARSE_DIRT, Blocks.MYCELIUM, Blocks.ROOTED_DIRT);

    @Nullable
    @Override
    public ItemStore findItemStore(ServerLevel level, BlockPos pos) {
        Storage<ItemVariant> storage = ItemStorage.SIDED.find(level, pos, null);
        if (storage instanceof SlottedStorage<ItemVariant> slotted) {
            return new SlottedStore(slotted);
        }
        return storage == null ? null : new ViewStore(storage);
    }

    @Nullable
    @Override
    public Fluid bucketFluid(ItemStack stack) {
        if (stack.getItem() instanceof MobBucketItem) {
            return null;
        }
        Storage<FluidVariant> storage = ContainerItemContext.withConstant(stack.copyWithCount(1)).find(FluidStorage.ITEM);
        if (storage == null) {
            return null;
        }
        for (StorageView<FluidVariant> view : storage) {
            if (!view.isResourceBlank() && view.getAmount() >= FluidConstants.BUCKET) {
                return view.getResource().getFluid();
            }
        }
        return null;
    }

    @Nullable
    @Override
    public ItemStack drainBucket(ItemStack container, Fluid fluid) {
        return moveBucket(container, fluid, false);
    }

    @Nullable
    @Override
    public ItemStack fillBucket(ItemStack container, Fluid fluid) {
        if (container.isEmpty() || bucketFluid(container) != null) {
            return null;
        }
        return moveBucket(container, fluid, true);
    }

    @Nullable
    private static ItemStack moveBucket(ItemStack container, Fluid fluid, boolean fill) {
        SingleVariantStorage<ItemVariant> slot = new SingleVariantStorage<>() {
            @Override
            protected ItemVariant getBlankVariant() {
                return ItemVariant.blank();
            }

            @Override
            protected long getCapacity(ItemVariant variant) {
                return Math.max(1, variant.getItem().getDefaultMaxStackSize());
            }
        };
        slot.variant = ItemVariant.of(container);
        slot.amount = 1;
        Storage<FluidVariant> storage = ContainerItemContext.ofSingleSlot(slot).find(FluidStorage.ITEM);
        if (storage == null) {
            return null;
        }
        try (Transaction transaction = Transaction.openOuter()) {
            long moved = fill ? storage.insert(FluidVariant.of(fluid), FluidConstants.BUCKET, transaction)
                    : storage.extract(FluidVariant.of(fluid), FluidConstants.BUCKET, transaction);
            if (moved != FluidConstants.BUCKET) {
                return null;
            }
            transaction.commit();
        }
        return slot.isResourceBlank() ? ItemStack.EMPTY : slot.getResource().toStack((int) slot.getAmount());
    }

    @Nullable
    @Override
    public BlockState toolModifiedState(ServerLevel level, BlockState state, BlockRequirements.ToolUse use) {
        if (use == BlockRequirements.ToolUse.IGNITE) {
            return null;
        }
        if (use == BlockRequirements.ToolUse.HOE) {
            Block tilled = TILLED.get(state.getBlock());
            return tilled == null ? null : tilled.defaultBlockState();
        }
        return FLATTENABLE.contains(state.getBlock()) ? Blocks.DIRT_PATH.defaultBlockState() : null;
    }

    @Override
    public boolean canDo(ItemStack stack, BlockRequirements.ToolUse use) {
        return switch (use) {
            case HOE -> stack.getItem() instanceof HoeItem;
            case SHOVEL -> stack.getItem() instanceof ShovelItem;
            case IGNITE -> stack.getItem() instanceof FlintAndSteelItem;
        };
    }

    private record SlottedStore(SlottedStorage<ItemVariant> storage) implements ItemStore {

        @Override
        public int size() {
            return storage.getSlotCount();
        }

        @Override
        public ItemStack get(int slot) {
            SingleSlotStorage<ItemVariant> view = storage.getSlot(slot);
            return view.isResourceBlank() ? ItemStack.EMPTY : view.getResource().toStack((int) view.getAmount());
        }

        @Override
        public ItemStack take(int slot, int count) {
            SingleSlotStorage<ItemVariant> view = storage.getSlot(slot);
            ItemVariant resource = view.getResource();
            if (resource.isBlank()) {
                return ItemStack.EMPTY;
            }
            try (Transaction transaction = Transaction.openOuter()) {
                long taken = view.extract(resource, count, transaction);
                transaction.commit();
                return resource.toStack((int) taken);
            }
        }

        @Override
        public ItemStack insert(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) {
                return stack;
            }
            try (Transaction transaction = Transaction.openOuter()) {
                long inserted = storage.getSlot(slot).insert(ItemVariant.of(stack), stack.getCount(), transaction);
                if (!simulate) {
                    transaction.commit();
                }
                return stack.copyWithCount(stack.getCount() - (int) inserted);
            }
        }

        @Override
        public boolean accepts(int slot, ItemStack stack) {
            return insert(slot, stack.copyWithCount(1), true).isEmpty();
        }

        @Override
        public int maxStack(int slot, ItemStack stack) {
            return (int) Math.min(stack.getMaxStackSize(), storage.getSlot(slot).getCapacity());
        }

        @Override
        public void setChanged() {
        }
    }

    private static final class ViewStore implements ItemStore {

        private final Storage<ItemVariant> storage;
        private final List<StorageView<ItemVariant>> views = new ArrayList<>();

        private ViewStore(Storage<ItemVariant> storage) {
            this.storage = storage;
            for (StorageView<ItemVariant> view : storage.nonEmptyViews()) {
                views.add(view);
            }
        }

        @Override
        public int size() {
            return views.size() + 1;
        }

        @Override
        public ItemStack get(int slot) {
            if (slot >= views.size() || views.get(slot).isResourceBlank()) {
                return ItemStack.EMPTY;
            }
            StorageView<ItemVariant> view = views.get(slot);
            return view.getResource().toStack((int) Math.min(Integer.MAX_VALUE, view.getAmount()));
        }

        @Override
        public ItemStack take(int slot, int count) {
            if (slot >= views.size() || views.get(slot).isResourceBlank()) {
                return ItemStack.EMPTY;
            }
            ItemVariant resource = views.get(slot).getResource();
            try (Transaction transaction = Transaction.openOuter()) {
                long taken = views.get(slot).extract(resource, count, transaction);
                transaction.commit();
                return resource.toStack((int) taken);
            }
        }

        @Override
        public ItemStack insert(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) {
                return stack;
            }
            try (Transaction transaction = Transaction.openOuter()) {
                long inserted = storage.insert(ItemVariant.of(stack), stack.getCount(), transaction);
                if (!simulate) {
                    transaction.commit();
                }
                return stack.copyWithCount(stack.getCount() - (int) inserted);
            }
        }

        @Override
        public boolean accepts(int slot, ItemStack stack) {
            return insert(slot, stack.copyWithCount(1), true).isEmpty();
        }

        @Override
        public int maxStack(int slot, ItemStack stack) {
            return stack.getMaxStackSize();
        }

        @Override
        public void setChanged() {
        }
    }
}
