package com.casimirbot.helixplayer.fabric;

import com.casimirbot.helixsensor.HelixJson;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.IntPredicate;

/**
 * Follows the installed desktop service's per-launch loopback origin without
 * moving a session secret into Minecraft. The authenticated endpoint path and
 * the connector credential remain unchanged.
 */
final class InstalledDesktopServiceEndpointResolver {
    private static final String RECEIPT_SCHEMA =
        "casimir_desktop_service_ready_receipt/1";
    private static final String RECEIPT_OVERRIDE_ENV =
        "CASIMIRBOT_DESKTOP_READY_RECEIPT";
    private static final String RECEIPT_RELATIVE_PATH =
        "@casimirbot/desktop/state/desktop-service-ready.json";

    private InstalledDesktopServiceEndpointResolver() {}

    static String resolve(String configuredEndpoint) {
        return resolve(configuredEndpoint, defaultReceiptPath());
    }

    static String resolve(String configuredEndpoint, Path receiptPath) {
        return resolve(
            configuredEndpoint,
            receiptPath,
            InstalledDesktopServiceEndpointResolver::acceptsLoopbackConnection
        );
    }

    static String resolve(
        String configuredEndpoint,
        Path receiptPath,
        IntPredicate configuredPortReachable
    ) {
        URI configured = exactLoopbackEndpoint(configuredEndpoint);
        if (configured == null || receiptPath == null || !Files.isRegularFile(receiptPath)) {
            return configuredEndpoint;
        }
        // Pairing establishes one exact service/database epoch. Keep that
        // configured endpoint authoritative while it remains reachable; a
        // simultaneously running packaged desktop receipt is only a fallback
        // after the paired endpoint disappears.
        if (configuredPortReachable.test(configured.getPort())) {
            return configuredEndpoint;
        }
        try {
            Map<String, Object> receipt = HelixJson.asObject(
                HelixJson.parse(Files.readString(receiptPath, StandardCharsets.UTF_8))
            );
            if (
                !RECEIPT_SCHEMA.equals(receipt.get("schema")) ||
                !Boolean.TRUE.equals(receipt.get("ready")) ||
                !(receipt.get("origin") instanceof String origin) ||
                !(receipt.get("serviceProcessId") instanceof Number processNumber)
            ) {
                return configuredEndpoint;
            }
            long processId = processNumber.longValue();
            if (
                processId <= 0L ||
                !ProcessHandle.of(processId).map(ProcessHandle::isAlive).orElse(false)
            ) {
                return configuredEndpoint;
            }
            URI currentOrigin = exactInstalledOrigin(origin);
            if (currentOrigin == null) return configuredEndpoint;
            return new URI(
                "http",
                null,
                "127.0.0.1",
                currentOrigin.getPort(),
                configured.getPath(),
                null,
                null
            ).toString();
        } catch (IOException | RuntimeException | URISyntaxException ignored) {
            return configuredEndpoint;
        }
    }

    private static boolean acceptsLoopbackConnection(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 150);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static Path defaultReceiptPath() {
        String override = System.getenv(RECEIPT_OVERRIDE_ENV);
        if (override != null && !override.isBlank()) return Path.of(override.trim());
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.isBlank()) return null;
        return Path.of(appData).resolve(RECEIPT_RELATIVE_PATH);
    }

    private static URI exactLoopbackEndpoint(String value) {
        try {
            URI endpoint = URI.create(value);
            String host = endpoint.getHost();
            String path = endpoint.getPath();
            return "http".equals(endpoint.getScheme()) &&
                ("127.0.0.1".equals(host) || "localhost".equals(host)) &&
                endpoint.getPort() > 0 && endpoint.getPort() <= 65_535 &&
                endpoint.getRawUserInfo() == null &&
                endpoint.getRawQuery() == null &&
                endpoint.getRawFragment() == null &&
                path != null && path.startsWith("/") && !path.startsWith("//")
                    ? endpoint
                    : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static URI exactInstalledOrigin(String value) {
        try {
            URI origin = URI.create(value);
            return "http".equals(origin.getScheme()) &&
                "127.0.0.1".equals(origin.getHost()) &&
                origin.getPort() > 0 && origin.getPort() <= 65_535 &&
                origin.getRawUserInfo() == null &&
                origin.getRawQuery() == null &&
                origin.getRawFragment() == null &&
                (origin.getPath() == null || origin.getPath().isEmpty() || "/".equals(origin.getPath()))
                    ? origin
                    : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
