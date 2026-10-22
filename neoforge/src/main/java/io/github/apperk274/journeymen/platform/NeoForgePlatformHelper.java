package io.github.apperk274.journeymen.platform;

import com.mojang.authlib.GameProfile;
import io.github.apperk274.journeymen.platform.services.IPlatformHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.event.EventHooks;

import java.nio.file.Path;

public class NeoForgePlatformHelper implements IPlatformHelper {

    @Override
    public String getPlatformName() {
        return "NeoForge";
    }

    @Override
    public boolean isModLoaded(String modId) {
        return ModList.get().isLoaded(modId);
    }

    @Override
    public boolean isDevelopmentEnvironment() {
        return !FMLLoader.isProduction();
    }

    @Override
    public Path getGameDirectory() {
        return FMLPaths.GAMEDIR.get();
    }

    @Override
    public ServerPlayer getFakePlayer(ServerLevel level, GameProfile profile) {
        return FakePlayerFactory.get(level, profile);
    }

    @Override
    public boolean mayBreak(ServerPlayer player, BlockPos pos, BlockState state) {
        return !CommonHooks.fireBlockBreak(player.level(), GameType.SURVIVAL, player, pos, state).isCanceled();
    }

    @Override
    public boolean mayPlace(ServerPlayer player, BlockPos pos, BlockState state) {
        return !EventHooks.onBlockPlace(player, BlockSnapshot.create(player.level().dimension(), player.level(), pos), Direction.UP);
    }
}
