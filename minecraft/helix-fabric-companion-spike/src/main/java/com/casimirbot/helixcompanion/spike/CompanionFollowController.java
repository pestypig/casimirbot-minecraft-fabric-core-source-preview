package com.casimirbot.helixcompanion.spike;

import java.util.Objects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Private deterministic C1 controller. It consumes one admitted semantic mode
 * and uses only the visible companion's native controls. It owns no model
 * loop, command lane, inventory, combat, mining, answer, or terminal authority.
 */
public final class CompanionFollowController {
    public static final Profile C1_PROFILE = new Profile(
        6.0D,
        3.0D,
        24.0D,
        16.0D,
        1.0D,
        5L,
        4,
        20L,
        20L,
        200L
    );

    private final CompanionPresenceRuntime presence;
    private final SpikeCompanionEntity actor;
    private final Profile profile;
    private Session session;

    public CompanionFollowController(
        CompanionPresenceRuntime presence,
        Profile profile
    ) {
        this.presence = Objects.requireNonNull(presence);
        this.actor = presence.actor();
        this.profile = Objects.requireNonNull(profile);
    }

    public Snapshot admit(Command command, long currentTick) {
        Objects.requireNonNull(command);
        if (session != null && !session.terminal()) {
            throw new FollowException("companion_controller_busy");
        }
        if (command.requestedAtTick() > currentTick) {
            throw new FollowException("companion_action_from_future");
        }
        long maximumExpiry = Math.addExact(currentTick, profile.defaultActionLeaseTicks());
        if (
            command.expiresAtTick() <= currentTick
                || command.expiresAtTick() > maximumExpiry
        ) {
            throw new FollowException("companion_action_expiry_invalid");
        }
        validateCommand(command, currentTick);
        CompanionPresenceRuntime.ActionLease action = presence.issueAction(
            command.actionId(),
            command.expiresAtTick()
        );
        String taskId = "task:c1:" + command.actionId();
        presence.queueTask(taskId);
        session = new Session(command, action, taskId, currentTick);

        if (command.mode() == Mode.RELEASE) {
            return settle("completed", "released_by_semantic_action", currentTick);
        }
        if (command.mode() == Mode.ABSTAIN) {
            return settle("abstained", command.reason(), currentTick);
        }
        if (command.mode() == Mode.HOLD) {
            stopNavigation();
            session.state = State.HOLDING;
        }
        return snapshot(currentTick);
    }

    public Snapshot tick(long currentTick) {
        if (session == null) {
            return Snapshot.idle(currentTick);
        }
        if (session.terminal()) {
            return snapshot(currentTick);
        }
        CompanionPresenceRuntime.ActionCheck current = presence.checkAction(
            session.action,
            currentTick
        );
        if (!current.current()) {
            String outcome = current.reason().equals("companion_action_expired")
                ? "lease_expired"
                : "identity_stale";
            return settle(outcome, current.reason(), currentTick);
        }
        session.ticksActive++;
        return switch (session.command.mode()) {
            case FOLLOW -> tickFollow(currentTick, false);
            case RETURN_TO_OWNER -> tickFollow(currentTick, true);
            case HOLD -> tickHold(currentTick);
            case LOOK_AT -> tickLook(currentTick);
            case NAVIGATE_NEARBY -> tickWaypoint(currentTick);
            case RELEASE, ABSTAIN -> snapshot(currentTick);
        };
    }

    public Snapshot manualRelease(String reason, long currentTick) {
        if (session == null || session.terminal()) return snapshot(currentTick);
        String normalized = requireText(reason, "reason");
        String outcome = normalized.equals("emergency_stop")
            ? "emergency_stopped"
            : "manual_override";
        return settle(outcome, normalized, currentTick);
    }

    public Snapshot releaseSuspended(long currentTick) {
        if (session == null || session.state != State.SUSPENDED) {
            throw new FollowException("companion_controller_not_suspended");
        }
        return settle("abstained", session.reason, currentTick);
    }

