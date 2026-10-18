package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class RealtimeTexturePackNativeBlockIdentityMatcherTest {
    @Test
    void matchesTheTypeScriptGoldenVectorExactly() {
        RealtimeTexturePackNativeBlockIdentityMatcher.Derivation derived =
            RealtimeTexturePackNativeBlockIdentityMatcher.derive(
                "minecraft:local:hybrid", "minecraft:overworld", 10, 64, 12,
                "minecraft:stone", "axis:none", "style:dreamlike-forest",
                "realtime_texture_pack.material_instance_variation.v1", 16
            );
        assertEquals(
            "sha256:d503c0749d083c841f94dc6164e19582a97e514ff5275acdea63ea1b3e4d1fff",
            derived.instanceIdentityHash()
        );
        assertEquals(2_690_567_025L, derived.variationSeed());
        assertEquals(1, derived.variationSlot());
    }

    @Test
    void cameraAndTreatmentRevisionsAreNotIdentityInputs() {
        var first = RealtimeTexturePackNativeBlockIdentityMatcher.derive(
            "world:a", "minecraft:overworld", 1, 2, 3, "minecraft:stone",
            "default", "style:a", "realtime_texture_pack.material_instance_variation.v1", 16
        );
        var repeated = RealtimeTexturePackNativeBlockIdentityMatcher.derive(
            "world:a", "minecraft:overworld", 1, 2, 3, "minecraft:stone",
            "default", "style:a", "realtime_texture_pack.material_instance_variation.v1", 16
        );
        var neighbor = RealtimeTexturePackNativeBlockIdentityMatcher.derive(
            "world:a", "minecraft:overworld", 2, 2, 3, "minecraft:stone",
            "default", "style:a", "realtime_texture_pack.material_instance_variation.v1", 16
        );
        assertEquals(first, repeated);
        assertNotEquals(first.instanceIdentityHash(), neighbor.instanceIdentityHash());
    }
}
