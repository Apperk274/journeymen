package io.github.apperk274.journeymen.platform;

import io.github.apperk274.journeymen.build.BlockRequirements;
import io.github.apperk274.journeymen.build.ItemStore;
import io.github.apperk274.journeymen.platform.services.ICompatHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MobBucketItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.ItemAbilities;
import net.neoforged.neoforge.common.ItemAbility;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.IFluidHandlerItem;
import net.neoforged.neoforge.items.IItemHandler;
import org.jetbrains.annotations.Nullable;

public class NeoForgeCompatHelper implements ICompatHelper {

    @Nullable
    @Override
    public ItemStore findItemStore(ServerLevel level, BlockPos pos) {
        IItemHandler handler = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
        return handler == null ? null : new HandlerStore(handler);
    }

    @Nullable
    @Override
    public Fluid bucketFluid(ItemStack stack) {
        if (stack.getItem() instanceof MobBucketItem) {
            return null;
        }
        return FluidUtil.getFluidContained(stack)
                .filter(fluid -> fluid.getAmount() >= FluidType.BUCKET_VOLUME)
                .map(FluidStack::getFluid)
                .orElse(null);
    }

    @Nullable
    @Override
    public ItemStack drainBucket(ItemStack container, Fluid fluid) {
        IFluidHandlerItem handler = FluidUtil.getFluidHandler(container.copyWithCount(1)).orElse(null);
        if (handler == null) {
            return null;
        }
        FluidStack drained = handler.drain(new FluidStack(fluid, FluidType.BUCKET_VOLUME), IFluidHandler.FluidAction.EXECUTE);
        return drained.getAmount() >= FluidType.BUCKET_VOLUME ? handler.getContainer() : null;
    }

    @Nullable
    @Override
    public ItemStack fillBucket(ItemStack container, Fluid fluid) {
        if (container.isEmpty() || container.getCapability(Capabilities.FluidHandler.ITEM) == null) {
            return null;
        }
        IFluidHandlerItem handler = FluidUtil.getFluidHandler(container.copyWithCount(1)).orElse(null);
        if (handler == null || !FluidUtil.getFluidContained(container).map(FluidStack::isEmpty).orElse(true)) {
            return null;
        }
        int filled = handler.fill(new FluidStack(fluid, FluidType.BUCKET_VOLUME), IFluidHandler.FluidAction.EXECUTE);
        return filled >= FluidType.BUCKET_VOLUME ? handler.getContainer() : null;
    }

    @Nullable
    @Override
    public BlockState toolModifiedState(ServerLevel level, BlockState state, BlockRequirements.ToolUse use) {
        if (use == BlockRequirements.ToolUse.IGNITE) {
            return null;
        }
        BlockPos pos = new BlockPos(0, level.getMaxBuildHeight() - 1, 0);
        ItemStack tool = new ItemStack(use == BlockRequirements.ToolUse.HOE ? Items.IRON_HOE : Items.IRON_SHOVEL);
        UseOnContext context = new UseOnContext(level, null, InteractionHand.MAIN_HAND, tool,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false)) {
        };
        try {
            return state.getToolModifiedState(context, ability(use), true);
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    public boolean canDo(ItemStack stack, BlockRequirements.ToolUse use) {
        return stack.canPerformAction(ability(use)) && (use != BlockRequirements.ToolUse.IGNITE || stack.isDamageableItem());
    }

    private static ItemAbility ability(BlockRequirements.ToolUse use) {
        return switch (use) {
            case HOE -> ItemAbilities.HOE_TILL;
            case SHOVEL -> ItemAbilities.SHOVEL_FLATTEN;
            case IGNITE -> ItemAbilities.FIRESTARTER_LIGHT;
        };
    }

    private record HandlerStore(IItemHandler handler) implements ItemStore {

        @Override
        public int size() {
            return handler.getSlots();
        }

        @Override
        public ItemStack get(int slot) {
            return handler.getStackInSlot(slot);
        }

        @Override
        public ItemStack take(int slot, int count) {
            return handler.extractItem(slot, count, false);
        }

        @Override
        public ItemStack insert(int slot, ItemStack stack, boolean simulate) {
            return handler.insertItem(slot, stack, simulate);
        }

        @Override
        public boolean accepts(int slot, ItemStack stack) {
            return handler.isItemValid(slot, stack);
        }

        @Override
        public int maxStack(int slot, ItemStack stack) {
            return Math.min(handler.getSlotLimit(slot), stack.getMaxStackSize());
        }

        @Override
        public void setChanged() {
        }
    }
}
