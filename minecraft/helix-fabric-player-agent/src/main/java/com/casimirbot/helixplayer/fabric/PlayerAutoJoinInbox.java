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
 * One-shot, loopback-only handoff into Minecraft's native connection screen.
 * This is deliberately not a launcher or host-process capability: it exists
 * only after the Fabric client has started, carries no account material, and
 * is atomically consumed before a connection is attempted.
 */
final class PlayerAutoJoinInbox {
    static final String FILE_NAME = "helix-fabric-player-agent.autojoin-inbox";
    static final long MAX_AGE_MILLIS = 2 * 60 * 1000L;
    static final long MAX_BYTES = 256L;

    private static final Pattern COMMAND = Pattern.compile(
        "^/helix-player\\s+autojoin\\s+" +
            "(localhost|127\\.0\\.0\\.1|\\[::1\\])(?::([0-9]{1,5}))?\\s*$",
        Pattern.CASE_INSENSITIVE
    );

    private PlayerAutoJoinInbox() {}

    record AutoJoinRequest(String address) {}

    record PollResult(AutoJoinRequest request, String failureCode) {
        static PollResult absent() {
            return new PollResult(null, "");
        }

        static PollResult rejected(String failureCode) {
            return new PollResult(null, failureCode);
        }
    }

    static PollResult consumeDefault(long nowMillis) throws IOException {
        return consume(
            FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME),
            nowMillis
        );
    }

    static PollResult consume(Path inbox, long nowMillis) throws IOException {
        if (!Files.exists(inbox, LinkOption.NOFOLLOW_LINKS)) return PollResult.absent();
        if (!Files.isRegularFile(inbox, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(inbox);
            return PollResult.rejected("player_autojoin_inbox_not_regular");
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
                return PollResult.rejected("player_autojoin_inbox_not_regular");
            }
            if (Files.size(claimed) > MAX_BYTES) {
                return PollResult.rejected("player_autojoin_inbox_too_large");
            }
            long modifiedAt = Files.getLastModifiedTime(
                claimed,
                LinkOption.NOFOLLOW_LINKS
            ).toMillis();
            if (modifiedAt > nowMillis + 5_000L || nowMillis - modifiedAt > MAX_AGE_MILLIS) {
                return PollResult.rejected("player_autojoin_inbox_stale");
            }
            Matcher match = COMMAND.matcher(
                Files.readString(claimed, StandardCharsets.UTF_8).trim()
            );
            if (!match.matches()) {
                return PollResult.rejected("player_autojoin_inbox_invalid");
            }
            int port = match.group(2) == null
                ? 25565
                : Integer.parseInt(match.group(2));
            if (port < 1 || port > 65_535) {
                return PollResult.rejected("player_autojoin_port_invalid");
            }
            String host = match.group(1).toLowerCase(java.util.Locale.ROOT);
            return new PollResult(new AutoJoinRequest(host + ":" + port), "");
        } finally {
            Files.deleteIfExists(claimed);
        }
    }
}
