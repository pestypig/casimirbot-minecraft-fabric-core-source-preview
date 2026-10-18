package com.casimirbot.helixplayer.fabric;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.particle.Particle;

/**
 * Narrow 1.21.8 boundary between the particle-creation mixin and the typed
 * camera tracker. Weak keys prevent this diagnostic metadata from extending a
 * particle's lifetime. No particle object or native identity crosses the
 * connector protocol.
 */
public final class HelixParticleObservationRegistry {
    private static final Map<Particle, String> TYPES = Collections.synchronizedMap(
        new WeakHashMap<>()
    );

    private HelixParticleObservationRegistry() {}

    public static void record(Particle particle, String particleTypeId) {
        if (particle == null || particleTypeId == null || particleTypeId.isBlank()) return;
        TYPES.put(particle, particleTypeId);
    }

    public static String typeOf(Particle particle) {
        String value = TYPES.get(particle);
        return value == null ? "" : value;
    }
}
