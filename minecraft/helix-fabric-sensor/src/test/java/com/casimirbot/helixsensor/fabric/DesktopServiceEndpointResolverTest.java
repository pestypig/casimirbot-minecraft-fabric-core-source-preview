package com.casimirbot.helixsensor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopServiceEndpointResolverTest {
    @TempDir Path temporaryDirectory;

    @Test
    void followsTheLiveInstalledServiceWithoutChangingTheBindingPath() throws Exception {
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
            "http://127.0.0.1:57577/api/room-ingress/v1/bindings/binding:test",
            DesktopServiceEndpointResolver.resolve(
                "http://127.0.0.1:1522/api/room-ingress/v1/bindings/binding:test",
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
            "http://127.0.0.1:1522/api/room-ingress/v1/bindings/binding:test";

        assertEquals(
            configured,
            DesktopServiceEndpointResolver.resolve(configured, receipt, ignored -> true)
        );
    }

    @Test
    void failsClosedForDeadProcessesRemoteOriginsAndNonBindingEndpoints() throws Exception {
        Path receipt = temporaryDirectory.resolve("desktop-service-ready.json");
        String configured =
            "http://127.0.0.1:1522/api/room-ingress/v1/bindings/binding:test";
        Files.writeString(
            receipt,
            """
            {"schema":"casimir_desktop_service_ready_receipt/1","ready":true,
             "origin":"https://example.com","serviceProcessId":%d}
            """.formatted(ProcessHandle.current().pid())
        );
        assertEquals(configured, DesktopServiceEndpointResolver.resolve(configured, receipt));
        assertEquals(
            "https://casimirbot.com/api/room-ingress/v1/bindings/binding:test",
            DesktopServiceEndpointResolver.resolve(
                "https://casimirbot.com/api/room-ingress/v1/bindings/binding:test",
                receipt
            )
        );
    }
}
