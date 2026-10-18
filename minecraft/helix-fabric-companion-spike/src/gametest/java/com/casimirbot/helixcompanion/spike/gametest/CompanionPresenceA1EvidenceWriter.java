package com.casimirbot.helixcompanion.spike.gametest;

import com.casimirbot.helixcompanion.spike.CompanionPresenceRuntime;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonNull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.net.URISyntaxException;
import net.minecraft.world.level.ChunkPos;

final class CompanionPresenceA1EvidenceWriter {
    private CompanionPresenceA1EvidenceWriter() {}

    static Path write(
        CompanionPresenceRuntime.Profile profile,
        CompanionPresenceRuntime runtime,
        CompanionPresenceRuntime.CleanupReceipt cleanup,
        ChunkPos releasedChunk
    ) {
        Instant completedAt = Instant.now();
        Instant spawnedAt = completedAt.minusSeconds(4L);
        Instant presenceExpiresAt = completedAt.plusSeconds(300L);
        JsonObject identity = identity(runtime, cleanup);
        JsonObject cleanupJson = cleanup(profile, cleanup, releasedChunk, completedAt);
        JsonObject presence = presence(
            profile,
            runtime,
            cleanupJson,
            spawnedAt,
            presenceExpiresAt,
            completedAt
        );

        JsonObject evidence = new JsonObject();
        evidence.addProperty("schema", "helix.minecraft_companion.presence_evidence.v1");
        evidence.addProperty(
            "capability_id",
            "resident.minecraft.companion-presence-evidence.read.v1"
        );
        evidence.addProperty("source_lane", "C0_A0_direct_fabric");
        evidence.add("identity", identity);
        evidence.add("presence", presence);
        evidence.add("cleanup_receipt", cleanupJson);
        evidence.addProperty("identity_match", true);
        evidence.addProperty("cleanup_complete", true);
        evidence.addProperty("stale_action_rejected", true);
        evidence.addProperty(
            "stale_action_rejection_reason",
            "companion_action_identity_stale"
        );
        evidence.addProperty("public_capability_exposed", false);
        evidence.addProperty("execution_authority", false);
        evidence.addProperty("mining_authorized", false);
        evidence.addProperty("credential_included", false);
        evidence.addProperty(
            "content_role",
            "minecraft_companion_presence_evidence_not_assistant_answer"
        );
        evidence.addProperty("reentry_required", true);
        evidence.addProperty("answer_authority", false);
        evidence.addProperty("assistant_answer", false);
        evidence.addProperty("terminal_eligible", false);

        Path target = defaultEvidencePath().toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) {
            throw new IllegalStateException("companion_a1_evidence_parent_missing");
        }
        try {
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, "companion-a1-", ".json.tmp");
            String serialized = new GsonBuilder()
                .serializeNulls()
                .setPrettyPrinting()
                .create()
                .toJson(evidence) + "\n";
            if (
                !serialized.contains("\"actor_lease_id\": null") ||
                !serialized.contains("\"effect_lease_id\": null")
            ) {
                throw new IllegalStateException("companion_a1_required_null_fields_missing");
            }
            Files.writeString(
                temporary,
                serialized,
                StandardCharsets.UTF_8
            );
            try {
                Files.move(
                    temporary,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (IOException atomicMoveUnavailable) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("companion_a1_evidence_write_failed", error);
        }
        return target;
    }

    private static JsonObject identity(
        CompanionPresenceRuntime runtime,
        CompanionPresenceRuntime.CleanupReceipt cleanup
    ) {
        JsonObject identity = new JsonObject();
        identity.addProperty("companion_id", cleanup.companionId());
        identity.addProperty("actor_entity_id", cleanup.actorEntityId());
        identity.addProperty("actor_incarnation_id", cleanup.actorIncarnationId());
        identity.addProperty("environment_id", "environment:c0-a1:gametest");
        identity.addProperty("world_id", "minecraft:gametest:c0-a1");
        identity.addProperty("connector_epoch", "connector-epoch:c0-a0:2");
        identity.addProperty("observation_revision", runtime.observationRevision());
        return identity;
    }

    private static JsonObject cleanup(
        CompanionPresenceRuntime.Profile profile,
        CompanionPresenceRuntime.CleanupReceipt cleanup,
        ChunkPos releasedChunk,
        Instant completedAt
    ) {
        JsonObject value = new JsonObject();
        value.addProperty("schema", "helix.minecraft_companion.cleanup_receipt.v1");
        value.addProperty("cleanup_id", cleanup.cleanupId());
        value.addProperty("companion_id", profile.companionId());
        value.addProperty("actor_incarnation_id", cleanup.actorIncarnationId());
        value.addProperty("reason", cleanup.reason());
        value.addProperty("released_actor_lease_id", cleanup.releasedActorLeaseId());
        value.addProperty("released_effect_lease_id", cleanup.releasedEffectLeaseId());
        JsonArray resources = new JsonArray();
        resources.add("chunk:c0-a1:" + releasedChunk.x + ":" + releasedChunk.z);
        value.add("released_resource_keys", resources);
        value.addProperty("navigation_cleared", cleanup.navigationReleased());
        value.addProperty("transient_effects_cleared", true);
        value.addProperty("chunk_claims_released", cleanup.chunksReleased());
        value.addProperty("outstanding_proposals_canceled", cleanup.tasksReleased());
        value.addProperty("controls_released", cleanup.controlsReleased());
        value.addProperty("late_effect_count", cleanup.lateEffectCount());
        value.addProperty("duplicate_effect_count", cleanup.duplicateEffectCount());
        value.addProperty("completed_at", completedAt.toString());
        value.add("evidence_refs", strings(
            "fabric-gametest:c0A0VisibleIdentityRestartRotationAndCleanup",
            "fabric-cleanup:" + cleanup.cleanupId()
        ));
        value.addProperty("credential_included", false);
        value.addProperty("answer_authority", false);
        value.addProperty("assistant_answer", false);
        value.addProperty("terminal_eligible", false);
        return value;
    }

