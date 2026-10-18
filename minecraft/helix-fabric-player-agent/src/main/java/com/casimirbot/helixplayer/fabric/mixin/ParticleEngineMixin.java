package com.casimirbot.helixplayer.fabric.mixin;

import com.casimirbot.helixplayer.fabric.HelixParticleObservationRegistry;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ParticleEngine.class)
abstract class ParticleEngineMixin {
    @Inject(method = "createParticle", at = @At("RETURN"))
    private void helix$recordCreatedParticle(
        ParticleOptions options,
        double x,
        double y,
        double z,
        double velocityX,
        double velocityY,
        double velocityZ,
        CallbackInfoReturnable<Particle> callback
    ) {
        Particle particle = callback.getReturnValue();
        if (particle == null) return;
        HelixParticleObservationRegistry.record(
            particle,
            BuiltInRegistries.PARTICLE_TYPE.getKey(options.getType()).toString()
        );
    }
}
