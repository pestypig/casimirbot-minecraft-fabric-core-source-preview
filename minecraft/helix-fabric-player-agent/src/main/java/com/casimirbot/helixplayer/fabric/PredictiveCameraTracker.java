package com.casimirbot.helixplayer.fabric;

/**
 * Pure render-frame camera tracking math for an already-admitted target.
 *
 * <p>The semantic target and prediction horizon are selected outside this
 * controller. This class only applies bounded angular rate, acceleration and
 * deadband constraints, so it can be tested without a running Minecraft
 * client.</p>
 */
final class PredictiveCameraTracker {
    record State(float angularRateDegreesPerTick) {
        State {
            requireFinite(angularRateDegreesPerTick, "angularRateDegreesPerTick");
            if (angularRateDegreesPerTick < 0) {
                throw new IllegalArgumentException("Angular rate cannot be negative.");
            }
        }

        static State initial() {
            return new State(0);
        }
    }

    record Step(
        State state,
        float yaw,
        float pitch,
        float targetYaw,
        float targetPitch,
        float yawError,
        float pitchError,
        boolean moved
    ) {}

    private PredictiveCameraTracker() {}

    static Step stepTowardPosition(
        float currentYaw,
        float currentPitch,
        double eyeX,
        double eyeY,
        double eyeZ,
        double targetX,
        double targetY,
        double targetZ,
        float maxRateDegreesPerTick,
        float maxAccelerationDegreesPerTickSquared,
        float deadbandDegrees,
        double elapsedTicks,
        State state
    ) {
        requireFinite(eyeX, "eyeX");
        requireFinite(eyeY, "eyeY");
        requireFinite(eyeZ, "eyeZ");
        requireFinite(targetX, "targetX");
        requireFinite(targetY, "targetY");
        requireFinite(targetZ, "targetZ");
        double dx = targetX - eyeX;
        double dy = targetY - eyeY;
        double dz = targetZ - eyeZ;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float targetPitch = clamp(
            (float) -Math.toDegrees(Math.atan2(dy, horizontal)),
            -90,
            90
        );
        return stepTowardAngles(
            currentYaw,
            currentPitch,
            targetYaw,
            targetPitch,
            maxRateDegreesPerTick,
            maxAccelerationDegreesPerTickSquared,
            deadbandDegrees,
            elapsedTicks,
            state
        );
    }

    static Step stepTowardAngles(
        float currentYaw,
        float currentPitch,
        float targetYaw,
        float targetPitch,
        float maxRateDegreesPerTick,
        float maxAccelerationDegreesPerTickSquared,
        float deadbandDegrees,
        double elapsedTicks,
        State state
    ) {
        requireFinite(currentYaw, "currentYaw");
        requireFinite(currentPitch, "currentPitch");
        requireFinite(targetYaw, "targetYaw");
        requireFinite(targetPitch, "targetPitch");
        requireFinite(maxRateDegreesPerTick, "maxRateDegreesPerTick");
        requireFinite(
            maxAccelerationDegreesPerTickSquared,
            "maxAccelerationDegreesPerTickSquared"
        );
        requireFinite(deadbandDegrees, "deadbandDegrees");
        requireFinite(elapsedTicks, "elapsedTicks");
        if (maxRateDegreesPerTick <= 0 || maxAccelerationDegreesPerTickSquared <= 0) {
            throw new IllegalArgumentException("Tracking rate and acceleration must be positive.");
        }
        if (deadbandDegrees < 0) {
            throw new IllegalArgumentException("Tracking deadband cannot be negative.");
        }
        State currentState = state == null ? State.initial() : state;
        double boundedElapsedTicks = Math.max(0.001, Math.min(1.0, elapsedTicks));
        float clampedTargetPitch = clamp(targetPitch, -90, 90);
        float signedYawError = wrapDegrees(targetYaw - currentYaw);
        float signedPitchError = clampedTargetPitch - currentPitch;
        float yawError = Math.abs(signedYawError);
        float pitchError = Math.abs(signedPitchError);
        float maximumError = Math.max(yawError, pitchError);

        if (maximumError <= deadbandDegrees) {
            float nextRate = Math.max(
                0,
                currentState.angularRateDegreesPerTick() -
                    (float) (maxAccelerationDegreesPerTickSquared * boundedElapsedTicks)
            );
            return new Step(
                new State(nextRate),
                currentYaw,
                currentPitch,
                targetYaw,
                clampedTargetPitch,
                yawError,
                pitchError,
                false
            );
        }

        float requestedRate = Math.min(maxRateDegreesPerTick, maximumError);
        float nextRate = Math.min(
            requestedRate,
            currentState.angularRateDegreesPerTick() +
                (float) (maxAccelerationDegreesPerTickSquared * boundedElapsedTicks)
        );
        float maximumStep = Math.max(0.001F, (float) (nextRate * boundedElapsedTicks));
        float nextYaw = currentYaw + clamp(signedYawError, -maximumStep, maximumStep);
        float nextPitch = clamp(
            currentPitch + clamp(signedPitchError, -maximumStep, maximumStep),
            -90,
            90
        );
        return new Step(
            new State(nextRate),
            nextYaw,
            nextPitch,
            targetYaw,
            clampedTargetPitch,
            yawError,
            pitchError,
            true
        );
    }

    static float wrapDegrees(float value) {
        float wrapped = value % 360.0F;
        if (wrapped >= 180.0F) wrapped -= 360.0F;
        if (wrapped < -180.0F) wrapped += 360.0F;
        return wrapped;
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static void requireFinite(double value, String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite.");
        }
    }
}
