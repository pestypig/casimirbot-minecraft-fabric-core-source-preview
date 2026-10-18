package com.casimirbot.helixplayer.fabric;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic short-horizon kinematics for an already-observed player.
 *
 * <p>This is deliberately not path planning or rescue strategy. It projects
 * the measured position and velocity under the vanilla airborne gravity/drag
 * approximation so an admitted program can branch on near-term contact or
 * exact placement reach. The live bridge marks the forecast inapplicable in
 * water, lava, flight, or other unsupported movement regimes.</p>
 */
final class ShortHorizonTrajectoryPredictor {
    static final String MODEL_SCHEMA =
        "helix.minecraft.short_horizon_trajectory.v1";
    static final int MAX_HORIZON_TICKS = 20;
    private static final double GRAVITY_PER_TICK = 0.08;
    private static final double VERTICAL_DRAG = 0.98;
    private static final double HORIZONTAL_DRAG = 0.91;

    record State(
        int tick,
        double x,
        double y,
        double z,
        double velocityX,
        double velocityY,
        double velocityZ
    ) {
        State {
            if (tick < 0 || tick > MAX_HORIZON_TICKS) {
                throw new IllegalArgumentException("Forecast tick is outside the horizon.");
            }
            for (double value : new double[] {
                x, y, z, velocityX, velocityY, velocityZ
            }) {
                if (!Double.isFinite(value)) {
                    throw new IllegalArgumentException("Forecast state must be finite.");
                }
            }
        }
    }

    record Forecast(int horizonTicks, List<State> states) {
        Forecast {
            if (horizonTicks < 1 || horizonTicks > MAX_HORIZON_TICKS) {
                throw new IllegalArgumentException("Forecast horizon must be 1-20 ticks.");
            }
            states = List.copyOf(states);
            if (states.size() != horizonTicks + 1 || states.get(0).tick() != 0) {
                throw new IllegalArgumentException("Forecast must include tick zero and every horizon tick.");
            }
        }

        State finalState() {
            return states.get(states.size() - 1);
        }
    }

    record PlacementForecast(
        boolean supportAvailable,
        int supportCandidateCount,
        int firstReachableTick,
        double initialDistance,
        double minimumPredictedDistance
    ) {
        PlacementForecast {
            if (supportCandidateCount < 0 || supportCandidateCount > 6) {
                throw new IllegalArgumentException("Support candidates must be 0-6.");
            }
            if (firstReachableTick < -1 || firstReachableTick > MAX_HORIZON_TICKS) {
                throw new IllegalArgumentException("Reachable tick is outside the horizon.");
            }
            if (!Double.isFinite(initialDistance) || !Double.isFinite(minimumPredictedDistance)) {
                throw new IllegalArgumentException("Placement distances must be finite.");
            }
        }

        boolean predictedReachable() {
            return supportAvailable && firstReachableTick >= 0;
        }
    }

    private ShortHorizonTrajectoryPredictor() {}

    static Forecast predict(
        double x,
        double y,
        double z,
        double velocityX,
        double velocityY,
        double velocityZ,
        int horizonTicks
    ) {
        if (horizonTicks < 1 || horizonTicks > MAX_HORIZON_TICKS) {
            throw new IllegalArgumentException("Forecast horizon must be 1-20 ticks.");
        }
        State current = new State(
            0, x, y, z, velocityX, velocityY, velocityZ
        );
        List<State> states = new ArrayList<>();
        states.add(current);
        for (int tick = 1; tick <= horizonTicks; tick++) {
            double nextX = current.x() + current.velocityX();
            double nextY = current.y() + current.velocityY();
            double nextZ = current.z() + current.velocityZ();
            double nextVelocityX = current.velocityX() * HORIZONTAL_DRAG;
            double nextVelocityY =
                (current.velocityY() - GRAVITY_PER_TICK) * VERTICAL_DRAG;
            double nextVelocityZ = current.velocityZ() * HORIZONTAL_DRAG;
            current = new State(
                tick,
                nextX,
                nextY,
                nextZ,
                nextVelocityX,
                nextVelocityY,
                nextVelocityZ
            );
            states.add(current);
        }
        return new Forecast(horizonTicks, states);
    }

    static PlacementForecast predictPlacementReach(
        Forecast forecast,
        double eyeOffset,
        double targetX,
        double targetY,
        double targetZ,
        double interactionRange,
        int supportCandidateCount
    ) {
        if (!Double.isFinite(eyeOffset) || !Double.isFinite(interactionRange) ||
            interactionRange <= 0) {
            throw new IllegalArgumentException("Placement reach inputs must be finite and positive.");
        }
        double initialDistance = distance(
            forecast.states().get(0),
            eyeOffset,
            targetX,
            targetY,
            targetZ
        );
        double minimumDistance = initialDistance;
        int firstReachableTick = -1;
        for (State state : forecast.states()) {
            double distance = distance(
                state,
                eyeOffset,
                targetX,
                targetY,
                targetZ
            );
            minimumDistance = Math.min(minimumDistance, distance);
            if (firstReachableTick < 0 && distance <= interactionRange) {
                firstReachableTick = state.tick();
            }
        }
        return new PlacementForecast(
            supportCandidateCount > 0,
            supportCandidateCount,
            firstReachableTick,
            initialDistance,
            minimumDistance
        );
    }

    private static double distance(
        State state,
        double eyeOffset,
        double targetX,
        double targetY,
        double targetZ
    ) {
        double dx = targetX - state.x();
        double dy = targetY - (state.y() + eyeOffset);
        double dz = targetZ - state.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
