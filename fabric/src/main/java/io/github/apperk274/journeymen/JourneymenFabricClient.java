package io.github.apperk274.journeymen;

import io.github.apperk274.journeymen.client.BuilderRenderer;
import io.github.apperk274.journeymen.entity.ModEntities;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

public class JourneymenFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(ModEntities.BUILDER, BuilderRenderer::new);
    }
}
