package com.casimirbot.helixsensor.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class BoundedNavigationFrontierTest {
    private static final BoundedNavigationFrontier.Position ORIGIN = position(0, 0, 0);

    @Test
    void findsTheClearFootholdAboveABlockedForwardCell() {
        Fixture fixture = new Fixture();
        fixture.loadBox(-3, 3, -3, 4, -3, 3);
        fixture.standable(ORIGIN);
        fixture.standable(position(0, 1, 1));
        fixture.set(position(0, 2, 0), BoundedNavigationFrontier.CellKind.CLEAR);

        BoundedNavigationFrontier.Result result = fixture.analyze(3, 3);
        BoundedNavigationFrontier.Route route = fixture.routeTo(
            result,
            position(0, 1, 1)
        );

        assertEquals(1, route.steps().size());
        assertEquals(
            BoundedNavigationFrontier.MoveKind.ASCEND,
            route.steps().get(0).kind()
        );
        assertTrue(result.evidenceComplete());
    }

    @Test
    void prefersADirectDiagonalOnlyWhenBothCornerColumnsAreClear() {
        Fixture fixture = new Fixture();
        fixture.loadBox(-3, 3, -3, 3, -3, 3);
        fixture.standable(ORIGIN);
        fixture.standable(position(1, 0, 0));
        fixture.standable(position(0, 0, 1));
        fixture.standable(position(1, 0, 1));

        BoundedNavigationFrontier.Route route = fixture.routeTo(
            fixture.analyze(2, 2),
            position(1, 0, 1)
        );
        assertEquals(1, route.steps().size());
        assertEquals(
            BoundedNavigationFrontier.MoveKind.DIAGONAL,
            route.steps().get(0).kind()
        );

        fixture.set(position(1, 1, 0), BoundedNavigationFrontier.CellKind.BLOCKED);
        route = fixture.routeTo(fixture.analyze(2, 2), position(1, 0, 1));
        assertEquals(2, route.steps().size());
        assertTrue(route.steps().stream().noneMatch(
            step -> step.kind() == BoundedNavigationFrontier.MoveKind.DIAGONAL
        ));
    }

    @Test
    void ranksTheFarthestReachableEndOfAWindingCorridor() {
        Fixture fixture = new Fixture();
        fixture.loadBox(-4, 4, -3, 3, -4, 4);
        fixture.standable(ORIGIN);
        fixture.standable(position(0, 0, 1));
        fixture.standable(position(1, 0, 1));
        fixture.standable(position(2, 0, 1));
        fixture.standable(position(2, 0, 2));

        BoundedNavigationFrontier.Result result = fixture.analyze(3, 2);
        BoundedNavigationFrontier.Route first = result.rankedFrontiers().get(0);

        assertEquals(position(2, 0, 2), first.destination());
        assertEquals(4, first.steps().size());
        assertEquals(5, result.reachableFootholdCount());
    }

    @Test
    void supportsOneBlockDescentButRejectsHazardAdjacentFootholds() {
        Fixture fixture = new Fixture();
        fixture.loadBox(-4, 4, -3, 4, -4, 4);
        BoundedNavigationFrontier.Position high = position(0, 1, 0);
        fixture.standable(high);
        fixture.standable(position(0, 0, 1));
        fixture.standable(position(1, 1, 0));
        fixture.set(position(2, 1, 0), BoundedNavigationFrontier.CellKind.HAZARD);

        BoundedNavigationFrontier.Result result = fixture.analyzeFrom(high, 3, 3);
        BoundedNavigationFrontier.Route descent = fixture.routeTo(
            result,
            position(0, 0, 1)
        );

        assertEquals(
            BoundedNavigationFrontier.MoveKind.DESCEND,
            descent.steps().get(0).kind()
        );
        assertFalse(result.rankedFrontiers().stream().anyMatch(
            route -> route.destination().equals(position(1, 1, 0))
        ));
    }

    @Test
    void reportsUnknownCoverageInsteadOfPromotingItToASafeRoute() {
        Fixture fixture = new Fixture(BoundedNavigationFrontier.CellKind.UNKNOWN);
        fixture.standable(ORIGIN);
        fixture.standable(position(0, 0, 1));

        BoundedNavigationFrontier.Result result = fixture.analyze(2, 2);

        assertFalse(result.evidenceComplete());
        assertTrue(result.rankedFrontiers().isEmpty());
    }

    @Test
    void recomputationReroutesAfterThePreferredCorridorBecomesBlocked() {
        Fixture fixture = new Fixture();
        fixture.loadBox(-4, 4, -3, 3, -4, 4);
        fixture.standable(ORIGIN);
        fixture.standable(position(1, 0, 0));
        fixture.standable(position(2, 0, 0));
        fixture.standable(position(3, 0, 0));
        fixture.standable(position(0, 0, 1));
        fixture.standable(position(0, 0, 2));

        BoundedNavigationFrontier.Result before = fixture.analyze(3, 2);
        assertEquals(position(3, 0, 0), before.rankedFrontiers().get(0).destination());

        fixture.set(position(1, 0, 0), BoundedNavigationFrontier.CellKind.BLOCKED);
        fixture.set(position(1, 1, 0), BoundedNavigationFrontier.CellKind.BLOCKED);
        BoundedNavigationFrontier.Result after = fixture.analyze(3, 2);

        assertEquals(position(0, 0, 2), after.rankedFrontiers().get(0).destination());
        assertTrue(after.rankedFrontiers().stream().noneMatch(
            route -> route.destination().equals(position(3, 0, 0))
        ));
    }

    @Test
    void plansToOneExactOwnedFrontierWaypoint() {
        Fixture fixture = new Fixture();
        fixture.loadBox(-4, 4, -3, 3, -4, 4);
        fixture.standable(ORIGIN);
        fixture.standable(position(0, 1, 1));
        fixture.set(position(0, 2, 0), BoundedNavigationFrontier.CellKind.CLEAR);

        BoundedNavigationFrontier.GoalPlan plan = BoundedNavigationFrontier.planTo(
            ORIGIN,
            position(0, 1, 1),
            3,
            3,
            fixture::cell
        );

        assertEquals(BoundedNavigationFrontier.GoalPlanStatus.ROUTE_FOUND, plan.status());
        assertEquals(
            BoundedNavigationFrontier.MoveKind.ASCEND,
            plan.route().steps().get(0).kind()
        );
    }

    @Test
    void exactGoalPlanningFailsClosedWhenTheRequiredVoxelEvidenceIsUnknown() {
        Fixture fixture = new Fixture(BoundedNavigationFrontier.CellKind.UNKNOWN);
        fixture.standable(ORIGIN);
        fixture.standable(position(0, 0, 1));

        BoundedNavigationFrontier.GoalPlan plan = BoundedNavigationFrontier.planTo(
            ORIGIN,
            position(0, 0, 1),
            2,
            2,
            fixture::cell
        );

        assertEquals(
            BoundedNavigationFrontier.GoalPlanStatus.EVIDENCE_INCOMPLETE,
            plan.status()
        );
        assertEquals(null, plan.route());
    }

    @Test
    void readsEachVoxelAtMostOncePerBoundedAnalysis() {
        Fixture fixture = new Fixture();
        fixture.loadBox(-4, 4, -3, 3, -4, 4);
        fixture.standable(ORIGIN);
        fixture.standable(position(0, 1, 1));
        fixture.standable(position(0, 1, 2));
        fixture.set(position(0, 2, 0), BoundedNavigationFrontier.CellKind.CLEAR);
        Map<BoundedNavigationFrontier.Position, Integer> reads = new HashMap<>();

        BoundedNavigationFrontier.Result result = BoundedNavigationFrontier.analyze(
            ORIGIN,
            3,
            3,
            8,
            position -> {
                reads.merge(position, 1, Integer::sum);
                return fixture.cell(position);
            }
        );

        assertTrue(result.reachableFootholdCount() > 1);
        assertTrue(reads.values().stream().allMatch(count -> count == 1));
    }

    private static BoundedNavigationFrontier.Position position(int x, int y, int z) {
        return new BoundedNavigationFrontier.Position(x, y, z);
    }

    private static final class Fixture {
        private final Map<BoundedNavigationFrontier.Position, BoundedNavigationFrontier.CellKind> cells =
            new HashMap<>();
        private final BoundedNavigationFrontier.CellKind fallback;

        private Fixture() {
            this(BoundedNavigationFrontier.CellKind.BLOCKED);
        }

        private Fixture(BoundedNavigationFrontier.CellKind fallback) {
            this.fallback = fallback;
        }

        private void loadBox(
            int minX,
            int maxX,
            int minY,
            int maxY,
            int minZ,
            int maxZ
        ) {
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        set(position(x, y, z), BoundedNavigationFrontier.CellKind.BLOCKED);
                    }
                }
            }
        }

        private void standable(BoundedNavigationFrontier.Position feet) {
            set(feet, BoundedNavigationFrontier.CellKind.CLEAR);
            set(feet.offset(0, 1, 0), BoundedNavigationFrontier.CellKind.CLEAR);
            set(feet.offset(0, -1, 0), BoundedNavigationFrontier.CellKind.SOLID);
        }

        private void set(
            BoundedNavigationFrontier.Position position,
            BoundedNavigationFrontier.CellKind kind
        ) {
            cells.put(position, kind);
        }

        private BoundedNavigationFrontier.CellKind cell(
            BoundedNavigationFrontier.Position position
        ) {
            return cells.getOrDefault(position, fallback);
        }

        private BoundedNavigationFrontier.Result analyze(int horizontal, int vertical) {
            return analyzeFrom(ORIGIN, horizontal, vertical);
        }

        private BoundedNavigationFrontier.Result analyzeFrom(
            BoundedNavigationFrontier.Position origin,
            int horizontal,
            int vertical
        ) {
            return BoundedNavigationFrontier.analyze(
                origin,
                horizontal,
                vertical,
                16,
                this::cell
            );
        }

        private BoundedNavigationFrontier.Route routeTo(
            BoundedNavigationFrontier.Result result,
            BoundedNavigationFrontier.Position destination
        ) {
            return result.rankedFrontiers().stream()
                .filter(route -> route.destination().equals(destination))
                .findFirst()
                .orElseThrow();
        }
    }
}
