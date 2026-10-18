package com.casimirbot.helixsensor.snapshot;

import com.casimirbot.helixsensor.HelixJson;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PayloadLimiter {
    private PayloadLimiter() {}

    public static int byteLength(Object payload) {
        return HelixJson.stringify(payload).getBytes(StandardCharsets.UTF_8).length;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> truncateSnapshot(Map<String, Object> snapshot, int maxBytes) {
        if (byteLength(snapshot) <= maxBytes) return snapshot;
        Object localMap = snapshot.get("local_map");
        if (localMap instanceof Map<?, ?> local) {
            Map<String, Object> mutableLocal = new java.util.LinkedHashMap<>((Map<String, Object>) local);
            Object cells = mutableLocal.get("salient_cells");
            if (cells instanceof List<?> list && list.size() > 16) {
                mutableLocal.put("salient_cells", new ArrayList<>(list.subList(0, 16)));
                snapshot = with(snapshot, "local_map", mutableLocal);
            }
        }
        if (byteLength(snapshot) <= maxBytes) return snapshot;
        Object objectState = snapshot.get("object_state");
        if (objectState instanceof Map<?, ?> objects) {
            Map<String, Object> mutableObjects = new java.util.LinkedHashMap<>((Map<String, Object>) objects);
            trimList(mutableObjects, "nearby_entities", 8);
            trimList(mutableObjects, "resources", 12);
            snapshot = with(snapshot, "object_state", mutableObjects);
        }
        if (byteLength(snapshot) <= maxBytes) return snapshot;
        return with(snapshot, "local_map", Map.of(
            "truncated", true,
            "sensor_scope", "sensor_observable",
            "map_hash", SectionHasher.hash(localMap)
        ));
    }

    private static void trimList(Map<String, Object> map, String key, int limit) {
        Object value = map.get(key);
        if (value instanceof List<?> list && list.size() > limit) {
            map.put(key, new ArrayList<>(list.subList(0, limit)));
        }
    }

    private static Map<String, Object> with(Map<String, Object> source, String key, Object value) {
        Map<String, Object> copy = new java.util.LinkedHashMap<>(source);
        copy.put(key, value);
        return copy;
    }
}