    public Snapshot snapshot(long currentTick) {
        if (session == null) return Snapshot.idle(currentTick);
        return new Snapshot(
            session.command.actionId(),
            session.command.mode(),
            session.state,
            session.outcome,
            session.reason,
            session.startedAtTick,
            currentTick,
            session.ticksActive,
            session.pathRequests,
            session.navigationStarts,
            session.navigationStops,
            session.pathFailures,
            session.replans,
            finiteOrNull(session.minimumDistance),
            finiteOrNull(session.maximumDistance),
            !presence.controlsAsserted(),
            session.taskReleased,
            false,
            false,
            false,
            false,
            false,
            false
        );
    }

    private Snapshot tickFollow(long currentTick, boolean returning) {
        Entity target = validatedTarget(currentTick);
        if (target == null) return snapshot(currentTick);
        double distance = actor.distanceTo(target);
        observeDistance(distance);
        if (distance > profile.maximumTargetRadius()) {
            return suspend("companion_target_out_of_bounds", currentTick);
        }

        if (session.moving) {
            if (distance <= profile.stopDistance()) {
                stopNavigation();
                session.state = State.HOLDING;
                session.noProgressTicks = 0L;
                return snapshot(currentTick);
            }
        } else if (distance >= profile.startDistance()) {
            session.moving = true;
            session.state = State.MOVING;
            if (!requestPath(target.getX(), target.getY(), target.getZ(), currentTick)) {
                return onPathFailure(currentTick);
            }
        }

        if (
            session.moving
                && currentTick - session.lastPathRequestTick
                    >= profile.pathRecalculationIntervalTicks()
        ) {
            session.replans++;
            if (!requestPath(target.getX(), target.getY(), target.getZ(), currentTick)) {
                return onPathFailure(currentTick);
            }
        }
        if (session.moving && trackProgress(distance)) {
            return suspend("companion_obstruction_replan_required", currentTick);
        }
        session.reason = returning ? "returning_to_owner" : "following_target";
        return snapshot(currentTick);
    }

    private Snapshot tickHold(long currentTick) {
        if (presence.controlsAsserted() || actor.getNavigation().isInProgress()) {
            stopNavigation();
        }
        session.state = State.HOLDING;
        session.reason = "holding_position";
        return snapshot(currentTick);
    }

    private Snapshot tickLook(long currentTick) {
        Entity target = validatedTarget(currentTick);
        if (target == null) return snapshot(currentTick);
        stopNavigationIfNeeded();
        actor.getLookControl().setLookAt(target, 30.0F, 30.0F);
        session.state = State.LOOKING;
        session.reason = "looking_at_target";
        return snapshot(currentTick);
    }

    private Snapshot tickWaypoint(long currentTick) {
        Vec3 waypoint = session.command.waypoint();
        double distance = actor.position().distanceTo(waypoint);
        observeDistance(distance);
        if (distance <= profile.stopDistance()) {
            return settle("completed", "nearby_waypoint_reached", currentTick);
        }
        if (
            !session.moving
                || currentTick - session.lastPathRequestTick
                    >= profile.pathRecalculationIntervalTicks()
        ) {
            if (session.moving) session.replans++;
            session.moving = true;
            session.state = State.MOVING;
            if (!requestPath(waypoint.x, waypoint.y, waypoint.z, currentTick)) {
                return onPathFailure(currentTick);
            }
        }
        if (trackProgress(distance)) {
            return suspend("companion_obstruction_replan_required", currentTick);
        }
        session.reason = "navigating_nearby_waypoint";
        return snapshot(currentTick);
    }

    private Entity validatedTarget(long currentTick) {
        Entity target = session.command.target();
        if (target == null) {
            suspend("companion_target_missing_replan_required", currentTick);
            return null;
        }
        if (target.isRemoved()) {
            suspend("companion_target_removed_replan_required", currentTick);
            return null;
        }
        if (!target.isAlive()) {
            suspend("companion_target_dead_replan_required", currentTick);
            return null;
        }
        if (target.level() != actor.level()) {
            suspend("companion_target_cross_world", currentTick);
            return null;
        }
        if (
            session.lastTargetObservationTick > currentTick
                || currentTick - session.lastTargetObservationTick
                    > profile.maximumTargetObservationAgeTicks()
        ) {
            suspend("companion_target_stale", currentTick);
            return null;
        }
        session.lastTargetObservationTick = currentTick;
        return target;
    }

