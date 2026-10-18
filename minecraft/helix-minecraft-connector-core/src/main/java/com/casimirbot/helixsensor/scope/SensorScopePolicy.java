package com.casimirbot.helixsensor.scope;

public final class SensorScopePolicy {
    private final SensorScope defaultScope;
    private final boolean allowPrivilegedContainerScan;
    private final boolean allowPrivilegedEntityScan;
    private final boolean privilegedStateRequiresCaveat;

    public SensorScopePolicy(
        SensorScope defaultScope,
        boolean allowPrivilegedContainerScan,
        boolean allowPrivilegedEntityScan,
        boolean privilegedStateRequiresCaveat
    ) {
        this.defaultScope = defaultScope == null ? SensorScope.PLAYER_OBSERVABLE : defaultScope;
        this.allowPrivilegedContainerScan = allowPrivilegedContainerScan;
        this.allowPrivilegedEntityScan = allowPrivilegedEntityScan;
        this.privilegedStateRequiresCaveat = privilegedStateRequiresCaveat;
    }

    public SensorScope defaultScope() {
        return defaultScope;
    }

    public boolean allowPrivilegedContainerScan() {
        return allowPrivilegedContainerScan;
    }

    public boolean allowPrivilegedEntityScan() {
        return allowPrivilegedEntityScan;
    }

    public boolean privilegedStateRequiresCaveat() {
        return privilegedStateRequiresCaveat;
    }

    public boolean requiresCaveat(SensorScope scope) {
        return scope == SensorScope.PRIVILEGED_SERVER_STATE || scope == SensorScope.SENSOR_OBSERVABLE || scope == SensorScope.UNKNOWN;
    }
}
