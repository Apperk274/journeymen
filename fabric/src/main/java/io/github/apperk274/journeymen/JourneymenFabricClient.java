package io.github.apperk274.journeymen;

import io.github.apperk274.journeymen.client.BuilderRenderer;
import io.github.apperk274.journeymen.client.BuilderScreen;
import io.github.apperk274.journeymen.client.ClientState;
import io.github.apperk274.journeymen.client.ForemanRenderer;
import io.github.apperk274.journeymen.client.PlacementMode;
import io.github.apperk274.journeymen.client.RecruiterRenderer;
import io.github.apperk274.journeymen.entity.ModEntities;
import io.github.apperk274.journeymen.network.JourneymenNetwork;
import io.github.apperk274.journeymen.platform.FabricMenuHelper;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public class JourneymenFabricClient implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        EntityRendererRegistry.register(ModEntities.FOREMAN, ForemanRenderer::new);
        EntityRendererRegistry.register(ModEntities.BUILDER, BuilderRenderer::new);
        EntityRendererRegistry.register(ModEntities.RECRUITER, RecruiterRenderer::new);
        JourneymenNetwork.CLIENT_BOUND.forEach(JourneymenFabricClient::registerClientBound);
        MenuScreens.register(FabricMenuHelper.BUILDER_MENU, BuilderScreen::new);
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> PlacementMode.render(context.camera()));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(ClientState::reset));
    }

    private static <T extends CustomPacketPayload> void registerClientBound(JourneymenNetwork.ClientBound<T> payload) {
        ClientPlayNetworking.registerGlobalReceiver(payload.type(),
                (message, context) -> context.client().execute(() -> payload.handler().accept(message)));
    }
}
