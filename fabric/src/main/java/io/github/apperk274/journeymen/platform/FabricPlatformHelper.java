package io.github.apperk274.journeymen.platform;

import com.mojang.authlib.GameProfile;
import io.github.apperk274.journeymen.platform.services.IPlatformHelper;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;

public class FabricPlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "Fabric";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    @Override
    public Path getGameDirectory() {
        return FabricLoader.getInstance().getGameDir();
    }

    @Override
    public ServerPlayer getFakePlayer(ServerLevel level, GameProfile profile) {
        return FakePlayer.get(level, profile);
    }

    @Override
    public boolean mayStartBreaking(ServerPlayer player, BlockPos pos) {
        return AttackBlockCallback.EVENT.invoker().interact(player, player.level(), InteractionHand.MAIN_HAND, pos, Direction.UP) != InteractionResult.FAIL;
    }

    @Override
    public boolean mayPlace(ServerPlayer player, BlockPos pos, BlockState state) {
        BlockState current = player.level().getBlockState(pos);
        return PlayerBlockBreakEvents.BEFORE.invoker().beforeBlockBreak(player.level(), player, pos, current, player.level().getBlockEntity(pos));
    }
}
