package io.github.apperk274.journeymen.block;

import io.github.apperk274.journeymen.Constants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

import java.util.function.BiConsumer;

public class ModBlocks {

    public static RecruitersStallBlock RECRUITERS_STALL;

    public static void register(BiConsumer<ResourceLocation, Block> registrar) {
        RECRUITERS_STALL = new RecruitersStallBlock(BlockBehaviour.Properties.of()
                .mapColor(MapColor.WOOD)
                .strength(2.0F)
                .sound(SoundType.WOOD)
                .noOcclusion()
                .ignitedByLava()
                .pushReaction(PushReaction.BLOCK));
        registrar.accept(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "recruiters_stall"), RECRUITERS_STALL);
    }
}
