package com.casimirbot.helixsensor.fabric;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FabricConnectorManagementBoundaryTest {
    @Test
    void excludesHumanConnectorManagementFromAgentCommandSurface() {
        assertTrue(FabricCommandCatalogBuilder.isConnectorManagementRoot("helix"));
        assertTrue(
            FabricCommandExecutor.CONNECTOR_MANAGEMENT_OUTCOME.equals(
                "connector_management_forbidden"
            )
        );
        assertTrue(
            FabricCommandCatalogBuilder.isConnectorManagementRoot(
                "helix_fabric_sensor:helix"
            )
        );
        assertFalse(FabricCommandCatalogBuilder.isConnectorManagementRoot("help"));
        assertFalse(FabricCommandCatalogBuilder.isConnectorManagementRoot("execute"));
    }
}
