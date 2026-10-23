package io.github.apperk274.journeymen.client;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.apperk274.journeymen.entity.RecruiterEntity;
import net.minecraft.client.model.VillagerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;

public class RecruiterRenderer extends MobRenderer<RecruiterEntity, VillagerModel<RecruiterEntity>> {

    private static final ResourceLocation BASE = ResourceLocation.withDefaultNamespace("textures/entity/villager/villager.png");
    private static final ResourceLocation TYPE = ResourceLocation.withDefaultNamespace("textures/entity/villager/type/plains.png");
    private static final ResourceLocation PROFESSION = ResourceLocation.withDefaultNamespace("textures/entity/villager/profession/librarian.png");

    public RecruiterRenderer(EntityRendererProvider.Context context) {
        super(context, new VillagerModel<>(context.bakeLayer(ModelLayers.VILLAGER)), 0.5F);
        addLayer(new Overlay(this, TYPE));
        addLayer(new Overlay(this, PROFESSION));
    }

    @Override
    public ResourceLocation getTextureLocation(RecruiterEntity entity) {
        return BASE;
    }

    private static final class Overlay extends RenderLayer<RecruiterEntity, VillagerModel<RecruiterEntity>> {

        private final ResourceLocation texture;

        private Overlay(RenderLayerParent<RecruiterEntity, VillagerModel<RecruiterEntity>> parent, ResourceLocation texture) {
            super(parent);
            this.texture = texture;
        }

        @Override
        public void render(PoseStack poseStack, MultiBufferSource buffer, int packedLight, RecruiterEntity entity, float limbSwing,
                           float limbSwingAmount, float partialTick, float ageInTicks, float netHeadYaw, float headPitch) {
            if (!entity.isInvisible()) {
                renderColoredCutoutModel(getParentModel(), texture, poseStack, buffer, packedLight, entity, -1);
            }
        }
    }
}
