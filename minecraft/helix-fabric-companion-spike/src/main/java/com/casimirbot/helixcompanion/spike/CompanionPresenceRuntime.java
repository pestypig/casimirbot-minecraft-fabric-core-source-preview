package com.casimirbot.helixcompanion.spike;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

/**
 * Private C0/A0 fixture for one visible companion incarnation. It owns no
 * model loop and exposes no command, catalog, or public action surface.
 */
public final class CompanionPresenceRuntime {
    private final Profile profile;
    private final SpikeCompanionEntity actor;
    private final ServerLevel level;
    private final String incarnationId;
    private final String connectorEpoch;
    private final long presenceExpiresAtTick;
    private final Set<ChunkPos> forcedChunks = new LinkedHashSet<>();
    private final Set<String> pendingTasks = new LinkedHashSet<>();
    private String actorLeaseId;
    private String effectLeaseId;
    private long observationRevision;
    private boolean active;
    private boolean controlsAsserted;
    private boolean cleaned;
    private CleanupReceipt cleanupReceipt;

    public CompanionPresenceRuntime(
        Profile profile,
        SpikeCompanionEntity actor,
        ServerLevel level,
        String incarnationId,
        String connectorEpoch,
        long presenceExpiresAtTick
    ) {
        this.profile = Objects.requireNonNull(profile);
        this.actor = Objects.requireNonNull(actor);
        this.level = Objects.requireNonNull(level);
        this.incarnationId = requireText(incarnationId, "incarnationId");
        this.connectorEpoch = requireText(connectorEpoch, "connectorEpoch");
        if (presenceExpiresAtTick <= level.getGameTime()) {
            throw new PresenceException("presence_expiry_invalid");
        }
        this.presenceExpiresAtTick = presenceExpiresAtTick;
        this.observationRevision = 1L;
    }

    public void admit(String actorLeaseId, String effectLeaseId) {
        ensureNotCleaned();
        if (active) {
            throw new PresenceException("presence_already_active");
        }
        this.actorLeaseId = requireText(actorLeaseId, "actorLeaseId");
        this.effectLeaseId = requireText(effectLeaseId, "effectLeaseId");
        this.active = true;
        this.observationRevision++;
    }

    public boolean startNavigation(double x, double y, double z, double speed) {
        ensureActive();
        boolean started = actor.getNavigation().moveTo(x, y, z, speed);
        controlsAsserted = started;
        return started;
    }

    public void stopNavigation() {
        ensureActive();
        actor.getNavigation().stop();
        controlsAsserted = false;
    }

    public void claimChunk(ChunkPos chunkPos) {
        ensureActive();
        if (forcedChunks.add(chunkPos)) {
            level.setChunkForced(chunkPos.x, chunkPos.z, true);
        }
    }

    public void queueTask(String taskId) {
        ensureActive();
        pendingTasks.add(requireText(taskId, "taskId"));
    }

    public void releaseTask(String taskId) {
        ensureActive();
        pendingTasks.remove(requireText(taskId, "taskId"));
    }

    public ActionLease issueAction(String actionId) {
        return issueAction(actionId, presenceExpiresAtTick);
    }

    public ActionLease issueAction(String actionId, long expiresAtTick) {
        ensureActive();
        if (expiresAtTick <= level.getGameTime() || expiresAtTick > presenceExpiresAtTick) {
            throw new PresenceException("companion_action_expiry_invalid");
        }
        return new ActionLease(
            requireText(actionId, "actionId"),
            profile.companionId(),
            actor.getUUID().toString(),
            incarnationId,
            connectorEpoch,
            actorLeaseId,
            effectLeaseId,
            observationRevision,
            expiresAtTick
        );
    }

    public ActionCheck checkAction(ActionLease action, long currentTick) {
        if (!active || cleaned || actor.isRemoved()) {
            return new ActionCheck(false, "companion_not_active");
        }
        boolean identityMatches =
            action.companionId().equals(profile.companionId())
                && action.actorEntityId().equals(actor.getUUID().toString())
                && action.actorIncarnationId().equals(incarnationId)
                && action.connectorEpoch().equals(connectorEpoch)
                && action.actorLeaseId().equals(actorLeaseId)
                && action.effectLeaseId().equals(effectLeaseId)
                && action.observationRevision() == observationRevision;
        if (!identityMatches) {
            return new ActionCheck(false, "companion_action_identity_stale");
        }
        if (currentTick >= action.expiresAtTick()) {
            return new ActionCheck(false, "companion_action_expired");
        }
        return new ActionCheck(true, "current");
    }

    public CleanupReceipt cleanup(String cleanupId, String reason, boolean discardActor) {
        if (cleaned) {
            return cleanupReceipt;
        }
        int releasedChunkCount = forcedChunks.size();
        int canceledTaskCount = pendingTasks.size();
        boolean navigationWasInProgress = actor.getNavigation().isInProgress();
        actor.getNavigation().stop();
        for (ChunkPos chunkPos : forcedChunks) {
            level.setChunkForced(chunkPos.x, chunkPos.z, false);
        }
        forcedChunks.clear();
        pendingTasks.clear();
        controlsAsserted = false;
        active = false;
        String releasedActorLeaseId = actorLeaseId;
        String releasedEffectLeaseId = effectLeaseId;
        actorLeaseId = null;
        effectLeaseId = null;
        observationRevision++;
        if (discardActor) {
            actor.discard();
        }
        cleaned = true;
        cleanupReceipt = new CleanupReceipt(
            requireText(cleanupId, "cleanupId"),
            requireText(reason, "reason"),
            profile.companionId(),
            actor.getUUID().toString(),
            incarnationId,
            releasedActorLeaseId,
            releasedEffectLeaseId,
            releasedChunkCount,
            canceledTaskCount,
            navigationWasInProgress,
            actor.getNavigation().isDone(),
            forcedChunks.isEmpty(),
            pendingTasks.isEmpty(),
            !controlsAsserted,
            discardActor && actor.isRemoved(),
            0,
            0
        );
        return cleanupReceipt;
    }

