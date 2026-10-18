package com.casimirbot.helixplayer.fabric.mixin;

import net.minecraft.client.particle.Particle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Particle.class)
public interface ParticleAccessor {
    @Accessor("x") double helix$getX();
    @Accessor("y") double helix$getY();
    @Accessor("z") double helix$getZ();
    @Accessor("xd") double helix$getVelocityX();
    @Accessor("yd") double helix$getVelocityY();
    @Accessor("zd") double helix$getVelocityZ();
}
