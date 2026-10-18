package com.casimirbot.helixsensor.scope;

public enum SensorScope {
    PLAYER_OBSERVABLE("player_observable"),
    PLAYER_MEMORY("player_memory"),
    SENSOR_OBSERVABLE("sensor_observable"),
    PRIVILEGED_SERVER_STATE("privileged_server_state"),
    UNKNOWN("unknown");

    private final String wireValue;

    SensorScope(String wireValue) {
        this.wireValue = wireValue;
    }

    public String wireValue() {
        return wireValue;
    }

    public static SensorScope from(String value) {
        if (value == null) return UNKNOWN;
        for (SensorScope scope : values()) {
            if (scope.wireValue.equalsIgnoreCase(value) || scope.name().equalsIgnoreCase(value)) {
                return scope;
            }
        }
        return UNKNOWN;
    }
}
