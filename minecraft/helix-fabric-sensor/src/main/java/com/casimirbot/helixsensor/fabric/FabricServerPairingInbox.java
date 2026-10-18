package com.casimirbot.helixsensor.fabric;

import com.casimirbot.helixsensor.HelixJson;
import com.casimirbot.helixsensor.pairing.ConnectorPairingClient;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Bounded same-host handoff for a one-time World Authority pairing command.
 * The claimed file is deleted before redemption and the code is never logged.
 */
final class FabricServerPairingInbox {
    static final String FILE_NAME = "helix-fabric-sensor.pairing-inbox";
    // A cold Fabric launch can exceed two minutes before the first server tick.
    // Stay below the source claim's five-minute window, and keep one-time
    // consumption and server-side pairing expiry as independent limits.
    static final long MAX_AGE_MILLIS = 4 * 60 * 1000L;
    static final long MAX_BYTES = 512L;

    private static final Pattern PAIR_COMMAND = Pattern.compile(
        "^/helix\\s+pair\\s+([A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4})\\s*$"
    );
    private static final String ENVELOPE_SCHEMA =
        "casimirbot.local_server_pairing_handoff.v1";

    private FabricServerPairingInbox() {}

    record PollResult(String code, String pairingEndpoint, String failureCode) {
        static PollResult absent() {
            return new PollResult(null, null, "");
        }

        static PollResult rejected(String failureCode) {
            return new PollResult(null, null, failureCode);
        }
    }

    static PollResult consumeDefault(long nowMillis) throws IOException {
        return consume(
            FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME),
            nowMillis
        );
    }

    static PollResult consume(Path inbox, long nowMillis) throws IOException {
        if (!Files.exists(inbox, LinkOption.NOFOLLOW_LINKS)) {
            return PollResult.absent();
        }
        if (!Files.isRegularFile(inbox, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(inbox);
            return PollResult.rejected("server_pairing_inbox_not_regular");
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
                return PollResult.rejected("server_pairing_inbox_not_regular");
            }
            if (Files.size(claimed) > MAX_BYTES) {
                return PollResult.rejected("server_pairing_inbox_too_large");
            }
            long modifiedAt = Files.getLastModifiedTime(
                claimed,
                LinkOption.NOFOLLOW_LINKS
            ).toMillis();
            if (
                modifiedAt > nowMillis + 5_000L ||
                nowMillis - modifiedAt > MAX_AGE_MILLIS
            ) {
                return PollResult.rejected("server_pairing_inbox_stale");
            }

            String payload = Files.readString(
                claimed,
                StandardCharsets.UTF_8
            ).trim();
            Matcher legacy = PAIR_COMMAND.matcher(payload);
            if (legacy.matches()) {
                return new PollResult(legacy.group(1), null, "");
            }
            try {
                Map<String, Object> envelope = HelixJson.asObject(
                    HelixJson.parse(payload)
                );
                if (
                    envelope.size() != 3 ||
                    !ENVELOPE_SCHEMA.equals(envelope.get("schema")) ||
                    !(envelope.get("command") instanceof String command) ||
                    !(envelope.get("pairing_endpoint") instanceof String endpoint)
                ) return PollResult.rejected("server_pairing_inbox_invalid");
                Matcher commandMatch = PAIR_COMMAND.matcher(command.trim());
                if (!commandMatch.matches() || !validLoopbackEndpoint(endpoint)) {
                    return PollResult.rejected("server_pairing_inbox_invalid");
                }
                return new PollResult(commandMatch.group(1), endpoint, "");
            } catch (RuntimeException ignored) {
                return PollResult.rejected("server_pairing_inbox_invalid");
            }
        } finally {
            Files.deleteIfExists(claimed);
        }
    }

    private static boolean validLoopbackEndpoint(String value) {
        try {
            URI endpoint = URI.create(value);
            return "http".equals(endpoint.getScheme()) &&
                "127.0.0.1".equals(endpoint.getHost()) &&
                endpoint.getPort() > 0 &&
                endpoint.getPort() <= 65_535 &&
                ConnectorPairingClient.REDEEM_PATH.equals(endpoint.getPath()) &&
                endpoint.getRawUserInfo() == null &&
                endpoint.getRawQuery() == null &&
                endpoint.getRawFragment() == null;
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
