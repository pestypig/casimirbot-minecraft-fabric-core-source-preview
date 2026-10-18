package com.casimirbot.helixsensor.snapshot.adapters;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class AffordanceSummaryAdapter {
    public Map<String, Object> build(Map<String, Object> focus, List<Map<String, Object>> containers, List<Map<String, Object>> resources) {
        List<String> visible = new ArrayList<>();
        List<String> reachable = new ArrayList<>();
        List<String> usable = new ArrayList<>();
        Object targetType = focus.get("target_type");
        if (targetType instanceof String type) {
            visible.add(type);
            if (Boolean.TRUE.equals(focus.get("reachable"))) reachable.add(type);
        }
        for (Map<String, Object> container : containers) {
            Object type = container.get("container_type");
            if (type instanceof String text) visible.add(text);
        }
        for (Map<String, Object> resource : resources) {
            Object type = resource.get("resource_type");
            if (type instanceof String text) usable.add(text);
        }
        return Map.of(
            "visible", visible,
            "reachable", reachable,
            "usable", usable,
            "sensor_scope", "sensor_observable"
        );
    }
}
