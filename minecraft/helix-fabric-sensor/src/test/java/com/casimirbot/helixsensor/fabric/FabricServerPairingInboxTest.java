package com.casimirbot.helixsensor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FabricServerPairingInboxTest {
    @TempDir
    Path tempDir;

    @Test
    void atomicallyConsumesAndDeletesAValidServerPairingCommand() throws Exception {
        Path inbox = tempDir.resolve(FabricServerPairingInbox.FILE_NAME);
        Files.writeString(inbox, "/helix pair Z4ZD-X2JJ");

        FabricServerPairingInbox.PollResult result =
            FabricServerPairingInbox.consume(inbox, System.currentTimeMillis());

        assertEquals("Z4ZD-X2JJ", result.code());
        assertNull(result.pairingEndpoint());
        assertFalse(Files.exists(inbox));
        assertFalse(
            Files.exists(inbox.resolveSibling(inbox.getFileName() + ".processing"))
        );
    }

    @Test
    void consumesCurrentLoopbackEndpointFromOpaqueEnvelope() throws Exception {
        Path inbox = tempDir.resolve(FabricServerPairingInbox.FILE_NAME);
        Files.writeString(
            inbox,
            "{\"schema\":\"casimirbot.local_server_pairing_handoff.v1\"," +
            "\"command\":\"/helix pair Z4ZD-X2JJ\"," +
            "\"pairing_endpoint\":\"http://127.0.0.1:60826/api/environment-connectors/v1/pairing/redeem\"}"
        );

        FabricServerPairingInbox.PollResult result =
            FabricServerPairingInbox.consume(inbox, System.currentTimeMillis());

        assertEquals("Z4ZD-X2JJ", result.code());
        assertEquals(
            "http://127.0.0.1:60826/api/environment-connectors/v1/pairing/redeem",
            result.pairingEndpoint()
        );
        assertFalse(Files.exists(inbox));
    }

    @Test
    void rejectsEndpointEnvelopeOutsideExactLoopbackRedeemRoute() throws Exception {
        Path inbox = tempDir.resolve(FabricServerPairingInbox.FILE_NAME);
        Files.writeString(
            inbox,
            "{\"schema\":\"casimirbot.local_server_pairing_handoff.v1\"," +
            "\"command\":\"/helix pair Z4ZD-X2JJ\"," +
            "\"pairing_endpoint\":\"https://example.com/api/environment-connectors/v1/pairing/redeem\"}"
        );

        FabricServerPairingInbox.PollResult result =
            FabricServerPairingInbox.consume(inbox, System.currentTimeMillis());

        assertNull(result.code());
        assertEquals("server_pairing_inbox_invalid", result.failureCode());
        assertFalse(Files.exists(inbox));
    }

    @Test
    void rejectsAndDeletesMalformedInputWithoutEchoingIt() throws Exception {
        Path inbox = tempDir.resolve(FabricServerPairingInbox.FILE_NAME);
        Files.writeString(inbox, "/say definitely-not-a-pairing-command");

        FabricServerPairingInbox.PollResult result =
            FabricServerPairingInbox.consume(inbox, System.currentTimeMillis());

        assertNull(result.code());
        assertEquals("server_pairing_inbox_invalid", result.failureCode());
        assertFalse(Files.exists(inbox));
    }

    @Test
    void rejectsAndDeletesStalePairingMaterial() throws Exception {
        Path inbox = tempDir.resolve(FabricServerPairingInbox.FILE_NAME);
        Files.writeString(inbox, "/helix pair Z4ZD-X2JJ");
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(
            inbox,
            FileTime.fromMillis(now - FabricServerPairingInbox.MAX_AGE_MILLIS - 1)
        );

        FabricServerPairingInbox.PollResult result =
            FabricServerPairingInbox.consume(inbox, now);

        assertNull(result.code());
        assertEquals("server_pairing_inbox_stale", result.failureCode());
        assertFalse(Files.exists(inbox));
    }

    @Test
    void acceptsAOneTimeHandoffAfterAMeasuredColdServerStartup() throws Exception {
        Path inbox = tempDir.resolve(FabricServerPairingInbox.FILE_NAME);
        Files.writeString(inbox, "/helix pair Z4ZD-X2JJ");
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(
            inbox,
            FileTime.fromMillis(now - 3 * 60 * 1000L)
        );

        FabricServerPairingInbox.PollResult result =
            FabricServerPairingInbox.consume(inbox, now);

        assertEquals("Z4ZD-X2JJ", result.code());
        assertFalse(Files.exists(inbox));
    }
}
