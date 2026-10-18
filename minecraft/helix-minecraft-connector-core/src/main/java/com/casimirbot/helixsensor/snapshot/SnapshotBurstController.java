package com.casimirbot.helixsensor.snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class SnapshotBurstController {
    private final long burstDurationTicks;
    private final List<Map<String, Object>> pendingEventFacts = new ArrayList<>();
    private volatile long burstUntilTick = 0L;
    private volatile String latestReason = "baseline";

    public SnapshotBurstController(com.casimirbot.helixsensor.HelixSensorConfig config) {
        this.burstDurationTicks = config.burstDurationTicks();
    }

    public void requestBurst(String reason, long currentTick) {
        this.latestReason = reason == null || reason.isBlank() ? "salient_event" : reason;
        this.burstUntilTick = Math.max(burstUntilTick, currentTick + burstDurationTicks);
    }

    public synchronized void requestBurst(String reason, long currentTick, Map<String, Object> eventFact) {
        requestBurst(reason, currentTick);
        if (eventFact != null && pendingEventFacts.size() < 64) pendingEventFacts.add(eventFact);
    }

    public boolean active(long currentTick) {
        return currentTick <= burstUntilTick;
    }

    public String latestReason() {
        return latestReason;
    }

    public synchronized List<Map<String, Object>> drainPendingEventFacts() {
        if (pendingEventFacts.isEmpty()) return List.of();
        List<Map<String, Object>> drained = new ArrayList<>(pendingEventFacts);
        pendingEventFacts.clear();
        return drained;
    }
}
