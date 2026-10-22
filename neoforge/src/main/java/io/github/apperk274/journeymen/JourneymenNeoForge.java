package io.github.apperk274.journeymen;

import io.github.apperk274.journeymen.command.JourneymenCommand;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.ModEntities;
import net.minecraft.core.registries.Registries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.RegisterEvent;

@Mod(Constants.MOD_ID)
public class JourneymenNeoForge {

    public JourneymenNeoForge(IEventBus eventBus) {
        CommonClass.init();
        eventBus.addListener((RegisterEvent event) -> event.register(Registries.ENTITY_TYPE, helper -> ModEntities.register(helper::register)));
        eventBus.addListener((EntityAttributeCreationEvent event) -> event.put(ModEntities.BUILDER, BuilderEntity.createAttributes().build()));
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> JourneymenCommand.register(event.getDispatcher()));
    }
}