    private boolean requestPath(double x, double y, double z, long currentTick) {
        session.pathRequests++;
        session.lastPathRequestTick = currentTick;
        boolean started = presence.startNavigation(x, y, z, profile.navigationSpeed());
        if (started) {
            session.navigationStarts++;
            session.consecutivePathFailures = 0;
            session.state = State.MOVING;
            return true;
        }
        session.pathFailures++;
        session.consecutivePathFailures++;
        return false;
    }

    private Snapshot onPathFailure(long currentTick) {
        if (
            session.consecutivePathFailures
                >= profile.maximumConsecutivePathFailures()
        ) {
            return suspend("companion_obstruction_replan_required", currentTick);
        }
        session.reason = "companion_path_retry_pending";
        return snapshot(currentTick);
    }

    private boolean trackProgress(double distance) {
        if (distance + 0.05D < session.lastProgressDistance) {
            session.lastProgressDistance = distance;
            session.noProgressTicks = 0L;
            return false;
        }
        session.noProgressTicks++;
        return session.noProgressTicks > profile.noProgressCeilingTicks();
    }

    private Snapshot suspend(String reason, long currentTick) {
        stopNavigationIfNeeded();
        session.state = State.SUSPENDED;
        session.reason = requireText(reason, "reason");
        session.moving = false;
        return snapshot(currentTick);
    }

    private Snapshot settle(String outcome, String reason, long currentTick) {
        stopNavigationIfNeeded();
        if (!session.taskReleased) {
            if (presence.active()) {
                presence.releaseTask(session.taskId);
            }
            session.taskReleased = true;
        }
        session.state = State.RELEASED;
        session.outcome = requireText(outcome, "outcome");
        session.reason = requireText(reason, "reason");
        session.moving = false;
        return snapshot(currentTick);
    }

    private void stopNavigationIfNeeded() {
        if (
            presence.active()
                && (presence.controlsAsserted() || actor.getNavigation().isInProgress())
        ) {
            stopNavigation();
        }
    }

    private void stopNavigation() {
        presence.stopNavigation();
        session.navigationStops++;
        session.moving = false;
    }

    private void observeDistance(double distance) {
        session.minimumDistance = Math.min(session.minimumDistance, distance);
        session.maximumDistance = Math.max(session.maximumDistance, distance);
        if (!Double.isFinite(session.lastProgressDistance)) {
            session.lastProgressDistance = distance;
        }
    }

    private void validateCommand(Command command, long currentTick) {
        requireText(command.actionId(), "actionId");
        if (
            command.mode() == Mode.FOLLOW
                || command.mode() == Mode.LOOK_AT
                || command.mode() == Mode.RETURN_TO_OWNER
        ) {
            if (command.target() == null) {
                throw new FollowException("companion_target_required");
            }
            if (
                command.targetObservedAtTick() > currentTick
                    || currentTick - command.targetObservedAtTick()
                        > profile.maximumTargetObservationAgeTicks()
            ) {
                throw new FollowException("companion_target_stale");
            }
        }
        if (command.mode() == Mode.NAVIGATE_NEARBY) {
            if (command.waypoint() == null) {
                throw new FollowException("companion_waypoint_required");
            }
            if (
                actor.position().distanceTo(command.waypoint())
                    > profile.maximumWaypointRadius()
            ) {
                throw new FollowException("companion_waypoint_out_of_bounds");
            }
        }
        if (command.mode() == Mode.ABSTAIN) {
            requireText(command.reason(), "reason");
        }
    }

