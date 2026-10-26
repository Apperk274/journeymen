package io.github.apperk274.journeymen;

import io.github.apperk274.journeymen.client.BuilderRenderer;
import io.github.apperk274.journeymen.client.BuilderScreen;
import io.github.apperk274.journeymen.client.ClientState;
import io.github.apperk274.journeymen.client.ConfigScreen;
import io.github.apperk274.journeymen.client.ForemanRenderer;
import io.github.apperk274.journeymen.client.ForemanScreen;
import io.github.apperk274.journeymen.client.PlacementMode;
import io.github.apperk274.journeymen.client.RecruiterRenderer;
import io.github.apperk274.journeymen.entity.ModEntities;
import io.github.apperk274.journeymen.platform.NeoForgeMenuHelper;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class JourneymenNeoForgeClient {

    public JourneymenNeoForgeClient(IEventBus eventBus, ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, (mod, parent) -> new ConfigScreen(parent));
        eventBus.addListener((EntityRenderersEvent.RegisterRenderers event) -> {
            event.registerEntityRenderer(ModEntities.FOREMAN, ForemanRenderer::new);
            event.registerEntityRenderer(ModEntities.BUILDER, BuilderRenderer::new);
            event.registerEntityRenderer(ModEntities.RECRUITER, RecruiterRenderer::new);
        });
        eventBus.addListener((RegisterMenuScreensEvent event) -> {
            event.register(NeoForgeMenuHelper.BUILDER_MENU, BuilderScreen::new);
            event.register(NeoForgeMenuHelper.FOREMAN_MENU, ForemanScreen::new);
        });
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> ClientState.reset());
        NeoForge.EVENT_BUS.addListener((RenderLevelStageEvent event) -> {
            if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
                PlacementMode.render(event.getCamera());
            }
        });
    }
}
