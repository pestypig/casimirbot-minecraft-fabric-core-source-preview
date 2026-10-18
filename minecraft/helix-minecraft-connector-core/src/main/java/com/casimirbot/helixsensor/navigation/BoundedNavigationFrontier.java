package com.casimirbot.helixsensor.navigation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * CasimirBot-owned bounded foothold search. It operates only on typed voxel
 * facts supplied by the caller and never chooses a gameplay strategy.
 */
public final class BoundedNavigationFrontier {
    public enum CellKind {
        UNKNOWN,
        CLEAR,
        SOLID,
        HAZARD,
        FLUID,
        BLOCKED
    }

    public enum MoveKind {
        WALK,
        DIAGONAL,
        ASCEND,
        DESCEND
    }

    public enum GoalPlanStatus {
        ROUTE_FOUND,
        ALREADY_AT_GOAL,
        GOAL_OUTSIDE_VOLUME,
        EVIDENCE_INCOMPLETE,
        NO_ROUTE,
        ROUTE_STEP_LIMIT
    }

    public record Position(int x, int y, int z) {
        public Position offset(int dx, int dy, int dz) {
            return new Position(x + dx, y + dy, z + dz);
        }
    }

    public record Step(Position from, Position to, MoveKind kind, int cost) {}

    public record Route(
        Position destination,
        List<Step> steps,
        int cost,
        double displacement,
        int verticalGain,
        boolean coverageBoundary
    ) {}

    public record Result(
        Position origin,
        int horizontalRadius,
        int verticalRadius,
        int reachableFootholdCount,
        boolean evidenceComplete,
        boolean coverageBoundaryReached,
        boolean routeStepLimitReached,
        List<Route> rankedFrontiers,
        List<Route> reachableRoutes
    ) {}

    public record GoalPlan(
        GoalPlanStatus status,
        Position origin,
        Position goal,
        Route route,
        int reachableFootholdCount,
        boolean evidenceComplete,
        boolean coverageBoundaryReached,
        boolean routeStepLimitReached
    ) {}

    @FunctionalInterface
    public interface CellView {
        CellKind cell(Position position);
    }

    private record QueueNode(Position position, int cost) {}

    private record Parent(Position position, MoveKind kind, int edgeCost) {}

    private record Transition(Position position, MoveKind kind, int cost) {}

    private static final int[][] CARDINALS = {
        {0, 1},
        {1, 0},
        {0, -1},
        {-1, 0},
    };
    private static final int[][] DIAGONALS = {
        {1, 1},
        {1, -1},
        {-1, -1},
        {-1, 1},
    };
    private static final int MAX_ROUTE_STEPS = 32;

    private BoundedNavigationFrontier() {}

