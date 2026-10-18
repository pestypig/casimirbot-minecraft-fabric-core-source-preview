package com.casimirbot.helixsensor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FabricRegionCheckpointStoreTest {
    @Test
    void computesBoundedSymmetricCheckpointVolumes() {
        assertEquals(27, FabricRegionCheckpointStore.volume(1, 1));
        assertEquals(1_331, FabricRegionCheckpointStore.volume(5, 5));
        assertTrue(
            FabricRegionCheckpointStore.volume(
                FabricRegionCheckpointStore.MAX_HORIZONTAL_RADIUS,
                FabricRegionCheckpointStore.MAX_VERTICAL_RADIUS
            ) <= FabricRegionCheckpointStore.MAX_BLOCKS
        );
    }

    @Test
    void computesExactInclusiveBoxVolumesWithoutCoordinateOrderBias() {
        assertEquals(
            15L,
            FabricRegionCheckpointStore.boxVolume(-53, 68, 0, -49, 70, 0)
        );
        assertEquals(
            15L,
            FabricRegionCheckpointStore.boxVolume(-49, 70, 0, -53, 68, 0)
        );
        assertEquals(
            Long.MAX_VALUE,
            FabricRegionCheckpointStore.boxVolume(
                Integer.MIN_VALUE,
                Integer.MIN_VALUE,
                Integer.MIN_VALUE,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE,
                Integer.MAX_VALUE
            )
        );
    }
}