    private static Double finiteOrNull(double value) {
        return Double.isFinite(value) ? value : null;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new FollowException(field + "_required");
        }
        return value;
    }

    public enum Mode {
        FOLLOW,
        HOLD,
        LOOK_AT,
        NAVIGATE_NEARBY,
        RETURN_TO_OWNER,
        RELEASE,
        ABSTAIN
    }

    public enum State {
        IDLE,
        ADMITTED,
        MOVING,
        HOLDING,
        LOOKING,
        SUSPENDED,
        RELEASED
    }

    public record Profile(
        double startDistance,
        double stopDistance,
        double maximumTargetRadius,
        double maximumWaypointRadius,
        double navigationSpeed,
        long pathRecalculationIntervalTicks,
        int maximumConsecutivePathFailures,
        long noProgressCeilingTicks,
        long maximumTargetObservationAgeTicks,
        long defaultActionLeaseTicks
    ) {
        public Profile {
            if (startDistance <= stopDistance || stopDistance <= 0.0D) {
                throw new FollowException("companion_follow_hysteresis_invalid");
            }
            if (
                maximumTargetRadius < startDistance
                    || maximumWaypointRadius <= 0.0D
                    || navigationSpeed <= 0.0D
                    || pathRecalculationIntervalTicks <= 0L
                    || maximumConsecutivePathFailures <= 0
                    || noProgressCeilingTicks <= 0L
                    || maximumTargetObservationAgeTicks <= 0L
                    || defaultActionLeaseTicks <= 0L
            ) {
                throw new FollowException("companion_follow_profile_invalid");
            }
        }
    }

    public record Command(
        String actionId,
        Mode mode,
        Entity target,
        Vec3 waypoint,
        String reason,
        long targetObservedAtTick,
        long requestedAtTick,
        long expiresAtTick
    ) {
        public static Command follow(
            String actionId,
            Entity target,
            long currentTick,
            long expiresAtTick
        ) {
            return new Command(
                actionId,
                Mode.FOLLOW,
                target,
                null,
                null,
                currentTick,
                currentTick,
                expiresAtTick
            );
        }

        public static Command mode(
            String actionId,
            Mode mode,
            long currentTick,
            long expiresAtTick
        ) {
            return new Command(
                actionId,
                mode,
                null,
                null,
                null,
                currentTick,
                currentTick,
                expiresAtTick
            );
        }
    }

    public record Snapshot(
        String actionId,
        Mode mode,
        State state,
        String outcome,
        String reason,
        long startedAtTick,
        long observedAtTick,
        long ticksActive,
        int pathRequests,
        int navigationStarts,
        int navigationStops,
        int pathFailures,
        int replans,
        Double minimumDistance,
        Double maximumDistance,
        boolean controlsReleased,
        boolean taskReleased,
        boolean worldAuthorityUsed,
        boolean inventoryAuthority,
        boolean miningAuthorized,
        boolean combatAuthorized,
        boolean answerAuthority,
        boolean terminalEligible
    ) {
        public static Snapshot idle(long currentTick) {
            return new Snapshot(
                null,
                null,
                State.IDLE,
                null,
                "idle",
                currentTick,
                currentTick,
                0L,
                0,
                0,
                0,
                0,
                0,
                null,
                null,
                true,
                true,
                false,
                false,
                false,
                false,
                false,
                false
            );
        }
    }

    private static final class Session {
        private final Command command;
        private final CompanionPresenceRuntime.ActionLease action;
        private final String taskId;
        private final long startedAtTick;
        private State state = State.ADMITTED;
        private String outcome;
        private String reason = "admitted";
        private long ticksActive;
        private int pathRequests;
        private int navigationStarts;
        private int navigationStops;
        private int pathFailures;
        private int replans;
        private int consecutivePathFailures;
        private long noProgressTicks;
        private long lastPathRequestTick = Long.MIN_VALUE;
        private long lastTargetObservationTick;
        private double lastProgressDistance = Double.POSITIVE_INFINITY;
        private double minimumDistance = Double.POSITIVE_INFINITY;
        private double maximumDistance = Double.NEGATIVE_INFINITY;
        private boolean moving;
        private boolean taskReleased;

        private Session(
            Command command,
            CompanionPresenceRuntime.ActionLease action,
            String taskId,
            long startedAtTick
        ) {
            this.command = command;
            this.action = action;
            this.taskId = taskId;
            this.startedAtTick = startedAtTick;
            this.lastTargetObservationTick = command.targetObservedAtTick();
        }

        private boolean terminal() {
            return state == State.RELEASED;
        }
    }

    public static final class FollowException extends RuntimeException {
        private final String code;

        public FollowException(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
