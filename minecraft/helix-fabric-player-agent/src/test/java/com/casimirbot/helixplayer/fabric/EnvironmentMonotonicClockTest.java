package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

final class EnvironmentMonotonicClockTest {
    @Test void preservesOriginAndFractionalElapsedWithoutWallOrWorldClock() {
        AtomicLong nanos = new AtomicLong(9_000);
        EnvironmentMonotonicClock clock = new EnvironmentMonotonicClock("origin:test", nanos::get);
        assertEquals(0.0, clock.snapshot().get("elapsed_ms"));
        nanos.addAndGet(1_500_000);
        var snapshot = clock.snapshot();
        assertEquals("origin:test", snapshot.get("origin_id"));
        assertEquals(1.5, snapshot.get("elapsed_ms"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("elapsed_ms", 0));
    }

    @Test void supportsSignedNanoTimeWrap() {
        AtomicLong nanos = new AtomicLong(Long.MAX_VALUE - 4);
        EnvironmentMonotonicClock clock = new EnvironmentMonotonicClock("origin:test", nanos::get);
        nanos.addAndGet(10);
        assertEquals(0.00001, clock.snapshot().get("elapsed_ms"));
    }

    @Test void newRuntimeGetsDistinctOrigin() {
        assertNotEquals(new EnvironmentMonotonicClock().snapshot().get("origin_id"),
            new EnvironmentMonotonicClock().snapshot().get("origin_id"));
    }
}
