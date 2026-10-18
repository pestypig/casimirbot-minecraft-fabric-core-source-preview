package com.casimirbot.helixsensor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FabricCommandRuntimeTest {
    @Test
    void boundsCatalogRepublishAcrossManifestAdmissions() {
        long interval = FabricCommandRuntime.CATALOG_REPUBLISH_INTERVAL_MILLIS;

        assertTrue(FabricCommandRuntime.catalogRepublishDue(0L, 1L));
        assertFalse(
            FabricCommandRuntime.catalogRepublishDue(10_000L, 10_000L + interval - 1L)
        );
        assertTrue(
            FabricCommandRuntime.catalogRepublishDue(10_000L, 10_000L + interval)
        );
        assertTrue(FabricCommandRuntime.catalogRepublishDue(20_000L, 19_999L));
    }

    @Test
    void reportsOnlyBoundedCatalogFailureMetadata() {
        assertEquals(
            "http_409_command_authority_inactive",
            FabricCommandRuntime.catalogFailureSummary(
                new FabricCommandHttpClient.Response(
                    409,
                    "{\"error\":\"command_authority_inactive\",\"message\":\"do not log me\"}"
                ),
                null
            )
        );
        assertEquals(
            "http_500_unspecified",
            FabricCommandRuntime.catalogFailureSummary(
                new FabricCommandHttpClient.Response(
                    500,
                    "{\"error\":\"unsafe value with spaces\",\"secret\":\"hidden\"}"
                ),
                null
            )
        );
        assertEquals(
            "transport_error",
            FabricCommandRuntime.catalogFailureSummary(
                null,
                new IllegalStateException("credential-bearing detail")
            )
        );
    }

    @Test
    void usesTheCanonicalCatalogIdReturnedForAReplay() {
        assertEquals(
            "command_catalog:restored",
            FabricCommandRuntime.catalogIdFromReceipt(
                """
                {
                  "schema":"helix.environment_command.catalog_receipt.v1",
                  "ok":true,
                  "command_catalog_id":"command_catalog:restored",
                  "replayed":true
                }
                """,
                "command_catalog:new-attempt"
            )
        );
    }

    @Test
    void acceptsANewCatalogOnlyWhenTheReceiptMatchesTheProposal() {
        assertEquals(
            "command_catalog:new-attempt",
            FabricCommandRuntime.catalogIdFromReceipt(
                """
                {
                  "schema":"helix.environment_command.catalog_receipt.v1",
                  "ok":true,
                  "command_catalog_id":"command_catalog:new-attempt",
                  "replayed":false
                }
                """,
                "command_catalog:new-attempt"
            )
        );
        assertNull(
            FabricCommandRuntime.catalogIdFromReceipt(
                """
                {
                  "schema":"helix.environment_command.catalog_receipt.v1",
                  "ok":true,
                  "command_catalog_id":"command_catalog:other",
                  "replayed":false
                }
                """,
                "command_catalog:new-attempt"
            )
        );
    }

    @Test
    void rejectsInvalidOrFailedCatalogReceipts() {
        assertNull(FabricCommandRuntime.catalogIdFromReceipt("{}", "command_catalog:new"));
        assertNull(FabricCommandRuntime.catalogIdFromReceipt("not-json", "command_catalog:new"));
    }
}
