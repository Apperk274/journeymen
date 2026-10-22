package io.github.apperk274.journeymen;

import io.github.apperk274.journeymen.command.JourneymenCommand;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@Mod(Constants.MOD_ID)
public class JourneymenNeoForge {

    public JourneymenNeoForge(IEventBus eventBus) {
        CommonClass.init();
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> JourneymenCommand.register(event.getDispatcher()));
    }
}
