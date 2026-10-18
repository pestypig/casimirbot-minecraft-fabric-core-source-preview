package com.casimirbot.helixsensor.snapshot;

import com.casimirbot.helixsensor.HelixSensorConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LocationSampleWorldEventBuilder {
    private LocationSampleWorldEventBuilder() {}

    public static Map<String, Object> build(
        Map<String, Object> snapshot,
        HelixSensorConfig config,
        Map<String, Object> seedMapMeta,
        int currentTick
    ) {
        if (!config.emitSeedMapMetadata() || seedMapMeta.isEmpty()) return Map.of();
        Object locationValue = snapshot.get("location");
        if (!(locationValue instanceof Map<?, ?> rawLocation)) return Map.of();

        Map<String, Object> location = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawLocation.entrySet()) location.put(String.valueOf(entry.getKey()), entry.getValue());

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("snapshot_id", snapshot.get("snapshot_id"));
        meta.put("domain", "minecraft");
        meta.put("domain_adapter", config.domainAdapter());
        meta.putAll(seedMapMeta);
        if (!meta.containsKey("seed")) return Map.of();

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("schema", "helix.world_event.v1");
        event.put("world_id", config.worldId());
        event.put("room_id", config.roomId());
        event.put("source_id", config.sourceId());
        event.put("actor_id", snapshot.get("actor_id"));
        event.put("actor_label", snapshot.get("actor_label"));
        event.put("ts", snapshot.get("ts"));
        event.put("event_type", "player_location_sample");
        event.put("location", location);
        event.put("evidence_refs", List.of("minecraft:location:server_tick:" + currentTick));
        event.put("meta", meta);
        return event;
    }
}
