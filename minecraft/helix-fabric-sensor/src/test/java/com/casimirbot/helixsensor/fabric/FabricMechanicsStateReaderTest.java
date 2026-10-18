package com.casimirbot.helixsensor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class FabricMechanicsStateReaderTest {
    @Test
    void mapsDocumentedCrimsonCursePointThresholdsToPhases() {
        assertEquals(-1, FabricMechanicsStateReader.phaseForPoints(Integer.MIN_VALUE));
        assertEquals(0, FabricMechanicsStateReader.phaseForPoints(-1));
        assertEquals(1, FabricMechanicsStateReader.phaseForPoints(0));
        assertEquals(1, FabricMechanicsStateReader.phaseForPoints(9));
        assertEquals(2, FabricMechanicsStateReader.phaseForPoints(10));
        assertEquals(2, FabricMechanicsStateReader.phaseForPoints(39));
        assertEquals(3, FabricMechanicsStateReader.phaseForPoints(40));
        assertEquals(4, FabricMechanicsStateReader.phaseForPoints(150));
        assertEquals(5, FabricMechanicsStateReader.phaseForPoints(300));
    }
}
