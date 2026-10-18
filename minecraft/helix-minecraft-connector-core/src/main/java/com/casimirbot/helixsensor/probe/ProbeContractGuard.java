package com.casimirbot.helixsensor.probe;

import java.util.Map;
import java.util.Set;

public final class ProbeContractGuard {
    private static final Set<String> FORBIDDEN = Set.of(
        "move_actor",
        "use_item",
        "take_item",
        "place_block",
        "break_block",
        "attack_entity",
        "open_container"
    );
    private static final Set<String> READ_ONLY = Set.of(
        "actor_status",
        "inventory_check",
        "nearby_entities",
        "line_of_sight",
        "reachability",
        "crop_state",
        "hazard_check",
        "local_map_summary",
        "spatial_region",
        "perception_snapshot",
        "registry_fact",
        "recipe_fact"
    );

    public boolean isForbiddenAction(Map<String, Object> probe) {
        return FORBIDDEN.contains(String.valueOf(probe.get("probe_type")));
    }

    public boolean isKnownReadOnlyProbe(Map<String, Object> probe) {
        return READ_ONLY.contains(String.valueOf(probe.get("probe_type")));
    }

    public boolean isReadOnly(Map<String, Object> probe) {
        Object constraints = probe.get("constraints");
        if (!(constraints instanceof Map<?, ?> map)) return false;
        return Boolean.TRUE.equals(map.get("read_only")) && Boolean.FALSE.equals(map.get("side_effects_allowed"));
    }
}
