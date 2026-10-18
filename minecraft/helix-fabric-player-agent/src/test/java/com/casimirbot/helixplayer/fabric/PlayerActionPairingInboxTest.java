package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PlayerActionPairingInboxTest {
    @TempDir
    Path tempDir;

    @Test
    void atomicallyConsumesAndDeletesAValidLocalPairingCommand() throws Exception {
        Path inbox = tempDir.resolve(PlayerActionPairingInbox.FILE_NAME);
        Files.writeString(inbox, "/helix-player pair Z4ZD-X2JJ");

        PlayerActionPairingInbox.PollResult result =
            PlayerActionPairingInbox.consume(inbox, System.currentTimeMillis());

        assertEquals("Z4ZD-X2JJ", result.request().code());
        assertNull(result.request().endpointOverride());
        assertFalse(Files.exists(inbox));
        assertFalse(Files.exists(inbox.resolveSibling(inbox.getFileName() + ".processing")));
    }

    @Test
    void preservesAnExplicitLoopbackEndpointForTheExistingSafetyValidator() throws Exception {
        Path inbox = tempDir.resolve(PlayerActionPairingInbox.FILE_NAME);
        Files.writeString(
            inbox,
            "/helix-player pair Z4ZD-X2JJ http://localhost:1522/api/environment-connectors/v1/pairing/redeem"
        );

        PlayerActionPairingInbox.PollResult result =
            PlayerActionPairingInbox.consume(inbox, System.currentTimeMillis());

        assertEquals(
            "http://localhost:1522/api/environment-connectors/v1/pairing/redeem",
            result.request().endpointOverride()
        );
        assertFalse(Files.exists(inbox));
    }

    @Test
    void rejectsAndDeletesMalformedInputWithoutEchoingIt() throws Exception {
        Path inbox = tempDir.resolve(PlayerActionPairingInbox.FILE_NAME);
        Files.writeString(inbox, "/say definitely-not-a-pairing-command");

        PlayerActionPairingInbox.PollResult result =
            PlayerActionPairingInbox.consume(inbox, System.currentTimeMillis());

        assertNull(result.request());
        assertEquals("player_pairing_inbox_invalid", result.failureCode());
        assertFalse(Files.exists(inbox));
    }

    @Test
    void rejectsAndDeletesStalePairingMaterial() throws Exception {
        Path inbox = tempDir.resolve(PlayerActionPairingInbox.FILE_NAME);
        Files.writeString(inbox, "/helix-player pair Z4ZD-X2JJ");
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(
            inbox,
            FileTime.fromMillis(now - PlayerActionPairingInbox.MAX_AGE_MILLIS - 1)
        );

        PlayerActionPairingInbox.PollResult result =
            PlayerActionPairingInbox.consume(inbox, now);

        assertNull(result.request());
        assertEquals("player_pairing_inbox_stale", result.failureCode());
        assertFalse(Files.exists(inbox));
    }
}
