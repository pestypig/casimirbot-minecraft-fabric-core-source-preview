package com.casimirbot.helixplayer.fabric;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;

/**
 * A bounded local handoff for test agents that cannot safely type a one-time
 * pairing command into the GLFW window. The inbox is claimed atomically and
 * deleted before the credential is redeemed; it is never copied into logs.
 */
final class PlayerActionPairingInbox {
    static final String FILE_NAME = "helix-fabric-player-agent.pairing-inbox";
    static final long MAX_AGE_MILLIS = 2 * 60 * 1000L;
    static final long MAX_BYTES = 512L;

    private static final Pattern PAIR_COMMAND = Pattern.compile(
        "^/helix-player\\s+pair\\s+([A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4})(?:\\s+(\\S+))?\\s*$"
    );

    private PlayerActionPairingInbox() {}

    record PairingRequest(String code, String endpointOverride) {}

    record PollResult(PairingRequest request, String failureCode) {
        static PollResult absent() {
            return new PollResult(null, "");
        }

        static PollResult rejected(String failureCode) {
            return new PollResult(null, failureCode);
        }
    }

    static PollResult consumeDefault(long nowMillis) throws IOException {
        Path inbox = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
        return consume(inbox, nowMillis);
    }

    static PollResult consume(Path inbox, long nowMillis) throws IOException {
        if (!Files.exists(inbox, LinkOption.NOFOLLOW_LINKS)) return PollResult.absent();
        if (!Files.isRegularFile(inbox, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(inbox);
            return PollResult.rejected("player_pairing_inbox_not_regular");
        }

        Path claimed = inbox.resolveSibling(inbox.getFileName() + ".processing");
        Files.deleteIfExists(claimed);
        try {
            Files.move(
                inbox,
                claimed,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(inbox, claimed, StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            if (!Files.isRegularFile(claimed, LinkOption.NOFOLLOW_LINKS)) {
                return PollResult.rejected("player_pairing_inbox_not_regular");
            }
            if (Files.size(claimed) > MAX_BYTES) {
                return PollResult.rejected("player_pairing_inbox_too_large");
            }
            long modifiedAt = Files.getLastModifiedTime(
                claimed,
                LinkOption.NOFOLLOW_LINKS
            ).toMillis();
            if (modifiedAt > nowMillis + 5_000L || nowMillis - modifiedAt > MAX_AGE_MILLIS) {
                return PollResult.rejected("player_pairing_inbox_stale");
            }

            String command = Files.readString(claimed, StandardCharsets.UTF_8).trim();
            Matcher match = PAIR_COMMAND.matcher(command);
            if (!match.matches()) {
                return PollResult.rejected("player_pairing_inbox_invalid");
            }
            String endpoint = match.group(2);
            return new PollResult(
                new PairingRequest(
                    match.group(1),
                    endpoint == null || endpoint.isBlank() ? null : endpoint.trim()
                ),
                ""
            );
        } finally {
            Files.deleteIfExists(claimed);
        }
    }
}
