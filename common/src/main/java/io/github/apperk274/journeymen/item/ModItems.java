package io.github.apperk274.journeymen.item;

import io.github.apperk274.journeymen.Constants;
import io.github.apperk274.journeymen.block.ModBlocks;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

import java.util.function.BiConsumer;

public class ModItems {

    public static BlockItem RECRUITERS_STALL;

    public static void register(BiConsumer<ResourceLocation, Item> registrar) {
        RECRUITERS_STALL = new BlockItem(ModBlocks.RECRUITERS_STALL, new Item.Properties());
        registrar.accept(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "recruiters_stall"), RECRUITERS_STALL);
    }
}
