package io.github.apperk274.journeymen.network;

import io.github.apperk274.journeymen.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record HireBuilderPayload(int recruiterId, String name) implements CustomPacketPayload {

    public static final Type<HireBuilderPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "hire_builder"));
    public static final StreamCodec<RegistryFriendlyByteBuf, HireBuilderPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, HireBuilderPayload::recruiterId,
            ByteBufCodecs.stringUtf8(64), HireBuilderPayload::name,
            HireBuilderPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
