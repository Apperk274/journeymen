package io.github.apperk274.journeymen.client;

import io.github.apperk274.journeymen.entity.ForemanEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

public class ForemanRenderer extends HumanoidMobRenderer<ForemanEntity, PlayerModel<ForemanEntity>> {

    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/player/slim/alex.png");

    public ForemanRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(ForemanEntity entity) {
        return TEXTURE;
    }
}
