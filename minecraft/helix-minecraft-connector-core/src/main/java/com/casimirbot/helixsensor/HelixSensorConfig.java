package com.casimirbot.helixsensor;

import com.casimirbot.helixsensor.scope.SensorScope;
import com.casimirbot.helixsensor.scope.SensorScopePolicy;
import java.net.URI;

public record HelixSensorConfig(
    boolean enabled,
    String endpoint,
    String bearerToken,
    String sourceId,
    String roomId,
    String worldId,
    String domainAdapter,
    String sourceLabel,
    int snapshotIntervalTicks,
    int heartbeatIntervalTicks,
    int probePollIntervalTicks,
    int burstIntervalTicks,
    int burstDurationTicks,
    boolean sendOnlyChangedSections,
    boolean includeSectionHashes,
    int maxPayloadBytes,
    int maxPendingUploads,
    SensorScopePolicy sensorScopePolicy,
    boolean readOnlyProbesEnabled,
    int maxPendingProbesPerPoll,
    boolean executionEnabled,
    boolean emitSeedMapMetadata,
    SeedMapOptions seedMapOptions,
    SnapshotOptions snapshotOptions,
    ProbeOptions probeOptions
) {
    public static final boolean DEFAULT_ENABLED = false;
    public static final boolean DEFAULT_READ_ONLY_PROBES_ENABLED = true;
    public static final boolean DEFAULT_EXECUTION_ENABLED = false;
    public static final int DEFAULT_HEARTBEAT_INTERVAL_TICKS = 100;
    public static final int MIN_MANIFEST_REFRESH_INTERVAL_TICKS = 300;
    public static final String INACTIVE_ENDPOINT =
        "https://casimirbot.com/api/room-ingress/v1/bindings/replace-with-generated-id";

    public record SnapshotOptions(
        boolean includeActorState,
        boolean includeInventoryState,
        boolean includeFocus,
        boolean includeNearbyEntities,
        boolean includeOpenContainer,
        boolean includeNearbyContainerRefs,
        boolean includeCrops,
        boolean includeLocalMap,
        boolean includeChunkSnapshotSummary,
        int nearbyEntityRadius,
        int cropRadius,
        int localMapRadius,
        int chunkSnapshotRadiusChunks,
        int maxEntities,
        int maxCrops,
        int maxLocalBlocks,
        int maxChunkSnapshotCells,
        int maxInventoryStacks
    ) {}

    public record ProbeOptions(int maxRouteRadius, int maxProbeDurationMs, int ttlMs) {}

    public record SeedMapOptions(
        int radiusChunks,
        String selectedTargetLabel,
        boolean repeatOnLocationSamples,
        int repeatEveryLocationSamples,
        boolean exposeSeedToHelixOnly,
        boolean redactSeedInDebugLogs
    ) {}

    public boolean sensorUploadsAllowed() {
        return enabled
            && !executionEnabled
            && secureEndpointAllowed(endpoint)
            && credentialAllowedForEndpoint(endpoint, bearerToken);
    }

    public static boolean credentialAllowedForEndpoint(String endpoint, String bearerToken) {
        if (!HelixHttpClient.isRoomIngressEndpoint(endpoint)) return true;
        return bearerToken != null
            && !bearerToken.isBlank()
            && !"replace-me".equalsIgnoreCase(bearerToken.trim());
    }

    public static boolean secureEndpointAllowed(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            if ("https".equalsIgnoreCase(uri.getScheme())) return true;
            String host = uri.getHost();
            return "http".equalsIgnoreCase(uri.getScheme()) &&
                ("localhost".equalsIgnoreCase(host) ||
                    "127.0.0.1".equals(host) ||
                    "::1".equals(host));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public static int positive(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    public static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) return INACTIVE_ENDPOINT;
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
