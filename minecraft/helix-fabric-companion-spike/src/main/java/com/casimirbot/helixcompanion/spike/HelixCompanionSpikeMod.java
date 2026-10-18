package com.casimirbot.helixcompanion.spike;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;

public final class HelixCompanionSpikeMod implements ModInitializer {
    public static final String MOD_ID = "helix_fabric_companion_spike";
    public static final EntityType<SpikeCompanionEntity> SPIKE_COMPANION = registerCompanion();

    private static EntityType<SpikeCompanionEntity> registerCompanion() {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MOD_ID, "companion");
        ResourceKey<EntityType<?>> key = ResourceKey.create(Registries.ENTITY_TYPE, id);
        EntityType<SpikeCompanionEntity> type = EntityType.Builder
            .of(SpikeCompanionEntity::new, MobCategory.CREATURE)
            .sized(0.6F, 1.8F)
            .clientTrackingRange(10)
            .build(key);
        return net.minecraft.core.Registry.register(BuiltInRegistries.ENTITY_TYPE, key, type);
    }

    @Override
    public void onInitialize() {
        FabricDefaultAttributeRegistry.register(
            SPIKE_COMPANION,
            SpikeCompanionEntity.createCompanionAttributes()
        );
    }
}
