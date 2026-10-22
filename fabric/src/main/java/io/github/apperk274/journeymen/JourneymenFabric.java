package io.github.apperk274.journeymen;

import io.github.apperk274.journeymen.command.JourneymenCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

public class JourneymenFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        CommonClass.init();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> JourneymenCommand.register(dispatcher));
    }
}
