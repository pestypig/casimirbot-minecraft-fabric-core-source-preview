package com.casimirbot.helixplayer.fabric;

import java.util.Comparator;
import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/** Bounded mechanics derivation for one already-selected mining target. */
record MiningTargetAffordance(
    BlockPos target,
    Direction face,
    Vec3 faceCenter,
    ApproachPose approach,
    double targetDistance,
    boolean inInteractionRange,
    boolean focused,
    boolean horizontallyBlocked
) {
    private static final int MAX_HORIZONTAL_APPROACH_RADIUS = 3;

    record ApproachPose(double x, double y, double z, Direction face, double distanceSquared) {}

    static MiningTargetAffordance derive(
        PlayerSensorFrame frame,
        BlockPos target,
        double interactionRange,
        Predicate<BlockPos> passable,
        Predicate<BlockPos> solid
    ) {
        if (frame == null || !frame.connected()) {
            throw new IllegalArgumentException("a connected sensor frame is required");
        }
        if (target == null) throw new IllegalArgumentException("target is required");
        if (!Double.isFinite(interactionRange) || interactionRange <= 0) {
            throw new IllegalArgumentException("interactionRange must be positive and finite");
        }
        Vec3 eye = new Vec3(frame.x(), frame.eyeY(), frame.z());
        ApproachPose approach = nearestApproach(target, eye, passable, solid).orElse(null);
        Direction face = approach == null
            ? nearestExposedFace(target, eye, passable).orElse(null)
            : approach.face();
        Vec3 faceCenter = face == null ? Vec3.atCenterOf(target) : faceCenter(target, face);
        double distance = eye.distanceTo(faceCenter);
        return new MiningTargetAffordance(
            target.immutable(), face, faceCenter, approach, distance,
            distance <= interactionRange,
            frame.focus().isBlock(target.getX(), target.getY(), target.getZ()),
            frame.horizontalCollision()
        );
    }

    static Optional<ApproachPose> nearestApproach(
        BlockPos target,
        Vec3 actorEye,
        Predicate<BlockPos> passable,
        Predicate<BlockPos> solid
    ) {
        if (target == null || actorEye == null || passable == null || solid == null) {
            throw new IllegalArgumentException("target, actorEye, passable, and solid are required");
        }
        ApproachPose best = null;
        for (int radius = 1; radius <= MAX_HORIZONTAL_APPROACH_RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                    for (int feetOffsetY = -1; feetOffsetY <= 1; feetOffsetY++) {
                        BlockPos feet = target.offset(dx, feetOffsetY, dz);
                        if (!passable.test(feet) || !passable.test(feet.above()) ||
                            !solid.test(feet.below())) continue;
                        Vec3 candidateEye = new Vec3(
                            feet.getX() + 0.5,
                            feet.getY() + 1.62,
                            feet.getZ() + 0.5
                        );
                        Optional<Direction> face = nearestExposedFace(
                            target,
                            candidateEye,
                            passable
                        );
                        if (face.isEmpty()) continue;
                        double distanceSquared = actorEye.distanceToSqr(candidateEye);
                        ApproachPose candidate = new ApproachPose(
                            feet.getX() + 0.5,
                            feet.getY(),
                            feet.getZ() + 0.5,
                            face.get(),
                            distanceSquared
                        );
                        if (best == null || Comparator
                            .comparingDouble(ApproachPose::distanceSquared)
                            .thenComparingDouble(ApproachPose::y)
                            .compare(candidate, best) < 0) {
                            best = candidate;
                        }
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    static Optional<Direction> nearestExposedFace(
        BlockPos target,
        Vec3 eye,
        Predicate<BlockPos> passable
    ) {
        Direction best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (Direction direction : Direction.values()) {
            if (!passable.test(target.relative(direction))) continue;
            double distance = eye.distanceToSqr(faceCenter(target, direction));
            if (distance < bestDistance) {
                best = direction;
                bestDistance = distance;
            }
        }
        return Optional.ofNullable(best);
    }

    static Vec3 faceCenter(BlockPos target, Direction face) {
        return new Vec3(
            target.getX() + 0.5 + face.getStepX() * 0.501,
            target.getY() + 0.5 + face.getStepY() * 0.501,
            target.getZ() + 0.5 + face.getStepZ() * 0.501
        );
    }
}
