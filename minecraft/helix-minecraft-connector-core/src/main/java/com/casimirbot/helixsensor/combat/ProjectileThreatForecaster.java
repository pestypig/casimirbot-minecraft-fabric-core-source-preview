package com.casimirbot.helixsensor.combat;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic short-horizon projectile threat classifier.
 *
 * <p>The caller supplies measured position/velocity plus the exact per-tick
 * drag and acceleration for the admitted projectile type. The forecaster does
 * not infer missing physics. It samples swept segments against the actor's
 * current collision box and a bounded near-miss envelope. Incomplete evidence
 * always settles as {@link ThreatClassification#UNKNOWN}.</p>
 */
public final class ProjectileThreatForecaster {
    public enum ThreatClassification {
        COLLISION,
        NEAR_MISS,
        SAFE,
        UNKNOWN
    }

    public record Vector(double x, double y, double z) {
        public Vector {
            requireFinite(x, "x");
            requireFinite(y, "y");
            requireFinite(z, "z");
        }

        Vector add(Vector other) {
            return new Vector(x + other.x, y + other.y, z + other.z);
        }

        Vector scale(double factor) {
            requireFinite(factor, "factor");
            return new Vector(x * factor, y * factor, z * factor);
        }
    }

    public record Box(
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ
    ) {
        public Box {
            requireFinite(minX, "minX");
            requireFinite(minY, "minY");
            requireFinite(minZ, "minZ");
            requireFinite(maxX, "maxX");
            requireFinite(maxY, "maxY");
            requireFinite(maxZ, "maxZ");
            if (minX > maxX || minY > maxY || minZ > maxZ) {
                throw new IllegalArgumentException("Box minima must not exceed maxima.");
            }
        }

        Box inflate(double margin) {
            if (!Double.isFinite(margin) || margin < 0 || margin > 8) {
                throw new IllegalArgumentException("margin must be finite and between 0 and 8.");
            }
            return new Box(
                minX - margin,
                minY - margin,
                minZ - margin,
                maxX + margin,
                maxY + margin,
                maxZ + margin
            );
        }
    }

    public record Input(
        Vector position,
        Vector velocity,
        Vector acceleration,
        double drag,
        int supportTicks,
        Box actorBox,
        double nearMissMargin,
        boolean evidenceComplete,
        Integer verifiedOcclusionTick
    ) {
        public Input {
            if (position == null || velocity == null || acceleration == null || actorBox == null) {
                throw new IllegalArgumentException("Projectile input vectors and actorBox are required.");
            }
            if (!Double.isFinite(drag) || drag < 0 || drag > 1.5) {
                throw new IllegalArgumentException("drag must be finite and between 0 and 1.5.");
            }
            if (supportTicks < 1 || supportTicks > 80) {
                throw new IllegalArgumentException("supportTicks must be between 1 and 80.");
            }
            if (!Double.isFinite(nearMissMargin) || nearMissMargin < 0 || nearMissMargin > 8) {
                throw new IllegalArgumentException("nearMissMargin must be finite and between 0 and 8.");
            }
            if (verifiedOcclusionTick != null &&
                (verifiedOcclusionTick < 1 || verifiedOcclusionTick > supportTicks)) {
                throw new IllegalArgumentException("verifiedOcclusionTick must fall within supportTicks.");
            }
        }
    }

    public record Sample(int tick, Vector position, Vector velocity) {}

    public record Forecast(
        ThreatClassification classification,
        Integer predictedCollisionTick,
        Vector predictedImpactPosition,
        Integer nearMissTick,
        boolean evidenceComplete,
        boolean occluded,
        List<Sample> samples
    ) {
        public Forecast {
            samples = List.copyOf(samples);
        }
    }

    private ProjectileThreatForecaster() {}

    public static Forecast forecast(Input input) {
        if (input == null) throw new IllegalArgumentException("input is required.");

        List<Sample> samples = new ArrayList<>(input.supportTicks() + 1);
        Vector position = input.position();
        Vector velocity = input.velocity();
        samples.add(new Sample(0, position, velocity));

        Integer collisionTick = null;
        Vector collisionPosition = null;
        Integer nearMissTick = null;
        Box nearMissBox = input.actorBox().inflate(input.nearMissMargin());

        for (int tick = 1; tick <= input.supportTicks(); tick++) {
            Vector next = position.add(velocity);
            if (collisionTick == null && segmentIntersects(position, next, input.actorBox())) {
                collisionTick = tick;
                collisionPosition = firstIntersection(position, next, input.actorBox());
            }
            if (nearMissTick == null && segmentIntersects(position, next, nearMissBox)) {
                nearMissTick = tick;
            }
            velocity = velocity.scale(input.drag()).add(input.acceleration());
            position = next;
            samples.add(new Sample(tick, position, velocity));
        }

        boolean occludesCollision = input.verifiedOcclusionTick() != null &&
            collisionTick != null && input.verifiedOcclusionTick() <= collisionTick;
        boolean occludesNearMiss = input.verifiedOcclusionTick() != null &&
            nearMissTick != null && input.verifiedOcclusionTick() <= nearMissTick;

        ThreatClassification classification;
        if (!input.evidenceComplete()) {
            classification = ThreatClassification.UNKNOWN;
        } else if (collisionTick != null && !occludesCollision) {
            classification = ThreatClassification.COLLISION;
        } else if (nearMissTick != null && !occludesNearMiss) {
            classification = ThreatClassification.NEAR_MISS;
        } else {
            classification = ThreatClassification.SAFE;
        }

        return new Forecast(
            classification,
            collisionTick,
            collisionPosition,
            nearMissTick,
            input.evidenceComplete(),
            input.verifiedOcclusionTick() != null,
            samples
        );
    }

    private static boolean segmentIntersects(Vector start, Vector end, Box box) {
        return intersectionParameter(start, end, box) != null;
    }

    private static Vector firstIntersection(Vector start, Vector end, Box box) {
        Double parameter = intersectionParameter(start, end, box);
        if (parameter == null) return null;
        return new Vector(
            start.x() + (end.x() - start.x()) * parameter,
            start.y() + (end.y() - start.y()) * parameter,
            start.z() + (end.z() - start.z()) * parameter
        );
    }

    private static Double intersectionParameter(Vector start, Vector end, Box box) {
        double tMin = 0;
        double tMax = 1;
        double[] starts = {start.x(), start.y(), start.z()};
        double[] deltas = {
            end.x() - start.x(),
            end.y() - start.y(),
            end.z() - start.z()
        };
        double[] minima = {box.minX(), box.minY(), box.minZ()};
        double[] maxima = {box.maxX(), box.maxY(), box.maxZ()};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(deltas[axis]) < 1.0e-12) {
                if (starts[axis] < minima[axis] || starts[axis] > maxima[axis]) return null;
                continue;
            }
            double inverse = 1.0 / deltas[axis];
            double near = (minima[axis] - starts[axis]) * inverse;
            double far = (maxima[axis] - starts[axis]) * inverse;
            if (near > far) {
                double swap = near;
                near = far;
                far = swap;
            }
            tMin = Math.max(tMin, near);
            tMax = Math.min(tMax, far);
            if (tMin > tMax) return null;
        }
        return tMin;
    }

    private static void requireFinite(double value, String label) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(label + " must be finite.");
        }
    }
}
