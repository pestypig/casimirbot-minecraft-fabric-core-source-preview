package com.casimirbot.helixplayer.fabric;

import com.casimirbot.helixsensor.combat.ProjectileThreatForecaster;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class ProjectileThreatForecasterTest {
    private static final ProjectileThreatForecaster.Box ACTOR =
        new ProjectileThreatForecaster.Box(-0.3, 64, -0.3, 0.3, 65.8, 0.3);

    @Test
    void labelsFrontRearAndSideSweepsAsCollisions() {
        for (ProjectileThreatForecaster.Input input : new ProjectileThreatForecaster.Input[] {
            arrow(-6, 65, 0, 1, 0, 0, true, null),
            arrow(6, 65, 0, -1, 0, 0, true, null),
            arrow(0, 65, -6, 0, 0, 1, true, null),
            arrow(0, 65, 6, 0, 0, -1, true, null),
        }) {
            ProjectileThreatForecaster.Forecast result =
                ProjectileThreatForecaster.forecast(input);
            assertEquals(ProjectileThreatForecaster.ThreatClassification.COLLISION,
                result.classification());
            assertNotNull(result.predictedCollisionTick());
            assertNotNull(result.predictedImpactPosition());
        }
    }

    @Test
    void separatesNearMissFromCollision() {
        ProjectileThreatForecaster.Forecast result = ProjectileThreatForecaster.forecast(
            arrow(-6, 65, 0.8, 1, 0, 0, true, null)
        );
        assertEquals(ProjectileThreatForecaster.ThreatClassification.NEAR_MISS,
            result.classification());
        assertNull(result.predictedCollisionTick());
        assertNotNull(result.nearMissTick());
    }

    @Test
    void labelsVerifiedOcclusionBeforeImpactSafe() {
        ProjectileThreatForecaster.Forecast result = ProjectileThreatForecaster.forecast(
            arrow(-6, 65, 0, 1, 0, 0, true, 3)
        );
        assertEquals(ProjectileThreatForecaster.ThreatClassification.SAFE,
            result.classification());
        assertEquals(6, result.predictedCollisionTick());
        assertEquals(true, result.occluded());
    }

    @Test
    void neverLabelsIncompleteEvidenceSafe() {
        ProjectileThreatForecaster.Forecast result = ProjectileThreatForecaster.forecast(
            arrow(-6, 65, 4, 1, 0, 0, false, null)
        );
        assertEquals(ProjectileThreatForecaster.ThreatClassification.UNKNOWN,
            result.classification());
        assertEquals(false, result.evidenceComplete());
    }

    @Test
    void appliesVanillaArrowDragThenGravity() {
        ProjectileThreatForecaster.Forecast result = ProjectileThreatForecaster.forecast(
            arrow(-6, 70, 0, 1, 0, 0, true, null)
        );
        ProjectileThreatForecaster.Sample tickOne = result.samples().get(1);
        assertEquals(-5, tickOne.position().x(), 1.0e-9);
        assertEquals(0.99, tickOne.velocity().x(), 1.0e-9);
        assertEquals(-0.05, tickOne.velocity().y(), 1.0e-9);
    }

    @Test
    void rejectsInvalidSupportAndOcclusionTicks() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectileThreatForecaster.Input(
            new ProjectileThreatForecaster.Vector(0, 0, 0),
            new ProjectileThreatForecaster.Vector(0, 0, 0),
            new ProjectileThreatForecaster.Vector(0, -0.05, 0),
            0.99, 0, ACTOR, 0.75, true, null
        ));
        assertThrows(IllegalArgumentException.class, () -> arrow(
            -6, 65, 0, 1, 0, 0, true, 21
        ));
    }

    private static ProjectileThreatForecaster.Input arrow(
        double x,
        double y,
        double z,
        double vx,
        double vy,
        double vz,
        boolean evidenceComplete,
        Integer verifiedOcclusionTick
    ) {
        return new ProjectileThreatForecaster.Input(
            new ProjectileThreatForecaster.Vector(x, y, z),
            new ProjectileThreatForecaster.Vector(vx, vy, vz),
            new ProjectileThreatForecaster.Vector(0, -0.05, 0),
            0.99,
            20,
            ACTOR,
            0.75,
            evidenceComplete,
            verifiedOcclusionTick
        );
    }
}
