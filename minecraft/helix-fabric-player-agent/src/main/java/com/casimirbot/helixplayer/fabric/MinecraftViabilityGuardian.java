package com.casimirbot.helixplayer.fabric;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minecraft-specific deterministic resident viability controller.
 *
 * <p>The guardian never interprets user intent and never executes an effect.
 * It turns one fresh, revision-bound player observation into a bounded proposal
 * for the trusted local arbiter. Unsupported hazards produce release/escalate
 * or abstention rather than an invented gameplay strategy.</p>
 */
final class MinecraftViabilityGuardian {
    static final String PROFILE_ID = "resident.minecraft.fabric-guardian.v1";
    static final String ARTIFACT_VERSION = "0.3.0";
    private static final int BLOCKED_MOVEMENT_TICKS = 10;

    enum ProposalKind {
        NONE,
        SWIM_UP,
        MONITOR_ADMITTED_RECOVERY,
        RELEASE_AND_ESCALATE,
        ABSTAIN_AND_ESCALATE
    }

    record Profile(
        long leaseExpiresTick,
        int minimumAir,
        double dangerousVerticalVelocity,
        int maximumSwimTicks,
        long maximumObservationAgeTicks
    ) {
        Profile {
            if (leaseExpiresTick < 1) throw new IllegalArgumentException("Guardian lease must expire after it starts.");
            if (minimumAir < 1 || minimumAir > 300) throw new IllegalArgumentException("minimumAir must be 1-300.");
            if (!Double.isFinite(dangerousVerticalVelocity) || dangerousVerticalVelocity >= 0) {
                throw new IllegalArgumentException("dangerousVerticalVelocity must be finite and negative.");
            }
            if (maximumSwimTicks < 1 || maximumSwimTicks > 1_200) {
                throw new IllegalArgumentException("maximumSwimTicks must be 1-1200.");
            }
            if (maximumObservationAgeTicks < 0 || maximumObservationAgeTicks > 20) {
                throw new IllegalArgumentException("maximumObservationAgeTicks must be 0-20.");
            }
        }
    }

    record Observation(
        long revision,
        long observedTick,
        long currentTick,
        boolean connected,
        float health,
        int air,
        int maximumAir,
        boolean submerged,
        boolean swimming,
        boolean inWater,
        boolean onFire,
        boolean inLava,
        boolean onGround,
        boolean horizontalCollision,
        double velocityY,
        boolean predictedSafeLanding,
        boolean unsafeLandingRecoveryActive,
        boolean fireRecoveryActive,
        boolean manualOverride,
        boolean emergencyStop
    ) {
        Observation {
            if (revision < 0 || observedTick < 0 || currentTick < 0) {
                throw new IllegalArgumentException("Guardian observation identity must be nonnegative.");
            }
            if (!Float.isFinite(health) || health < 0) {
                throw new IllegalArgumentException("Guardian health must be finite and nonnegative.");
            }
            if (air < 0 || maximumAir < 1 || air > maximumAir) {
                throw new IllegalArgumentException("Guardian air must be inside the measured maximum.");
            }
            if (!Double.isFinite(velocityY)) {
                throw new IllegalArgumentException("Guardian vertical velocity must be finite.");
            }
        }
    }

    record Decision(
        long decisionSequence,
        long observationRevision,
        ProposalKind proposal,
        String reasonCode,
        boolean controlsMustRelease,
        boolean semanticEscalationRequired,
        Map<String, Object> measurements
    ) {
        Decision {
            if (decisionSequence < 1 || observationRevision < 0) {
                throw new IllegalArgumentException("Guardian decisions require causal identity.");
            }
            if (proposal == null || reasonCode == null || reasonCode.isBlank()) {
                throw new IllegalArgumentException("Guardian decisions require a proposal and reason.");
            }
            measurements = measurements == null ? Map.of() : Map.copyOf(measurements);
        }
    }

    private Profile profile;
    private long lastRevision = -1;
    private long decisionSequence;
    private int swimTicks;
    private int collisionTicks;
    private int priorAir = -1;
    private boolean waterRecoveryActive;
    private boolean breathingRestored;
    private boolean delegatedFallRecovery;
    private boolean delegatedFireRecovery;
    private boolean armed;

    void arm(Profile profile) {
        this.profile = profile;
        lastRevision = -1;
        decisionSequence = 0;
        swimTicks = 0;
        collisionTicks = 0;
        priorAir = -1;
        waterRecoveryActive = false;
        breathingRestored = false;
        delegatedFallRecovery = false;
        delegatedFireRecovery = false;
        armed = true;
    }

