package io.github.apperk274.journeymen.build;

import com.mojang.authlib.GameProfile;
import io.github.apperk274.journeymen.platform.Services;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;
import java.util.function.BooleanSupplier;

public final class OwnerRights {

    private static int probing;

    private OwnerRights() {
    }

    public static boolean isProbing() {
        return probing > 0;
    }

    public static ServerPlayer actor(ServerLevel level, UUID owner, String ownerName) {
        return Services.PLATFORM.getFakePlayer(level, new GameProfile(owner, ownerName));
    }

    public static boolean mayStartBreaking(ServerPlayer player, BlockPos pos) {
        return probe(player, pos, () -> Services.PLATFORM.mayStartBreaking(player, pos));
    }

    public static boolean mayPlace(ServerPlayer player, BlockPos pos, BlockState state) {
        return probe(player, pos, () -> Services.PLATFORM.mayPlace(player, pos, state));
    }

    public static boolean mayOpen(ServerPlayer player, BlockPos pos) {
        return probe(player, pos, () -> Services.PLATFORM.mayUseBlock(player, pos));
    }

    public static boolean mayOpenContainer(ServerLevel level, UUID owner, String ownerName, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return true;
        }
        ServerPlayer actor = actor(level, owner, ownerName);
        BlockPos other = Crew.otherChestHalf(level, pos);
        return mayOpen(actor, pos) && (other == null || mayOpen(actor, other));
    }

    private static boolean probe(ServerPlayer player, BlockPos pos, BooleanSupplier check) {
        if (!player.level().mayInteract(player, pos)) {
            return false;
        }
        ItemStack held = player.getMainHandItem();
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        probing++;
        try {
            return check.getAsBoolean();
        } finally {
            probing--;
            player.setItemInHand(InteractionHand.MAIN_HAND, held);
        }
    }
}
