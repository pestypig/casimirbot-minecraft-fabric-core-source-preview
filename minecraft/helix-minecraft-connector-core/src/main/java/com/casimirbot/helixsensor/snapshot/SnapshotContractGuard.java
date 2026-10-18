package com.casimirbot.helixsensor.snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SnapshotContractGuard {
    public List<String> validate(Map<String, Object> snapshot, long previousSourceTick) {
        List<String> issues = new ArrayList<>();
        requireFalse(snapshot, "assistant_answer", issues);
        requireFalse(snapshot, "raw_content_included", issues);
        requireFalse(snapshot, "raw_payload_included", issues);
        requirePresent(snapshot, "snapshot_id", issues);
        requirePresent(snapshot, "changed_sections", issues);
        requirePresent(snapshot, "section_hashes", issues);
        Object tick = snapshot.get("source_tick");
        if (!(tick instanceof Number number)) issues.add("source_tick_missing");
        else if (previousSourceTick >= 0 && number.longValue() <= previousSourceTick) issues.add("source_tick_not_monotonic");
        Object domainSpecific = snapshot.get("domain_specific");
        if (domainSpecific instanceof Map<?, ?> ds) {
            Object minecraft = ds.get("minecraft");
            if (minecraft instanceof Map<?, ?> mc && Boolean.TRUE.equals(mc.get("raw_nbt_included"))) {
                issues.add("raw_nbt_included");
            }
        }
        return issues;
    }

    private void requireFalse(Map<String, Object> snapshot, String key, List<String> issues) {
        if (!Boolean.FALSE.equals(snapshot.get(key))) issues.add(key + "_must_be_false");
    }

    private void requirePresent(Map<String, Object> snapshot, String key, List<String> issues) {
        if (!snapshot.containsKey(key) || snapshot.get(key) == null) issues.add(key + "_missing");
    }
}
