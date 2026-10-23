package io.github.apperk274.journeymen.network;

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
            new ServerBound<>(HireBuilderPayload.TYPE, HireBuilderPayload.CODEC, ServerHandlers::hireBuilder));

    public static final List<ClientBound<?>> CLIENT_BOUND = List.of(
            new ClientBound<>(OpenHireScreenPayload.TYPE, OpenHireScreenPayload.CODEC, payload -> ClientHandlers.openHireScreen(payload)));

    private JourneymenNetwork() {
    }
}
