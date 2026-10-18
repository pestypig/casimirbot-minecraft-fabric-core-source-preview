package com.casimirbot.helixplayer.fabric;

/**
 * Pure, bounded admission check for one already-planned locomotion tick. It
 * reports mechanics evidence only and never chooses a route or recovery plan.
 */
record LocomotionSafetyEnvelope(double minimumHealth, double maximumDropBlocks) {
    record Observation(
        double health,
        boolean onGround,
        double verticalVelocity,
        double fallDistance,
        boolean inLava,
        boolean onFire,
        boolean landingGeometryKnown,
        double predictedDropBlocks,
        boolean predictedLandingLava,
        boolean controlledJumpArc
    ) {}

    record Decision(boolean admitted, String reasonCode) {
        static Decision admit() {
            return new Decision(true, "locomotion_safety_admitted");
        }

        static Decision refuse(String reasonCode) {
            return new Decision(false, reasonCode);
        }
    }

    record Check(Decision decision, java.util.Map<String, Object> measurements) {
        Check {
            if (decision == null) throw new IllegalArgumentException("decision is required");
            measurements = measurements == null ? java.util.Map.of() : java.util.Map.copyOf(measurements);
        }
    }

    LocomotionSafetyEnvelope {
        if (!Double.isFinite(minimumHealth) || minimumHealth < 1 || minimumHealth > 20) {
            throw new IllegalArgumentException("minimumHealth must be within 1..20");
        }
        if (!Double.isFinite(maximumDropBlocks) || maximumDropBlocks < 0 || maximumDropBlocks > 3) {
            throw new IllegalArgumentException("maximumDropBlocks must be within 0..3");
        }
    }

    Decision assess(Observation observation) {
        if (observation == null) throw new IllegalArgumentException("observation is required");
        if (!Double.isFinite(observation.health()) ||
            !Double.isFinite(observation.verticalVelocity()) ||
            !Double.isFinite(observation.fallDistance()) ||
            !Double.isFinite(observation.predictedDropBlocks())) {
            return Decision.refuse("locomotion_observation_invalid");
        }
        if (observation.health() < minimumHealth) {
            return Decision.refuse("locomotion_health_floor_crossed");
        }
        if (observation.inLava()) return Decision.refuse("locomotion_lava_contact");
        if (observation.onFire()) return Decision.refuse("locomotion_fire_contact");
        if (!observation.onGround() &&
            observation.verticalVelocity() < -0.35 &&
            observation.fallDistance() > maximumDropBlocks &&
            !observation.controlledJumpArc()) {
            return Decision.refuse("locomotion_active_fall");
        }
        if (!observation.landingGeometryKnown()) {
            return Decision.refuse("locomotion_landing_geometry_unknown");
        }
        if (observation.predictedLandingLava()) {
            return Decision.refuse("locomotion_predicted_lava");
        }
        if (observation.predictedDropBlocks() > maximumDropBlocks) {
            return Decision.refuse("locomotion_predicted_drop_exceeded");
        }
        return Decision.admit();
    }
}
