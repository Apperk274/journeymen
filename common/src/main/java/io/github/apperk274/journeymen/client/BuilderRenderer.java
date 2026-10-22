package io.github.apperk274.journeymen.client;

import io.github.apperk274.journeymen.entity.BuilderEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.resources.ResourceLocation;

public class BuilderRenderer extends HumanoidMobRenderer<BuilderEntity, PlayerModel<BuilderEntity>> {

    private static final ResourceLocation TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/player/wide/steve.png");

    public BuilderRenderer(EntityRendererProvider.Context context) {
        super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(BuilderEntity entity) {
        return TEXTURE;
    }
}