    void disarm() {
        armed = false;
        swimTicks = 0;
        collisionTicks = 0;
        priorAir = -1;
        waterRecoveryActive = false;
        breathingRestored = false;
        delegatedFallRecovery = false;
        delegatedFireRecovery = false;
    }

    boolean armed() {
        return armed;
    }

    Decision step(Observation observation) {
        if (!armed || profile == null) return decision(observation, ProposalKind.NONE, "guardian_inactive", false, false);
        if (observation.revision() <= lastRevision) {
            return decision(observation, ProposalKind.ABSTAIN_AND_ESCALATE, "observation_revision_not_monotonic", true, true);
        }
        lastRevision = observation.revision();
        if (observation.currentTick() > profile.leaseExpiresTick()) {
            boolean recoveryWasActive = waterRecoveryActive;
            disarm();
            return decision(
                observation,
                ProposalKind.RELEASE_AND_ESCALATE,
                recoveryWasActive
                    ? "guardian_lease_expired_during_water_recovery"
                    : "guardian_lease_expired",
                true,
                true
            );
        }
        if (observation.currentTick() - observation.observedTick() > profile.maximumObservationAgeTicks()) {
            return decision(observation, ProposalKind.ABSTAIN_AND_ESCALATE, "guardian_observation_stale", true, true);
        }
        if (!observation.connected()) {
            return decision(observation, ProposalKind.RELEASE_AND_ESCALATE, "player_disconnected", true, true);
        }
        if (observation.emergencyStop()) {
            disarm();
            return decision(observation, ProposalKind.RELEASE_AND_ESCALATE, "emergency_stop", true, true);
        }
        if (observation.manualOverride()) {
            disarm();
            return decision(observation, ProposalKind.RELEASE_AND_ESCALATE, "manual_override", true, true);
        }
        if (delegatedFireRecovery) {
            if (observation.fireRecoveryActive()) {
                return decision(
                    observation,
                    ProposalKind.MONITOR_ADMITTED_RECOVERY,
                    observation.onFire() || observation.inLava()
                        ? "fire_recovery_program_active"
                        : "fire_recovery_postcondition_observed",
                    false,
                    true
                );
            }
            delegatedFireRecovery = false;
            if (!observation.onFire() && !observation.inLava() && observation.health() > 0) {
                return decision(
                    observation,
                    ProposalKind.RELEASE_AND_ESCALATE,
                    "fire_recovery_verified",
                    true,
                    true
                );
            }
        }
        if (observation.onFire() || observation.inLava()) {
            if (observation.fireRecoveryActive()) {
                delegatedFireRecovery = true;
                return decision(
                    observation,
                    ProposalKind.MONITOR_ADMITTED_RECOVERY,
                    "fire_recovery_program_active",
                    false,
                    true
                );
            }
            return decision(
                observation,
                ProposalKind.RELEASE_AND_ESCALATE,
                observation.inLava()
                    ? "lava_pressure_requires_semantic_replan"
                    : "fire_pressure_requires_semantic_replan",
                true,
                true
            );
        }
        if (!observation.inWater() && observation.horizontalCollision()) {
            collisionTicks++;
            if (collisionTicks >= BLOCKED_MOVEMENT_TICKS) {
                return decision(
                    observation,
                    ProposalKind.RELEASE_AND_ESCALATE,
                    "movement_blocked_requires_semantic_replan",
                    true,
                    true
                );
            }
        } else {
            collisionTicks = 0;
        }
        if (delegatedFallRecovery) {
            if (observation.unsafeLandingRecoveryActive()) {
                return decision(
                    observation,
                    ProposalKind.MONITOR_ADMITTED_RECOVERY,
                    "unsafe_landing_recovery_active",
                    false,
                    true
                );
            }
            delegatedFallRecovery = false;
            if (
                observation.health() > 0 &&
                (observation.onGround() || observation.inWater())
            ) {
                return decision(
                    observation,
                    ProposalKind.RELEASE_AND_ESCALATE,
                    "fall_recovery_verified",
                    true,
                    true
                );
            }
        }
        if (
            !observation.onGround() &&
            observation.velocityY() <= profile.dangerousVerticalVelocity() &&
            !observation.predictedSafeLanding()
        ) {
            if (observation.unsafeLandingRecoveryActive()) {
                delegatedFallRecovery = true;
                return decision(
                    observation,
                    ProposalKind.MONITOR_ADMITTED_RECOVERY,
                    "unsafe_landing_recovery_active",
                    false,
                    true
                );
            }
            return decision(observation, ProposalKind.RELEASE_AND_ESCALATE, "unsafe_landing_requires_admitted_recovery", true, true);
        }
        if (waterRecoveryActive) {
            // A swim impulse commonly carries the player one tick above the
            // surface before gravity returns them to the same water column.
            // That airborne bob is breathing progress, not a verified exit.
            // Release only after the player is both dry and grounded; until
            // then retain the admitted upward hold across the discontinuity.
            if (!observation.inWater() && observation.onGround()) {
                waterRecoveryActive = false;
                breathingRestored = false;
                swimTicks = 0;
                priorAir = observation.air();
                return decision(
                    observation,
                    ProposalKind.RELEASE_AND_ESCALATE,
                    "water_exit_verified",
                    true,
                    true
                );
            }
            if (!observation.inWater()) breathingRestored = true;
            if (!observation.submerged()) breathingRestored = true;
            if (!breathingRestored) {
                boolean airImproving = priorAir >= 0 && observation.air() > priorAir;
                priorAir = observation.air();
                if (swimTicks >= profile.maximumSwimTicks()) {
                    return decision(
                        observation,
                        ProposalKind.RELEASE_AND_ESCALATE,
                        "swim_repertoire_exhausted",
                        true,
                        true
                    );
                }
                swimTicks++;
                return decision(
                    observation,
                    ProposalKind.SWIM_UP,
                    airImproving ? "submerged_air_recovering" : "submerged_air_low",
                    false,
                    false
                );
            }
            priorAir = observation.air();
            return decision(
                observation,
                ProposalKind.SWIM_UP,
                "breathing_restored_surface_hold",
                false,
                true
            );
        }
        if (observation.submerged() && observation.air() <= profile.minimumAir()) {
            waterRecoveryActive = true;
            breathingRestored = false;
            boolean airImproving = priorAir >= 0 && observation.air() > priorAir;
            priorAir = observation.air();
            if (swimTicks >= profile.maximumSwimTicks()) {
                return decision(observation, ProposalKind.RELEASE_AND_ESCALATE, "swim_repertoire_exhausted", true, true);
            }
            swimTicks++;
            return decision(
                observation,
                ProposalKind.SWIM_UP,
                airImproving ? "submerged_air_recovering" : "submerged_air_low",
                false,
                false
            );
        }
        priorAir = observation.air();
        return decision(observation, ProposalKind.NONE, "viability_within_profile", false, false);
    }

