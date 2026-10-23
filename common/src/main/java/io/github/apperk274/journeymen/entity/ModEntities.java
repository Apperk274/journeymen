package io.github.apperk274.journeymen.entity;

import io.github.apperk274.journeymen.Constants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

import java.util.function.BiConsumer;

public class ModEntities {

    public static EntityType<BuilderEntity> BUILDER;
    public static EntityType<RecruiterEntity> RECRUITER;

    public static void register(BiConsumer<ResourceLocation, EntityType<?>> registrar) {
        BUILDER = EntityType.Builder.of(BuilderEntity::new, MobCategory.MISC)
                .sized(0.6F, 1.8F)
                .eyeHeight(1.62F)
                .clientTrackingRange(10)
                .build("builder");
        registrar.accept(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "builder"), BUILDER);
        RECRUITER = EntityType.Builder.of(RecruiterEntity::new, MobCategory.MISC)
                .sized(0.6F, 1.95F)
                .eyeHeight(1.62F)
                .clientTrackingRange(10)
                .build("recruiter");
        registrar.accept(ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "recruiter"), RECRUITER);
    }
}
