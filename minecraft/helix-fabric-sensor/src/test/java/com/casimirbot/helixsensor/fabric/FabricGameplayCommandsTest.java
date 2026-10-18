package com.casimirbot.helixsensor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

final class FabricGameplayCommandsTest {
    @Test
    void gameplayNamespaceIsCatalogVisibleAndNotConnectorManagement() {
        assertEquals("helixgame", FabricGameplayCommands.ROOT);
        assertFalse(
            FabricCommandCatalogBuilder.isConnectorManagementRoot(
                FabricGameplayCommands.ROOT
            )
        );
        FabricCommandClassifier.Classification classification =
            FabricCommandClassifier.classify("helixgame ping");
        assertEquals("query", classification.category());
        assertEquals("read_only", classification.effectClass());
    }

    @Test
    void classifiesCheckpointAndFallRescuePrimitivesByTheirActualEffects() {
        assertEquals(
            new FabricCommandClassifier.Classification("query", "read_only"),
            FabricCommandClassifier.classify("helixgame checkpoint status")
        );
        assertEquals(
            new FabricCommandClassifier.Classification(
                "server_administration",
                "server_administration"
            ),
            FabricCommandClassifier.classify("helixgame checkpoint capture cottage 7 5")
        );
        assertEquals(
            new FabricCommandClassifier.Classification(
                "server_administration",
                "server_administration"
            ),
            FabricCommandClassifier.classify(
                "helixgame checkpoint capture_box cottage -53 68 0 -49 70 0"
            )
        );
        assertEquals(
            new FabricCommandClassifier.Classification(
                "world_build",
                "world_mutation"
            ),
            FabricCommandClassifier.classify("helixgame checkpoint restore cottage")
        );
        assertEquals(
            new FabricCommandClassifier.Classification(
                "world_build",
                "world_mutation"
            ),
            FabricCommandClassifier.classify("helixgame fall_rescue arm 30")
        );
        assertEquals(
            new FabricCommandClassifier.Classification("query", "read_only"),
            FabricCommandClassifier.classify("helixgame combat_watchdog status DatDamPig")
        );
        assertEquals(
            new FabricCommandClassifier.Classification(
                "entity_control",
                "world_mutation"
            ),
            FabricCommandClassifier.classify(
                "helixgame combat_watchdog arm DatDamPig helix_c1 12 24 30"
            )
        );
    }
}