    private Decision decision(
        Observation observation,
        ProposalKind proposal,
        String reason,
        boolean release,
        boolean escalate
    ) {
        Map<String, Object> measurements = new LinkedHashMap<>();
        measurements.put("profile_id", PROFILE_ID);
        measurements.put("artifact_version", ARTIFACT_VERSION);
        measurements.put("observed_tick", observation.observedTick());
        measurements.put("current_tick", observation.currentTick());
        measurements.put("health", (double) observation.health());
        measurements.put("air", observation.air());
        measurements.put("maximum_air", observation.maximumAir());
        measurements.put("submerged", observation.submerged());
        measurements.put("swimming", observation.swimming());
        measurements.put("in_water", observation.inWater());
        measurements.put("on_ground", observation.onGround());
        measurements.put("on_fire", observation.onFire());
        measurements.put("in_lava", observation.inLava());
        measurements.put("vertical_velocity", observation.velocityY());
        measurements.put("predicted_safe_landing", observation.predictedSafeLanding());
        measurements.put("unsafe_landing_recovery_active", observation.unsafeLandingRecoveryActive());
        measurements.put("fire_recovery_active", observation.fireRecoveryActive());
        measurements.put("delegated_fall_recovery", delegatedFallRecovery);
        measurements.put("delegated_fire_recovery", delegatedFireRecovery);
        measurements.put("swim_ticks", swimTicks);
        measurements.put("water_recovery_active", waterRecoveryActive);
        measurements.put("breathing_restored", breathingRestored);
        measurements.put("horizontal_collision", observation.horizontalCollision());
        measurements.put("collision_ticks", collisionTicks);
        return new Decision(
            ++decisionSequence,
            observation.revision(),
            proposal,
            reason,
            release,
            escalate,
            measurements
        );
    }
}
