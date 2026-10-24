package io.github.apperk274.journeymen;

import io.github.apperk274.journeymen.block.ModBlocks;
import io.github.apperk274.journeymen.client.ClientState;
import io.github.apperk274.journeymen.command.JourneymenCommand;
import io.github.apperk274.journeymen.entity.BuilderEntity;
import io.github.apperk274.journeymen.entity.ModEntities;
import io.github.apperk274.journeymen.entity.RecruiterEntity;
import io.github.apperk274.journeymen.item.ModItems;
import io.github.apperk274.journeymen.menu.LinkModeService;
import io.github.apperk274.journeymen.network.JourneymenNetwork;
import io.github.apperk274.journeymen.platform.FabricMenuHelper;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.CreativeModeTabs;

public class JourneymenFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        CommonClass.init();
        ModBlocks.register((id, block) -> Registry.register(BuiltInRegistries.BLOCK, id, block));
        ModItems.register((id, item) -> Registry.register(BuiltInRegistries.ITEM, id, item));
        ModEntities.register((id, type) -> Registry.register(BuiltInRegistries.ENTITY_TYPE, id, type));
        Registry.register(BuiltInRegistries.MENU, ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "builder"), FabricMenuHelper.BUILDER_MENU);
        FabricDefaultAttributeRegistry.register(ModEntities.BUILDER, BuilderEntity.createAttributes());
        FabricDefaultAttributeRegistry.register(ModEntities.RECRUITER, RecruiterEntity.createAttributes());
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(entries -> entries.accept(ModItems.RECRUITERS_STALL));
        JourneymenNetwork.SERVER_BOUND.forEach(JourneymenFabric::registerServerBound);
        JourneymenNetwork.CLIENT_BOUND.forEach(JourneymenFabric::registerClientBoundType);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> JourneymenCommand.register(dispatcher));
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (hand != InteractionHand.MAIN_HAND) {
                return InteractionResult.PASS;
            }
            boolean handled = player instanceof ServerPlayer serverPlayer
                    ? LinkModeService.onUseBlock(serverPlayer, hit.getBlockPos())
                    : level.isClientSide() && ClientState.link != null;
            return handled ? InteractionResult.SUCCESS : InteractionResult.PASS;
        });
        ServerLifecycleEvents.SERVER_STARTING.register(CommonClass::onServerStarting);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> CommonClass.onServerStopped());
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> CommonClass.onPlayerJoin(handler.player));
    }

    private static <T extends CustomPacketPayload> void registerClientBoundType(JourneymenNetwork.ClientBound<T> payload) {
        PayloadTypeRegistry.playS2C().register(payload.type(), payload.codec());
    }

    private static <T extends CustomPacketPayload> void registerServerBound(JourneymenNetwork.ServerBound<T> payload) {
        PayloadTypeRegistry.playC2S().register(payload.type(), payload.codec());
        ServerPlayNetworking.registerGlobalReceiver(payload.type(),
                (message, context) -> context.server().execute(() -> payload.handler().accept(message, context.player())));
    }
}
