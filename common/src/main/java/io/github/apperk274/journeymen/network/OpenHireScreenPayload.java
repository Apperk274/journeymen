package io.github.apperk274.journeymen.network;

import io.github.apperk274.journeymen.Constants;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

public record OpenHireScreenPayload(int recruiterId, ItemStack price, int owned, int max, String suggestedName) implements CustomPacketPayload {

    public static final Type<OpenHireScreenPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "open_hire_screen"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenHireScreenPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenHireScreenPayload::recruiterId,
            ItemStack.STREAM_CODEC, OpenHireScreenPayload::price,
            ByteBufCodecs.VAR_INT, OpenHireScreenPayload::owned,
            ByteBufCodecs.VAR_INT, OpenHireScreenPayload::max,
            ByteBufCodecs.STRING_UTF8, OpenHireScreenPayload::suggestedName,
            OpenHireScreenPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
