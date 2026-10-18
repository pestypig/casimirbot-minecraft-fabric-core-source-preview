package com.casimirbot.helixplayer.fabric;

import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/** Measurement only: no wall-clock synchronization or execution authority. */
final class EnvironmentMonotonicClock {
    private final String originId;
    private final LongSupplier nanoTime;
    private final long originNanos;

    EnvironmentMonotonicClock() {
        this("minecraft_monotonic_clock:" + UUID.randomUUID(), System::nanoTime);
    }

    EnvironmentMonotonicClock(String originId, LongSupplier nanoTime) {
        this.originId = originId;
        this.nanoTime = nanoTime;
        this.originNanos = nanoTime.getAsLong();
    }

    Map<String, Object> snapshot() {
        // Subtraction supports nanoTime's signed wrap for intervals < 292 years.
        long elapsed = nanoTime.getAsLong() - originNanos;
        if (elapsed < 0) throw new IllegalStateException("Monotonic clock regressed");
        return Map.of("origin_id", originId, "elapsed_ms", elapsed / 1_000_000.0);
    }
}
