package io.github.apperk274.journeymen;

import io.github.apperk274.journeymen.block.ModBlocks;
import io.github.apperk274.journeymen.command.JourneymenCommand;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.ModEntities;
import io.github.apperk274.journeymen.entity.RecruiterEntity;
import io.github.apperk274.journeymen.item.ModItems;
import io.github.apperk274.journeymen.network.JourneymenNetwork;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import net.neoforged.neoforge.registries.RegisterEvent;

@Mod(Constants.MOD_ID)
public class JourneymenNeoForge {

    public JourneymenNeoForge(IEventBus eventBus) {
        CommonClass.init();
        eventBus.addListener((RegisterEvent event) -> {
            event.register(Registries.BLOCK, helper -> ModBlocks.register(helper::register));
            event.register(Registries.ITEM, helper -> ModItems.register(helper::register));
            event.register(Registries.ENTITY_TYPE, helper -> ModEntities.register(helper::register));
        });
        eventBus.addListener((EntityAttributeCreationEvent event) -> {
            event.put(ModEntities.BUILDER, BuilderEntity.createAttributes().build());
            event.put(ModEntities.RECRUITER, RecruiterEntity.createAttributes().build());
        });
        eventBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
                event.accept(ModItems.RECRUITERS_STALL);
            }
        });
        eventBus.addListener((RegisterPayloadHandlersEvent event) -> {
            PayloadRegistrar registrar = event.registrar("1");
            JourneymenNetwork.SERVER_BOUND.forEach(payload -> registerServerBound(registrar, payload));
            JourneymenNetwork.CLIENT_BOUND.forEach(payload -> registerClientBound(registrar, payload));
        });
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> JourneymenCommand.register(event.getDispatcher()));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent event) -> {
            if (event.getEntity() instanceof ServerPlayer player) {
                CommonClass.onPlayerJoin(player);
            }
        });
    }

    private static <T extends CustomPacketPayload> void registerServerBound(PayloadRegistrar registrar, JourneymenNetwork.ServerBound<T> payload) {
        registrar.playToServer(payload.type(), payload.codec(),
                (message, context) -> context.enqueueWork(() -> payload.handler().accept(message, (ServerPlayer) context.player())));
    }

    private static <T extends CustomPacketPayload> void registerClientBound(PayloadRegistrar registrar, JourneymenNetwork.ClientBound<T> payload) {
        registrar.playToClient(payload.type(), payload.codec(), (message, context) -> context.enqueueWork(() -> payload.handler().accept(message)));
    }
}
