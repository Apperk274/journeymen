package io.github.apperk274.journeymen;

import io.github.apperk274.journeymen.command.JourneymenCommand;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.ModEntities;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

public class JourneymenFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        CommonClass.init();
        ModEntities.register((id, type) -> Registry.register(BuiltInRegistries.ENTITY_TYPE, id, type));
        FabricDefaultAttributeRegistry.register(ModEntities.BUILDER, BuilderEntity.createAttributes());
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> JourneymenCommand.register(dispatcher));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> CommonClass.onPlayerJoin(handler.player));
    }
}
