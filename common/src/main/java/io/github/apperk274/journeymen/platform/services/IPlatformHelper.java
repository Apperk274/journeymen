package io.github.apperk274.journeymen.platform.services;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;

public interface IPlatformHelper {

    String getPlatformName();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();

    Path getGameDirectory();

    ServerPlayer getFakePlayer(ServerLevel level, GameProfile profile);

    boolean mayStartBreaking(ServerPlayer player, BlockPos pos);

    boolean mayPlace(ServerPlayer player, BlockPos pos, BlockState state);

    default String getEnvironmentName() {
        return isDevelopmentEnvironment() ? "development" : "production";
    }
}