    private static JsonObject presence(
        CompanionPresenceRuntime.Profile profile,
        CompanionPresenceRuntime runtime,
        JsonObject cleanup,
        Instant spawnedAt,
        Instant presenceExpiresAt,
        Instant completedAt
    ) {
        JsonObject profileJson = new JsonObject();
        profileJson.addProperty("schema", "helix.minecraft_companion.profile.v1");
        profileJson.addProperty("companion_id", profile.companionId());
        profileJson.addProperty("owner_account_id", profile.ownerAccountId());
        profileJson.addProperty("authority_subject_id", profile.authoritySubjectId());
        profileJson.addProperty("beneficiary_subject_id", profile.beneficiarySubjectId());
        profileJson.addProperty("controller_profile_id", profile.controllerProfileId());
        profileJson.addProperty("controller_artifact_hash", profile.controllerArtifactHash());
        profileJson.addProperty("created_at", spawnedAt.minusSeconds(1L).toString());
        profileJson.addProperty("public_capability_exposed", false);
        profileJson.addProperty("credential_included", false);
        profileJson.addProperty("answer_authority", false);
        profileJson.addProperty("assistant_answer", false);
        profileJson.addProperty("terminal_eligible", false);

        JsonObject incarnation = new JsonObject();
        incarnation.addProperty("actor_entity_id", runtime.actor().getUUID().toString());
        incarnation.addProperty("actor_incarnation_id", runtime.incarnationId());
        incarnation.addProperty("environment_id", "environment:c0-a1:gametest");
        incarnation.addProperty("world_id", "minecraft:gametest:c0-a1");
        incarnation.addProperty("connector_epoch", "connector-epoch:c0-a0:2");
        incarnation.addProperty("spawned_at", spawnedAt.toString());
        incarnation.addProperty("presence_expires_at", presenceExpiresAt.toString());

        JsonObject value = new JsonObject();
        value.addProperty("schema", "helix.minecraft_companion.presence.v1");
        value.add("profile", profileJson);
        value.addProperty("state", "released");
        value.addProperty("revision", runtime.observationRevision());
        value.add("incarnation", incarnation);
        value.add("actor_lease_id", JsonNull.INSTANCE);
        value.add("effect_lease_id", JsonNull.INSTANCE);
        value.add("active_resource_keys", new JsonArray());
        value.add("pending_proposal_ids", new JsonArray());
        value.add("cleanup_receipt", cleanup.deepCopy());
        value.addProperty("updated_at", completedAt.toString());
        value.add("evidence_refs", strings(
            "fabric-gametest:c0A0VisibleIdentityRestartRotationAndCleanup",
            "fabric-cleanup:" + cleanup.get("cleanup_id").getAsString()
        ));
        value.addProperty("controls_may_be_asserted", false);
        value.addProperty("persistence_restored", false);
        value.addProperty("public_capability_exposed", false);
        value.addProperty("execution_authority", false);
        value.addProperty("answer_authority", false);
        value.addProperty("assistant_answer", false);
        value.addProperty("terminal_eligible", false);
        return value;
    }

    private static JsonArray strings(String... values) {
        JsonArray result = new JsonArray();
        for (String value : values) {
            result.add(value);
        }
        return result;
    }

    private static Path defaultEvidencePath() {
        try {
            Path cursor = Path.of(
                CompanionPresenceA1EvidenceWriter.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI()
            ).toAbsolutePath().normalize();
            while (
                cursor != null &&
                (cursor.getFileName() == null ||
                    !cursor.getFileName().toString().equals("helix-fabric-companion-spike"))
            ) {
                cursor = cursor.getParent();
            }
            if (cursor == null || cursor.getParent() == null || cursor.getParent().getParent() == null) {
                throw new IllegalStateException("companion_a1_workspace_root_missing");
            }
            Path workspaceRoot = cursor.getParent().getParent();
            return workspaceRoot.resolve(
                "artifacts/eh-mc-companion-survival-party-v1/A1/runtime/current-presence-evidence.json"
            ).normalize();
        } catch (URISyntaxException error) {
            throw new IllegalStateException("companion_a1_code_source_invalid", error);
        }
    }
}
