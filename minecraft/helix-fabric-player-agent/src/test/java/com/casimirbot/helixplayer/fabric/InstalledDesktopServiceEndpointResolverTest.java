package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class InstalledDesktopServiceEndpointResolverTest {
    @TempDir Path temporaryDirectory;

    @Test
    void followsTheLiveInstalledServiceForPairingAndActionPaths() throws Exception {
        Path receipt = temporaryDirectory.resolve("desktop-service-ready.json");
        Files.writeString(
            receipt,
            """
            {
              "schema":"casimir_desktop_service_ready_receipt/1",
              "ready":true,
              "origin":"http://127.0.0.1:57577",
              "serviceProcessId":%d
            }
            """.formatted(ProcessHandle.current().pid())
        );

        assertEquals(
            "http://127.0.0.1:57577/api/environment-connectors/v1/pairing/redeem",
            InstalledDesktopServiceEndpointResolver.resolve(
                "http://localhost:1522/api/environment-connectors/v1/pairing/redeem",
                receipt,
                ignored -> false
            )
        );
        assertEquals(
            "http://127.0.0.1:57577/api/environment-actions/v1/authorities/authority:test",
            InstalledDesktopServiceEndpointResolver.resolve(
                "http://127.0.0.1:1522/api/environment-actions/v1/authorities/authority:test",
                receipt,
                ignored -> false
            )
        );
    }

    @Test
    void preservesAReachablePairedEndpointInsteadOfSplittingServiceEpochs() throws Exception {
        Path receipt = temporaryDirectory.resolve("desktop-service-ready.json");
        Files.writeString(
            receipt,
            """
            {"schema":"casimir_desktop_service_ready_receipt/1","ready":true,
             "origin":"http://127.0.0.1:57577","serviceProcessId":%d}
            """.formatted(ProcessHandle.current().pid())
        );
        String configured =
            "http://127.0.0.1:1522/api/environment-connectors/v1/pairing/redeem";

        assertEquals(
            configured,
            InstalledDesktopServiceEndpointResolver.resolve(
                configured,
                receipt,
                ignored -> true
            )
        );
    }

    @Test
    void failsClosedForRemoteReceiptOriginsAndRemoteConfiguredEndpoints() throws Exception {
        Path receipt = temporaryDirectory.resolve("desktop-service-ready.json");
        Files.writeString(
            receipt,
            """
            {"schema":"casimir_desktop_service_ready_receipt/1","ready":true,
             "origin":"https://example.com","serviceProcessId":%d}
            """.formatted(ProcessHandle.current().pid())
        );
        String local = "http://localhost:1522/api/environment-connectors/v1/pairing/redeem";
        String remote = "https://casimirbot.com/api/environment-connectors/v1/pairing/redeem";
        assertEquals(local, InstalledDesktopServiceEndpointResolver.resolve(local, receipt));
        assertEquals(remote, InstalledDesktopServiceEndpointResolver.resolve(remote, receipt));
    }
}
