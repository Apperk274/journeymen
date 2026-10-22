package io.github.apperk274.journeymen;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(Constants.MOD_ID)
public class JourneymenNeoForge {

    public JourneymenNeoForge(IEventBus eventBus) {
        CommonClass.init();
    }
}
