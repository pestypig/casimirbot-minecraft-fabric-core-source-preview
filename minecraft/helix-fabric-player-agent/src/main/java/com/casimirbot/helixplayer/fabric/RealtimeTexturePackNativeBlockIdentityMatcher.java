package com.casimirbot.helixplayer.fabric;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Optional;
import java.util.regex.Pattern;

/** Reproduces the provider-neutral TypeScript material-instance identity. */
final class RealtimeTexturePackNativeBlockIdentityMatcher {
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-zA-Z0-9:._/-]{1,320}$");

    record Derivation(String instanceIdentityHash, long variationSeed, int variationSlot) {}

    record Match(
        String materialFamily,
        String instanceIdentityHash,
        long variationSeed,
        int variationSlot
    ) {}

    static Derivation derive(
        String worldId,
        String dimensionId,
        int x,
        int y,
        int z,
        String blockType,
        String blockState,
        String styleFamilyId,
        String policyId,
        int slotCount
    ) {
        requireIdentifier(worldId, "world_id");
        requireIdentifier(dimensionId, "dimension_id");
        requireIdentifier(blockType, "block_type");
        requireIdentifier(blockState, "block_state");
        requireIdentifier(styleFamilyId, "style_family_id");
        requireIdentifier(policyId, "policy_id");
        if (slotCount < 2 || slotCount > 256) throw invalid("slot_count");
        String identityJson = "{\"block_position\":{\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z +
            "},\"block_state\":\"" + blockState + "\",\"block_type\":\"" + blockType +
            "\",\"dimension_id\":\"" + dimensionId + "\",\"world_id\":\"" + worldId + "\"}";
        String identity = sha256(identityJson);
        String seedJson = "{\"instance_identity_hash\":\"" + identity + "\",\"policy_id\":\"" + policyId +
            "\",\"style_family_id\":\"" + styleFamilyId + "\"}";
        String seedHash = sha256(seedJson);
        long seed = Long.parseUnsignedLong(seedHash.substring(7, 15), 16);
        return new Derivation(identity, seed, (int) (seed % slotCount));
    }

    static Optional<Match> match(
        RealtimeTexturePackDebugRenderState.Projection projection,
        int x,
        int y,
        int z,
        String blockType,
        String blockState
    ) {
        Derivation derived = derive(
            projection.worldId(), projection.dimensionId(), x, y, z,
            blockType, blockState, projection.styleFamilyId(),
            projection.materialVariationPolicyId(), projection.variationSlotCount()
        );
        return projection.materialInstances().stream()
            .filter(instance -> instance.instanceIdentityHash().equals(derived.instanceIdentityHash()))
            .findFirst()
            .map(instance -> {
                if (instance.variationSeed() != derived.variationSeed() ||
                    instance.variationSlot() != derived.variationSlot()) {
                    throw new IllegalArgumentException("realtime_texture_pack_native_block_variation_mismatch");
                }
                return new Match(instance.materialFamily(), derived.instanceIdentityHash(),
                    derived.variationSeed(), derived.variationSlot());
            });
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable.", error);
        }
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) throw invalid(field);
    }

    private static IllegalArgumentException invalid(String field) {
        return new IllegalArgumentException("realtime_texture_pack_native_block_identity_invalid:" + field);
    }
}
