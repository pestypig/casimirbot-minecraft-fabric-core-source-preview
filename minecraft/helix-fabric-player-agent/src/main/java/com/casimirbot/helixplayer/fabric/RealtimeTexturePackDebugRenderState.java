package com.casimirbot.helixplayer.fabric;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Render-thread seam for the Realtime Texture Pack hybrid projection.
 *
 * <p>This class deliberately cannot upload or replace a texture. It admits a
 * short-lived, authority-free envelope and exposes only an immutable render
 * snapshot. A later Fabric renderer may match the opaque instance hashes
 * against identities computed from locally visible blocks.</p>
 */
final class RealtimeTexturePackDebugRenderState {
    static final String SCHEMA = "helix.realtime_texture_pack_fabric_debug_projection.v1";
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-zA-Z0-9:._/-]{1,320}$");
    private static final Pattern SHA256 = Pattern.compile("^sha256:[a-f0-9]{64}$");
    private static final int MAX_INSTANCES = 256;
    private static final Set<String> PROJECTION_KEYS = Set.of(
        "schema", "projection_id", "projection_hash", "source_binding_id",
        "source_binding_revision", "capture_session_id", "source_frame_id",
        "scene_capsule_id", "scene_capsule_hash", "visual_treatment_revision_id",
        "visual_treatment_revision", "treatment_hash", "prompt_revision_id",
        "overlay_prompt_hash", "shader_parameter_hash", "dynamic_material_prompt_hash",
        "material_variation_policy_id", "style_family_id", "material_instances",
        "world_id", "dimension_id", "variation_slot_count",
        "created_at", "expires_at", "debug_only", "texture_mutation_allowed",
        "provider_request_allowed", "prompt_body_included", "block_identity_input_included",
        "presentation_only", "environment_action_authority", "world_mutation_authority",
        "assistant_answer", "terminal_eligible", "raw_content_included"
    );
    private static final Set<String> INSTANCE_KEYS = Set.of(
        "material_family", "instance_identity_hash", "variation_seed", "variation_slot"
    );

    record MaterialInstance(String materialFamily, String instanceIdentityHash, long variationSeed, int variationSlot) {}

    record Projection(
        String projectionId,
        String projectionHash,
        String sourceBindingId,
        int sourceBindingRevision,
        String captureSessionId,
        String sourceFrameId,
        String sceneCapsuleId,
        String sceneCapsuleHash,
        String visualTreatmentRevisionId,
        int visualTreatmentRevision,
        String treatmentHash,
        String promptRevisionId,
        String overlayPromptHash,
        String shaderParameterHash,
        String dynamicMaterialPromptHash,
        String materialVariationPolicyId,
        String styleFamilyId,
        String worldId,
        String dimensionId,
        int variationSlotCount,
        List<MaterialInstance> materialInstances,
        long createdAtMillis,
        long expiresAtMillis
    ) {}

    record RenderSnapshot(
        String projectionId,
        String projectionHash,
        String sourceFrameId,
        String sceneCapsuleHash,
        String treatmentHash,
        String shaderParameterHash,
        String dynamicMaterialPromptHash,
        int materialInstanceCount,
        int distinctVariationSlotCount,
        long renderFrameNanos,
        boolean textureMutationPerformed,
        boolean providerRequestPerformed
    ) {}

    record HudSnapshot(
        String sourceFrameId,
        int materialInstanceCount,
        int distinctVariationSlotCount,
        Integer targetedVariationSlot,
        String targetedMaterialFamily
    ) {}

    private Projection current;
    private RealtimeTexturePackNativeBlockIdentityMatcher.Match targetedMatch;

    synchronized void admit(Map<String, Object> wire, long nowMillis) {
        Projection next = parse(wire);
        if (nowMillis < next.createdAtMillis() || nowMillis >= next.expiresAtMillis()) {
            throw new IllegalArgumentException("realtime_texture_pack_fabric_projection_stale");
        }
        Projection prior = current;
        if (prior != null) {
            if (!prior.sourceBindingId().equals(next.sourceBindingId()) ||
                prior.sourceBindingRevision() != next.sourceBindingRevision() ||
                !prior.captureSessionId().equals(next.captureSessionId())) {
                throw new IllegalArgumentException("realtime_texture_pack_fabric_projection_binding_mismatch");
            }
            if (next.visualTreatmentRevision() < prior.visualTreatmentRevision()) {
                throw new IllegalArgumentException("realtime_texture_pack_fabric_projection_revision_regressed");
            }
            if (next.visualTreatmentRevision() == prior.visualTreatmentRevision()) {
                if (next.projectionHash().equals(prior.projectionHash())) return;
                throw new IllegalArgumentException("realtime_texture_pack_fabric_projection_revision_conflict");
            }
        }
        current = next;
        targetedMatch = null;
    }

    synchronized Optional<RenderSnapshot> renderFrame(long frameNanos, long nowMillis) {
        Projection active = current;
        if (active == null) return Optional.empty();
        if (nowMillis >= active.expiresAtMillis()) {
            current = null;
            targetedMatch = null;
            return Optional.empty();
        }
        int distinctSlots = new HashSet<>(
            active.materialInstances().stream().map(MaterialInstance::variationSlot).toList()
        ).size();
        return Optional.of(new RenderSnapshot(
            active.projectionId(), active.projectionHash(), active.sourceFrameId(),
            active.sceneCapsuleHash(), active.treatmentHash(), active.shaderParameterHash(),
            active.dynamicMaterialPromptHash(), active.materialInstances().size(),
            distinctSlots, frameNanos, false, false
        ));
    }

    synchronized void reset() {
        current = null;
        targetedMatch = null;
    }

    synchronized boolean active() {
        return current != null;
    }

    synchronized void matchTargetBlock(
        String actualDimensionId,
        int x,
        int y,
        int z,
        String blockType,
        String blockState,
        long nowMillis
    ) {
        Projection active = current;
        if (active == null || nowMillis >= active.expiresAtMillis()) {
            current = null;
            targetedMatch = null;
            return;
        }
        if (!active.dimensionId().equals(actualDimensionId)) {
            targetedMatch = null;
            return;
        }
        targetedMatch = RealtimeTexturePackNativeBlockIdentityMatcher
            .match(active, x, y, z, blockType, blockState)
            .orElse(null);
    }

    synchronized void clearTargetMatch() {
        targetedMatch = null;
    }

    synchronized Optional<HudSnapshot> hudSnapshot(long nowMillis) {
        Projection active = current;
        if (active == null || nowMillis >= active.expiresAtMillis()) {
            current = null;
            targetedMatch = null;
            return Optional.empty();
        }
        int distinctSlots = new HashSet<>(
            active.materialInstances().stream().map(MaterialInstance::variationSlot).toList()
        ).size();
        return Optional.of(new HudSnapshot(
            active.sourceFrameId(), active.materialInstances().size(), distinctSlots,
            targetedMatch == null ? null : targetedMatch.variationSlot(),
            targetedMatch == null ? "none" : targetedMatch.materialFamily()
        ));
    }

    private static Projection parse(Map<String, Object> wire) {
        if (!wire.keySet().equals(PROJECTION_KEYS)) throw invalid("projection_keys");
        requireText(wire, "schema", SCHEMA, false);
        requireBoolean(wire, "debug_only", true);
        requireBoolean(wire, "texture_mutation_allowed", false);
        requireBoolean(wire, "provider_request_allowed", false);
        requireBoolean(wire, "prompt_body_included", false);
        requireBoolean(wire, "block_identity_input_included", false);
        requireBoolean(wire, "presentation_only", true);
        requireBoolean(wire, "environment_action_authority", false);
        requireBoolean(wire, "world_mutation_authority", false);
        requireBoolean(wire, "assistant_answer", false);
        requireBoolean(wire, "terminal_eligible", false);
        requireBoolean(wire, "raw_content_included", false);
        List<?> instances = requireList(wire, "material_instances");
        if (instances.isEmpty() || instances.size() > MAX_INSTANCES) {
            throw invalid("material_instances");
        }
        List<MaterialInstance> parsed = new ArrayList<>();
        Set<String> identities = new HashSet<>();
        for (Object value : instances) {
            if (!(value instanceof Map<?, ?> raw)) throw invalid("material_instances");
            @SuppressWarnings("unchecked") Map<String, Object> instance = (Map<String, Object>) raw;
            if (!instance.keySet().equals(INSTANCE_KEYS)) throw invalid("material_instance_keys");
            String identity = requireHash(instance, "instance_identity_hash");
            if (!identities.add(identity)) throw invalid("material_instances_duplicate");
            long seed = requireLong(instance, "variation_seed", 0, 0xffff_ffffL);
            int slot = (int) requireLong(instance, "variation_slot", 0, 255);
            parsed.add(new MaterialInstance(requireIdentifier(instance, "material_family"), identity, seed, slot));
        }
        long created = timestamp(wire, "created_at");
        long expires = timestamp(wire, "expires_at");
        if (expires <= created) throw invalid("expires_at");
        return new Projection(
            requireIdentifier(wire, "projection_id"), requireHash(wire, "projection_hash"),
            requireIdentifier(wire, "source_binding_id"), (int) requireLong(wire, "source_binding_revision", 1, Integer.MAX_VALUE),
            requireIdentifier(wire, "capture_session_id"), requireIdentifier(wire, "source_frame_id"),
            requireIdentifier(wire, "scene_capsule_id"), requireHash(wire, "scene_capsule_hash"),
            requireIdentifier(wire, "visual_treatment_revision_id"), (int) requireLong(wire, "visual_treatment_revision", 1, Integer.MAX_VALUE),
            requireHash(wire, "treatment_hash"), requireIdentifier(wire, "prompt_revision_id"),
            requireHash(wire, "overlay_prompt_hash"), requireHash(wire, "shader_parameter_hash"),
            requireHash(wire, "dynamic_material_prompt_hash"), requireIdentifier(wire, "material_variation_policy_id"),
            requireIdentifier(wire, "style_family_id"), requireIdentifier(wire, "world_id"),
            requireIdentifier(wire, "dimension_id"), (int) requireLong(wire, "variation_slot_count", 2, 256),
            List.copyOf(parsed), created, expires
        );
    }

    private static String requireIdentifier(Map<String, Object> map, String key) {
        String value = requireText(map, key, null, true);
        if (!IDENTIFIER.matcher(value).matches()) throw invalid(key);
        return value;
    }

    private static String requireHash(Map<String, Object> map, String key) {
        String value = requireText(map, key, null, true);
        if (!SHA256.matcher(value).matches()) throw invalid(key);
        return value;
    }

    private static String requireText(Map<String, Object> map, String key, String exact, boolean nonBlank) {
        Object raw = map.get(key);
        if (!(raw instanceof String value) || (nonBlank && value.isBlank()) || (exact != null && !exact.equals(value))) throw invalid(key);
        return value;
    }

    private static void requireBoolean(Map<String, Object> map, String key, boolean expected) {
        if (!(map.get(key) instanceof Boolean value) || value != expected) throw invalid(key);
    }

    private static List<?> requireList(Map<String, Object> map, String key) {
        if (!(map.get(key) instanceof List<?> value)) throw invalid(key);
        return value;
    }

    private static long requireLong(Map<String, Object> map, String key, long min, long max) {
        if (!(map.get(key) instanceof Number number)) throw invalid(key);
        double raw = number.doubleValue();
        long value = number.longValue();
        if (!Double.isFinite(raw) || raw != value || value < min || value > max) throw invalid(key);
        return value;
    }

    private static long timestamp(Map<String, Object> map, String key) {
        try {
            return Instant.parse(requireText(map, key, null, true)).toEpochMilli();
        } catch (DateTimeParseException error) {
            throw invalid(key);
        }
    }

    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("realtime_texture_pack_fabric_projection_invalid:" + field);
    }
}