    public CleanupReceipt enforceExpiry(String cleanupId, long currentTick) {
        if (currentTick < presenceExpiresAtTick) {
            return null;
        }
        return cleanup(cleanupId, "lease_expired", true);
    }

    public boolean active() {
        return active;
    }

    public boolean controlsAsserted() {
        return controlsAsserted;
    }

    public int forcedChunkCount() {
        return forcedChunks.size();
    }

    public int pendingTaskCount() {
        return pendingTasks.size();
    }

    public String incarnationId() {
        return incarnationId;
    }

    public long observationRevision() {
        return observationRevision;
    }

    public SpikeCompanionEntity actor() {
        return actor;
    }

    public static CompoundTag snapshotProfile(Profile profile, String previousIncarnationId) {
        CompoundTag tag = new CompoundTag();
        tag.putString("schema", "helix.minecraft_companion.persistence.v1");
        tag.putString("companion_id", profile.companionId());
        tag.putString("owner_account_id", profile.ownerAccountId());
        tag.putString("authority_subject_id", profile.authoritySubjectId());
        tag.putString("beneficiary_subject_id", profile.beneficiarySubjectId());
        tag.putString("controller_profile_id", profile.controllerProfileId());
        tag.putString("controller_artifact_hash", profile.controllerArtifactHash());
        tag.putString("previous_actor_incarnation_id", previousIncarnationId);
        tag.putBoolean("active_incarnation_persisted", false);
        tag.putBoolean("actor_lease_persisted", false);
        tag.putBoolean("effect_lease_persisted", false);
        tag.putBoolean("resource_claims_persisted", false);
        tag.putBoolean("pending_tasks_persisted", false);
        tag.putBoolean("credentials_persisted", false);
        return tag;
    }

    public static RestoredProfile restoreProfile(CompoundTag tag) {
        if (!tag.getStringOr("schema", "").equals("helix.minecraft_companion.persistence.v1")) {
            throw new PresenceException("persistence_schema_invalid");
        }
        if (
            tag.getBooleanOr("active_incarnation_persisted", true)
                || tag.getBooleanOr("actor_lease_persisted", true)
                || tag.getBooleanOr("effect_lease_persisted", true)
                || tag.getBooleanOr("resource_claims_persisted", true)
                || tag.getBooleanOr("pending_tasks_persisted", true)
                || tag.getBooleanOr("credentials_persisted", true)
        ) {
            throw new PresenceException("persistence_authority_leak");
        }
        return new RestoredProfile(
            new Profile(
                tag.getStringOr("companion_id", ""),
                tag.getStringOr("owner_account_id", ""),
                tag.getStringOr("authority_subject_id", ""),
                tag.getStringOr("beneficiary_subject_id", ""),
                tag.getStringOr("controller_profile_id", ""),
                tag.getStringOr("controller_artifact_hash", "")
            ),
            requireText(
                tag.getStringOr("previous_actor_incarnation_id", ""),
                "previousActorIncarnationId"
            )
        );
    }

    private void ensureActive() {
        ensureNotCleaned();
        if (!active || actorLeaseId == null || effectLeaseId == null || actor.isRemoved()) {
            throw new PresenceException("companion_not_active");
        }
    }

    private void ensureNotCleaned() {
        if (cleaned) {
            throw new PresenceException("companion_already_cleaned");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new PresenceException(field + "_required");
        }
        return value;
    }

    public record Profile(
        String companionId,
        String ownerAccountId,
        String authoritySubjectId,
        String beneficiarySubjectId,
        String controllerProfileId,
        String controllerArtifactHash
    ) {
        public Profile {
            requireText(companionId, "companionId");
            requireText(ownerAccountId, "ownerAccountId");
            requireText(authoritySubjectId, "authoritySubjectId");
            requireText(beneficiarySubjectId, "beneficiarySubjectId");
            requireText(controllerProfileId, "controllerProfileId");
            requireText(controllerArtifactHash, "controllerArtifactHash");
        }
    }

    public record RestoredProfile(Profile profile, String previousIncarnationId) {}

    public record ActionLease(
        String actionId,
        String companionId,
        String actorEntityId,
        String actorIncarnationId,
        String connectorEpoch,
        String actorLeaseId,
        String effectLeaseId,
        long observationRevision,
        long expiresAtTick
    ) {}

    public record ActionCheck(boolean current, String reason) {}

    public record CleanupReceipt(
        String cleanupId,
        String reason,
        String companionId,
        String actorEntityId,
        String actorIncarnationId,
        String releasedActorLeaseId,
        String releasedEffectLeaseId,
        int releasedChunkCount,
        int canceledTaskCount,
        boolean navigationWasInProgress,
        boolean navigationReleased,
        boolean chunksReleased,
        boolean tasksReleased,
        boolean controlsReleased,
        boolean actorRemoved,
        int lateEffectCount,
        int duplicateEffectCount
    ) {}

    public static final class PresenceException extends RuntimeException {
        private final String code;

        public PresenceException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