    public static Result analyze(
        Position origin,
        int horizontalRadius,
        int verticalRadius,
        int retainedFrontierLimit,
        CellView view
    ) {
        if (horizontalRadius < 1 || horizontalRadius > 16) {
            throw new IllegalArgumentException("horizontal radius must be 1..16");
        }
        if (verticalRadius < 1 || verticalRadius > 16) {
            throw new IllegalArgumentException("vertical radius must be 1..16");
        }
        if (retainedFrontierLimit < 1 || retainedFrontierLimit > 32) {
            throw new IllegalArgumentException("frontier limit must be 1..32");
        }

        Map<Position, CellKind> observedCells = new HashMap<>();
        CellView cachedView = position -> observedCells.computeIfAbsent(
            position,
            view::cell
        );

        Map<Position, Integer> bestCosts = new HashMap<>();
        Map<Position, Parent> parents = new HashMap<>();
        PriorityQueue<QueueNode> open = new PriorityQueue<>(
            Comparator.comparingInt(QueueNode::cost)
                .thenComparingInt(node -> node.position().x())
                .thenComparingInt(node -> node.position().y())
                .thenComparingInt(node -> node.position().z())
        );
        boolean[] incomplete = {false};
        bestCosts.put(origin, 0);
        open.add(new QueueNode(origin, 0));

        while (!open.isEmpty()) {
            QueueNode current = open.remove();
            if (current.cost() != bestCosts.getOrDefault(current.position(), -1)) {
                continue;
            }
            for (Transition transition : transitions(
                origin,
                current.position(),
                horizontalRadius,
                verticalRadius,
                cachedView,
                incomplete
            )) {
                int candidateCost = current.cost() + transition.cost();
                int existingCost = bestCosts.getOrDefault(
                    transition.position(),
                    Integer.MAX_VALUE
                );
                if (candidateCost >= existingCost) continue;
                bestCosts.put(transition.position(), candidateCost);
                parents.put(
                    transition.position(),
                    new Parent(
                        current.position(),
                        transition.kind(),
                        transition.cost()
                    )
                );
                open.add(new QueueNode(transition.position(), candidateCost));
            }
        }

        List<Route> routes = new ArrayList<>();
        boolean boundaryReached = false;
        boolean stepLimitReached = false;
        for (Map.Entry<Position, Integer> entry : bestCosts.entrySet()) {
            Position destination = entry.getKey();
            if (destination.equals(origin)) continue;
            boolean boundary = atBoundary(
                origin,
                destination,
                horizontalRadius,
                verticalRadius
            );
            boundaryReached |= boundary;
            List<Step> steps = reconstruct(origin, destination, parents);
            if (steps.size() > MAX_ROUTE_STEPS) {
                stepLimitReached = true;
                continue;
            }
            routes.add(new Route(
                destination,
                steps,
                entry.getValue(),
                displacement(origin, destination),
                destination.y() - origin.y(),
                boundary
            ));
        }
        routes.sort(
            Comparator.comparingDouble(Route::displacement).reversed()
                .thenComparing(Comparator.comparingInt(Route::verticalGain).reversed())
                .thenComparingInt(Route::cost)
                .thenComparingInt(route -> route.destination().x())
                .thenComparingInt(route -> route.destination().y())
                .thenComparingInt(route -> route.destination().z())
        );
        List<Route> reachableRoutes = List.copyOf(routes);
        if (routes.size() > retainedFrontierLimit) {
            routes = new ArrayList<>(routes.subList(0, retainedFrontierLimit));
        }
        return new Result(
            origin,
            horizontalRadius,
            verticalRadius,
            bestCosts.size(),
            !incomplete[0],
            boundaryReached,
            stepLimitReached,
            List.copyOf(routes),
            reachableRoutes
        );
    }

    public static GoalPlan planTo(
        Position origin,
        Position goal,
        int horizontalRadius,
        int verticalRadius,
        CellView view
    ) {
        if (origin.equals(goal)) {
            return new GoalPlan(
                GoalPlanStatus.ALREADY_AT_GOAL,
                origin,
                goal,
                null,
                1,
                true,
                false,
                false
            );
        }
        if (!inside(origin, goal, horizontalRadius, verticalRadius)) {
            return new GoalPlan(
                GoalPlanStatus.GOAL_OUTSIDE_VOLUME,
                origin,
                goal,
                null,
                1,
                true,
                false,
                false
            );
        }
        Result result = analyze(
            origin,
            horizontalRadius,
            verticalRadius,
            32,
            view
        );
        Route route = result.reachableRoutes().stream()
            .filter(candidate -> candidate.destination().equals(goal))
            .findFirst()
            .orElse(null);
        GoalPlanStatus status = route != null
            ? GoalPlanStatus.ROUTE_FOUND
            : !result.evidenceComplete()
                ? GoalPlanStatus.EVIDENCE_INCOMPLETE
                : result.routeStepLimitReached()
                    ? GoalPlanStatus.ROUTE_STEP_LIMIT
                    : GoalPlanStatus.NO_ROUTE;
        return new GoalPlan(
            status,
            origin,
            goal,
            route,
            result.reachableFootholdCount(),
            result.evidenceComplete(),
            result.coverageBoundaryReached(),
            result.routeStepLimitReached()
        );
    }

    private static List<Transition> transitions(
        Position origin,
        Position from,
        int horizontalRadius,
        int verticalRadius,
        CellView view,
        boolean[] incomplete
    ) {
        List<Transition> output = new ArrayList<>(16);
        for (int[] direction : CARDINALS) {
            appendCardinalTransitions(
                output,
                origin,
                from,
                direction[0],
                direction[1],
                horizontalRadius,
                verticalRadius,
                view,
                incomplete
            );
        }
        for (int[] direction : DIAGONALS) {
            Position target = from.offset(direction[0], 0, direction[1]);
            if (!inside(origin, target, horizontalRadius, verticalRadius)) continue;
            if (!standable(target, view, incomplete)) continue;
            if (
                !clearColumn(from.offset(direction[0], 0, 0), view, incomplete) ||
                !clearColumn(from.offset(0, 0, direction[1]), view, incomplete)
            ) continue;
            output.add(new Transition(target, MoveKind.DIAGONAL, 14));
        }
        return output;
    }

