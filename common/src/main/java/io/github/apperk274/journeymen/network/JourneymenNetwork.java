package io.github.apperk274.journeymen.network;

import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.menu.ScreenActions;
import io.github.apperk274.journeymen.platform.Services;
import io.netty.buffer.Unpooled;
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
            new ServerBound<>(ScreenPayloads.Action.TYPE, ScreenPayloads.Action.CODEC, ScreenActions::handle));

    public static final List<ClientBound<?>> CLIENT_BOUND = List.of(
            new ClientBound<>(OpenHireScreenPayload.TYPE, OpenHireScreenPayload.CODEC, payload -> ClientHandlers.openHireScreen(payload)),
            new ClientBound<>(ScreenPayloads.BuilderState.TYPE, ScreenPayloads.BuilderState.CODEC, payload -> ClientHandlers.builderState(payload)),
            new ClientBound<>(ScreenPayloads.ForemanState.TYPE, ScreenPayloads.ForemanState.CODEC, payload -> ClientHandlers.foremanState(payload)),
            new ClientBound<>(ScreenPayloads.SchematicList.TYPE, ScreenPayloads.SchematicList.CODEC, payload -> ClientHandlers.schematicList(payload)),
            new ClientBound<>(ScreenPayloads.SchematicInfo.TYPE, ScreenPayloads.SchematicInfo.CODEC, payload -> ClientHandlers.schematicInfo(payload)),
            new ClientBound<>(ScreenPayloads.SchematicBlocks.TYPE, ScreenPayloads.SchematicBlocks.CODEC, payload -> ClientHandlers.schematicBlocks(payload)),
            new ClientBound<>(ScreenPayloads.CheckResult.TYPE, ScreenPayloads.CheckResult.CODEC, payload -> ClientHandlers.checkResult(payload)),
            new ClientBound<>(ScreenPayloads.LinkMode.TYPE, ScreenPayloads.LinkMode.CODEC, payload -> ClientHandlers.linkMode(payload)),
            new ClientBound<>(ScreenPayloads.Gone.TYPE, ScreenPayloads.Gone.CODEC, payload -> ClientHandlers.journeymanGone(payload)));

    private JourneymenNetwork() {
    }

    public static void checkInDevelopment(ServerPlayer player, CustomPacketPayload payload) {
        if (!Services.PLATFORM.isDevelopmentEnvironment()) {
            return;
        }
        for (ClientBound<?> bound : CLIENT_BOUND) {
            if (bound.type().equals(payload.type())) {
                roundTrip(player, bound, payload);
                return;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends CustomPacketPayload> void roundTrip(ServerPlayer player, ClientBound<T> bound, CustomPacketPayload payload) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), player.registryAccess());
        try {
            bound.codec().encode(buf, (T) payload);
            T decoded = bound.codec().decode(buf);
            if (decoded instanceof OpenHireScreenPayload hire) {
                Constants.LOG.info("[hire screen] {}: price {} {}, {} of {} foremen", player.getGameProfile().getName(), hire.price().getCount(),
                        hire.price().isEmpty() ? "(free)" : hire.price().getHoverName().getString(), hire.owned(), hire.max());
            }
        } catch (RuntimeException e) {
            Constants.LOG.error("[payload] Could not send {} to {}: {}", payload.type().id(), player.getGameProfile().getName(), e.toString());
        } finally {
            buf.release();
        }
    }
}
