package io.github.apperk274.journeymen;

import io.github.apperk274.journeymen.client.BuilderRenderer;
import io.github.apperk274.journeymen.entity.ModEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class JourneymenNeoForgeClient {

    public JourneymenNeoForgeClient(IEventBus eventBus) {
        eventBus.addListener((EntityRenderersEvent.RegisterRenderers event) -> event.registerEntityRenderer(ModEntities.BUILDER, BuilderRenderer::new));
    }
}