    private static void appendCardinalTransitions(
        List<Transition> output,
        Position origin,
        Position from,
        int dx,
        int dz,
        int horizontalRadius,
        int verticalRadius,
        CellView view,
        boolean[] incomplete
    ) {
        Position level = from.offset(dx, 0, dz);
        if (
            inside(origin, level, horizontalRadius, verticalRadius) &&
            standable(level, view, incomplete)
        ) output.add(new Transition(level, MoveKind.WALK, 10));

        Position ascend = from.offset(dx, 1, dz);
        if (
            inside(origin, ascend, horizontalRadius, verticalRadius) &&
            isClear(from.offset(0, 2, 0), view, incomplete) &&
            standable(ascend, view, incomplete)
        ) output.add(new Transition(ascend, MoveKind.ASCEND, 16));

        Position descend = from.offset(dx, -1, dz);
        if (
            inside(origin, descend, horizontalRadius, verticalRadius) &&
            standable(descend, view, incomplete)
        ) output.add(new Transition(descend, MoveKind.DESCEND, 12));
    }

    private static boolean clearColumn(
        Position feet,
        CellView view,
        boolean[] incomplete
    ) {
        return isClear(feet, view, incomplete) &&
            isClear(feet.offset(0, 1, 0), view, incomplete);
    }

    private static boolean standable(
        Position feet,
        CellView view,
        boolean[] incomplete
    ) {
        if (
            !isClear(feet, view, incomplete) ||
            !isClear(feet.offset(0, 1, 0), view, incomplete)
        ) return false;
        CellKind support = view.cell(feet.offset(0, -1, 0));
        if (support == CellKind.UNKNOWN) incomplete[0] = true;
        if (support != CellKind.SOLID) return false;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    CellKind nearby = view.cell(feet.offset(dx, dy, dz));
                    if (nearby == CellKind.UNKNOWN) {
                        incomplete[0] = true;
                        return false;
                    }
                    if (nearby == CellKind.HAZARD || nearby == CellKind.FLUID) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private static boolean isClear(
        Position position,
        CellView view,
        boolean[] incomplete
    ) {
        CellKind cell = view.cell(position);
        if (cell == CellKind.UNKNOWN) incomplete[0] = true;
        return cell == CellKind.CLEAR;
    }

    private static boolean inside(
        Position origin,
        Position candidate,
        int horizontalRadius,
        int verticalRadius
    ) {
        return Math.abs(candidate.x() - origin.x()) <= horizontalRadius &&
            Math.abs(candidate.z() - origin.z()) <= horizontalRadius &&
            Math.abs(candidate.y() - origin.y()) <= verticalRadius;
    }

    private static boolean atBoundary(
        Position origin,
        Position candidate,
        int horizontalRadius,
        int verticalRadius
    ) {
        return Math.abs(candidate.x() - origin.x()) == horizontalRadius ||
            Math.abs(candidate.z() - origin.z()) == horizontalRadius ||
            Math.abs(candidate.y() - origin.y()) == verticalRadius;
    }

    private static List<Step> reconstruct(
        Position origin,
        Position destination,
        Map<Position, Parent> parents
    ) {
        List<Step> reverse = new ArrayList<>();
        Position cursor = destination;
        while (!cursor.equals(origin)) {
            Parent parent = parents.get(cursor);
            if (parent == null) {
                throw new IllegalStateException("frontier route parent missing");
            }
            reverse.add(new Step(
                parent.position(),
                cursor,
                parent.kind(),
                parent.edgeCost()
            ));
            cursor = parent.position();
        }
        List<Step> ordered = new ArrayList<>(reverse.size());
        for (int index = reverse.size() - 1; index >= 0; index--) {
            ordered.add(reverse.get(index));
        }
        return List.copyOf(ordered);
    }

    private static double displacement(Position from, Position to) {
        long dx = (long) to.x() - from.x();
        long dy = (long) to.y() - from.y();
        long dz = (long) to.z() - from.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
