package com.casimirbot.helixcompanion.spike;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

public final class SpikeCompanionEntity extends PathfinderMob {
    private final SimpleContainer canonicalInventory = new SimpleContainer(9);

    public SpikeCompanionEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    public static AttributeSupplier.Builder createCompanionAttributes() {
        return PathfinderMob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0D)
            .add(Attributes.MOVEMENT_SPEED, 0.30D)
            .add(Attributes.FOLLOW_RANGE, 24.0D);
    }

    public SimpleContainer canonicalInventory() {
        return canonicalInventory;
    }

    @Override
    protected void registerGoals() {
        // C1 keeps autonomous behavior empty. Only one admitted resident
        // controller may assert native movement or look controls.
    }
}
