package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class RealtimeTexturePackDebugRenderStateTest {
    private static final long NOW = 1_788_091_202_000L;
    private static final String HASH_A = "sha256:" + "a".repeat(64);
    private static final String HASH_B = "sha256:" + "b".repeat(64);
    private static final String HASH_C = "sha256:" + "c".repeat(64);
    private static final String HASH_GOLDEN =
        "sha256:d503c0749d083c841f94dc6164e19582a97e514ff5275acdea63ea1b3e4d1fff";

    @Test
    void admitsAnAuthorityFreeProjectionOnTheRenderSeam() {
        RealtimeTexturePackDebugRenderState state = new RealtimeTexturePackDebugRenderState();
        state.admit(projection(1, HASH_A, "rtp_source_frame:hybrid:7"), NOW);

        RealtimeTexturePackDebugRenderState.RenderSnapshot snapshot =
            state.renderFrame(123_456L, NOW + 1).orElseThrow();
        assertEquals(3, snapshot.materialInstanceCount());
        assertEquals(3, snapshot.distinctVariationSlotCount());
        assertFalse(snapshot.textureMutationPerformed());
        assertFalse(snapshot.providerRequestPerformed());
    }

    @Test
    void acceptsANewerCameraFrameWithoutChangingMaterialAssignments() {
        RealtimeTexturePackDebugRenderState state = new RealtimeTexturePackDebugRenderState();
        Map<String, Object> first = projection(1, HASH_A, "rtp_source_frame:hybrid:7");
        Map<String, Object> second = projection(2, HASH_B, "rtp_source_frame:hybrid:8");
        assertEquals(first.get("material_instances"), second.get("material_instances"));

        state.admit(first, NOW);
        state.admit(second, NOW + 1);
        assertEquals(
            "rtp_source_frame:hybrid:8",
            state.renderFrame(222L, NOW + 2).orElseThrow().sourceFrameId()
        );
    }

    @Test
    void rejectsRegressedConflictingAndReboundProjections() {
        RealtimeTexturePackDebugRenderState state = new RealtimeTexturePackDebugRenderState();
        state.admit(projection(2, HASH_B, "rtp_source_frame:hybrid:8"), NOW);
        assertEquals(
            "realtime_texture_pack_fabric_projection_revision_regressed",
            assertThrows(IllegalArgumentException.class, () ->
                state.admit(projection(1, HASH_A, "rtp_source_frame:hybrid:7"), NOW)
            ).getMessage()
        );
        Map<String, Object> conflict = projection(2, HASH_C, "rtp_source_frame:hybrid:9");
        assertEquals(
            "realtime_texture_pack_fabric_projection_revision_conflict",
            assertThrows(IllegalArgumentException.class, () -> state.admit(conflict, NOW)).getMessage()
        );
        Map<String, Object> rebound = projection(3, HASH_C, "rtp_source_frame:hybrid:10");
        rebound.put("capture_session_id", "rtp_capture_session:other");
        assertEquals(
            "realtime_texture_pack_fabric_projection_binding_mismatch",
            assertThrows(IllegalArgumentException.class, () -> state.admit(rebound, NOW)).getMessage()
        );
    }

    @Test
    void rejectsPromptBodiesAndAuthorityExpansion() {
        RealtimeTexturePackDebugRenderState state = new RealtimeTexturePackDebugRenderState();
        Map<String, Object> promptLeak = projection(1, HASH_A, "rtp_source_frame:hybrid:7");
        promptLeak.put("overlay_prompt", "secret prompt body");
        assertTrue(assertThrows(IllegalArgumentException.class, () -> state.admit(promptLeak, NOW))
            .getMessage().contains("projection_keys"));

        Map<String, Object> authority = projection(1, HASH_A, "rtp_source_frame:hybrid:7");
        authority.put("texture_mutation_allowed", true);
        assertTrue(assertThrows(IllegalArgumentException.class, () -> state.admit(authority, NOW))
            .getMessage().contains("texture_mutation_allowed"));
    }

    @Test
    void dropsTheProjectionAtExpiryAndReset() {
        RealtimeTexturePackDebugRenderState state = new RealtimeTexturePackDebugRenderState();
        state.admit(projection(1, HASH_A, "rtp_source_frame:hybrid:7"), NOW);
        assertTrue(state.renderFrame(1L, NOW + 3_999).isPresent());
        assertTrue(state.renderFrame(2L, NOW + 4_000).isEmpty());
        assertFalse(state.active());
        state.admit(projection(1, HASH_A, "rtp_source_frame:hybrid:7"), NOW);
        state.reset();
        assertTrue(state.renderFrame(3L, NOW).isEmpty());
    }

    @Test
    void matchesTheLocallyTargetedBlockForTheVisibleHud() {
        RealtimeTexturePackDebugRenderState state = new RealtimeTexturePackDebugRenderState();
        state.admit(projection(1, HASH_A, "rtp_source_frame:hybrid:7"), NOW);
        state.matchTargetBlock(
            "minecraft:overworld", 10, 64, 12, "minecraft:stone", "axis:none", NOW
        );
        var hud = state.hudSnapshot(NOW).orElseThrow();
        assertEquals(1, hud.targetedVariationSlot());
        assertEquals("terrain", hud.targetedMaterialFamily());
        state.matchTargetBlock(
            "minecraft:the_nether", 10, 64, 12, "minecraft:stone", "axis:none", NOW
        );
        assertNull(state.hudSnapshot(NOW).orElseThrow().targetedVariationSlot());
    }

    private static Map<String, Object> projection(int revision, String projectionHash, String frameId) {
        Map<String, Object> wire = new LinkedHashMap<>();
        wire.put("schema", RealtimeTexturePackDebugRenderState.SCHEMA);
        wire.put("projection_id", "rtp_hybrid_projection:test:" + revision);
        wire.put("projection_hash", projectionHash);
        wire.put("source_binding_id", "rtp_source_binding:minecraft:hybrid");
        wire.put("source_binding_revision", 1);
        wire.put("capture_session_id", "rtp_capture_session:hybrid");
        wire.put("source_frame_id", frameId);
        wire.put("scene_capsule_id", "rtp_scene_capsule:test:" + revision);
        wire.put("scene_capsule_hash", HASH_C);
        wire.put("visual_treatment_revision_id", "rtp_visual_treatment:test:" + revision);
        wire.put("visual_treatment_revision", revision);
        wire.put("treatment_hash", HASH_B);
        wire.put("prompt_revision_id", "rtp_prompt_revision:test:" + revision);
        wire.put("overlay_prompt_hash", HASH_A);
        wire.put("shader_parameter_hash", HASH_B);
        wire.put("dynamic_material_prompt_hash", HASH_C);
        wire.put("material_variation_policy_id", "realtime_texture_pack.material_instance_variation.v1");
        wire.put("style_family_id", "style:dreamlike-forest");
        wire.put("world_id", "minecraft:local:hybrid");
        wire.put("dimension_id", "minecraft:overworld");
        wire.put("variation_slot_count", 16);
        List<Map<String, Object>> instances = new ArrayList<>();
        instances.add(instance(HASH_GOLDEN, 2_690_567_025L, 1));
        instances.add(instance(HASH_B, 202L, 2));
        instances.add(instance(HASH_C, 303L, 3));
        wire.put("material_instances", instances);
        wire.put("created_at", "2026-08-30T12:00:02.000Z");
        wire.put("expires_at", "2026-08-30T12:00:06.000Z");
        wire.put("debug_only", true);
        wire.put("texture_mutation_allowed", false);
        wire.put("provider_request_allowed", false);
        wire.put("prompt_body_included", false);
        wire.put("block_identity_input_included", false);
        wire.put("presentation_only", true);
        wire.put("environment_action_authority", false);
        wire.put("world_mutation_authority", false);
        wire.put("assistant_answer", false);
        wire.put("terminal_eligible", false);
        wire.put("raw_content_included", false);
        return wire;
    }

    private static Map<String, Object> instance(String identity, long seed, int slot) {
        return Map.of(
            "material_family", "terrain",
            "instance_identity_hash", identity,
            "variation_seed", seed,
            "variation_slot", slot
        );
    }
}
