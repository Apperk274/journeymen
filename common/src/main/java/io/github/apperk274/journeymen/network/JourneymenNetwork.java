package io.github.apperk274.journeymen.network;

import io.github.apperk274.journeymen.menu.BuilderScreenService;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

public final class JourneymenNetwork {

    public record ServerBound<T extends CustomPacketPayload>(CustomPacketPayload.Type<T> type,
                                                            StreamCodec<RegistryFriendlyByteBuf, T> codec,
                                                            BiConsumer<T, ServerPlayer> handler) {
    }

    public record ClientBound<T extends CustomPacketPayload>(CustomPacketPayload.Type<T> type,
                                                            StreamCodec<RegistryFriendlyByteBuf, T> codec,
                                                            Consumer<T> handler) {
    }

    public static final List<ServerBound<?>> SERVER_BOUND = List.of(
            new ServerBound<>(HireBuilderPayload.TYPE, HireBuilderPayload.CODEC, ServerHandlers::hireBuilder),
            new ServerBound<>(ScreenPayloads.BuilderAction.TYPE, ScreenPayloads.BuilderAction.CODEC, BuilderScreenService::handle));

    public static final List<ClientBound<?>> CLIENT_BOUND = List.of(
            new ClientBound<>(OpenHireScreenPayload.TYPE, OpenHireScreenPayload.CODEC, payload -> ClientHandlers.openHireScreen(payload)),
            new ClientBound<>(ScreenPayloads.BuilderState.TYPE, ScreenPayloads.BuilderState.CODEC, payload -> ClientHandlers.builderState(payload)),
            new ClientBound<>(ScreenPayloads.SchematicList.TYPE, ScreenPayloads.SchematicList.CODEC, payload -> ClientHandlers.schematicList(payload)),
            new ClientBound<>(ScreenPayloads.SchematicInfo.TYPE, ScreenPayloads.SchematicInfo.CODEC, payload -> ClientHandlers.schematicInfo(payload)),
            new ClientBound<>(ScreenPayloads.CheckResult.TYPE, ScreenPayloads.CheckResult.CODEC, payload -> ClientHandlers.checkResult(payload)),
            new ClientBound<>(ScreenPayloads.LinkMode.TYPE, ScreenPayloads.LinkMode.CODEC, payload -> ClientHandlers.linkMode(payload)));

    private JourneymenNetwork() {
    }
}
