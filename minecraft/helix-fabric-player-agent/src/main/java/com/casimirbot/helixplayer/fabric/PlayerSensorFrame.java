package com.casimirbot.helixplayer.fabric;

import com.casimirbot.helixplayer.fabric.PlayerActionWorkflow.PlayerSnapshot;

/**
 * One immutable, client-thread observation captured at most once per Minecraft
 * world tick. It contains mechanics evidence only; it does not select a goal or
 * authorize an action.
 */
record PlayerSensorFrame(
    long worldRevision,
    long gameTick,
    long captureDurationNanos,
    String dimensionId,
    boolean connected,
    double x,
    double y,
    double eyeY,
    double z,
    double velocityX,
    double velocityY,
    double velocityZ,
    float yaw,
    float pitch,
    float health,
    boolean onGround,
    boolean horizontalCollision,
    boolean verticalCollision,
    boolean screenOpen,
    boolean manualInputDetected,
    String manualInputReason,
    Focus focus
) {
    enum FocusKind { MISS, BLOCK, ENTITY }

    record Focus(
        FocusKind kind,
        int blockX,
        int blockY,
        int blockZ,
        String face,
        double hitX,
        double hitY,
        double hitZ,
        double distance
    ) {
        Focus {
            if (kind == null) throw new IllegalArgumentException("focus kind is required");
            face = face == null ? "" : face;
            requireFinite(hitX, "focus hitX");
            requireFinite(hitY, "focus hitY");
            requireFinite(hitZ, "focus hitZ");
            requireFinite(distance, "focus distance");
            if (distance < 0) throw new IllegalArgumentException("focus distance cannot be negative");
            if (kind != FocusKind.BLOCK) face = "";
        }

        static Focus miss(double eyeX, double eyeY, double eyeZ) {
            return new Focus(FocusKind.MISS, 0, 0, 0, "", eyeX, eyeY, eyeZ, 0);
        }

        boolean isBlock(int x, int y, int z) {
            return kind == FocusKind.BLOCK &&
                blockX == x && blockY == y && blockZ == z;
        }
    }

    PlayerSensorFrame {
        if (worldRevision < 0) throw new IllegalArgumentException("worldRevision cannot be negative");
        if (captureDurationNanos < 0) {
            throw new IllegalArgumentException("captureDurationNanos cannot be negative");
        }
        dimensionId = dimensionId == null ? "" : dimensionId;
        if (dimensionId.length() > 320) {
            throw new IllegalArgumentException("dimensionId must be bounded");
        }
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(eyeY, "eyeY");
        requireFinite(z, "z");
        requireFinite(velocityX, "velocityX");
        requireFinite(velocityY, "velocityY");
        requireFinite(velocityZ, "velocityZ");
        requireFinite(yaw, "yaw");
        requireFinite(pitch, "pitch");
        requireFinite(health, "health");
        if (!manualInputDetected) manualInputReason = null;
        if (manualInputDetected && (manualInputReason == null || manualInputReason.isBlank())) {
            manualInputReason = "unspecified_manual_input";
        }
        if (manualInputReason != null && manualInputReason.length() > 320) {
            throw new IllegalArgumentException("manualInputReason must be bounded");
        }
        if (focus == null) focus = Focus.miss(x, eyeY, z);
    }

    static PlayerSensorFrame disconnected(long worldRevision) {
        return new PlayerSensorFrame(
            worldRevision, 0, 0, "", false,
            0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, false, false, false, false,
            false, null, Focus.miss(0, 0, 0)
        );
    }

    PlayerSnapshot snapshot() {
        return new PlayerSnapshot(
            connected, x, y, eyeY, z, yaw, pitch, health, onGround,
            horizontalCollision, manualInputDetected, manualInputReason
        );
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException(name + " must be finite");
    }
}
