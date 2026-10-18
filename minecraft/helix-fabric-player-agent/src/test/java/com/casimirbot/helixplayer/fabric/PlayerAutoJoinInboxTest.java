package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PlayerAutoJoinInboxTest {
    @TempDir
    Path tempDir;

    @Test
    void atomicallyConsumesOneLoopbackJoinRequest() throws Exception {
        Path inbox = tempDir.resolve(PlayerAutoJoinInbox.FILE_NAME);
        Files.writeString(inbox, "/helix-player autojoin localhost:25565");

        PlayerAutoJoinInbox.PollResult result =
            PlayerAutoJoinInbox.consume(inbox, System.currentTimeMillis());

        assertEquals("localhost:25565", result.request().address());
        assertFalse(Files.exists(inbox));
        assertFalse(Files.exists(inbox.resolveSibling(inbox.getFileName() + ".processing")));
    }

    @Test
    void suppliesTheMinecraftDefaultPortWithoutBroadeningTheHost() throws Exception {
        Path inbox = tempDir.resolve(PlayerAutoJoinInbox.FILE_NAME);
        Files.writeString(inbox, "/helix-player autojoin 127.0.0.1");

        PlayerAutoJoinInbox.PollResult result =
            PlayerAutoJoinInbox.consume(inbox, System.currentTimeMillis());

        assertEquals("127.0.0.1:25565", result.request().address());
    }

    @Test
    void rejectsRemoteOrOutOfRangeDestinations() throws Exception {
        Path remote = tempDir.resolve("remote-inbox");
        Files.writeString(remote, "/helix-player autojoin example.com:25565");
        PlayerAutoJoinInbox.PollResult remoteResult =
            PlayerAutoJoinInbox.consume(remote, System.currentTimeMillis());
        assertNull(remoteResult.request());
        assertEquals("player_autojoin_inbox_invalid", remoteResult.failureCode());

        Path badPort = tempDir.resolve("port-inbox");
        Files.writeString(badPort, "/helix-player autojoin localhost:65536");
        PlayerAutoJoinInbox.PollResult portResult =
            PlayerAutoJoinInbox.consume(badPort, System.currentTimeMillis());
        assertNull(portResult.request());
        assertEquals("player_autojoin_port_invalid", portResult.failureCode());
    }

    @Test
    void rejectsAStaleJoinRequest() throws Exception {
        Path inbox = tempDir.resolve(PlayerAutoJoinInbox.FILE_NAME);
        Files.writeString(inbox, "/helix-player autojoin localhost:25565");
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(
            inbox,
            FileTime.fromMillis(now - PlayerAutoJoinInbox.MAX_AGE_MILLIS - 1)
        );

        PlayerAutoJoinInbox.PollResult result = PlayerAutoJoinInbox.consume(inbox, now);

        assertNull(result.request());
        assertEquals("player_autojoin_inbox_stale", result.failureCode());
        assertFalse(Files.exists(inbox));
    }
}
