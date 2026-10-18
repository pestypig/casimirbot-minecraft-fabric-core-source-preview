package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class TemporalDeliveryLaneTest {
    @Test
    void blockedDeliveryDoesNotOccupyControlExecutorOrQueueDuplicateWork() throws Exception {
        var controls = Executors.newSingleThreadExecutor();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        AtomicInteger deliveries = new AtomicInteger();
        try (var lane = new TemporalDeliveryLane()) {
            assertTrue(controls.submit(() -> lane.submit(() -> {
                deliveries.incrementAndGet();
                entered.countDown();
                try { release.await(); }
                catch (InterruptedException stopped) { Thread.currentThread().interrupt(); }
            })).get(2, TimeUnit.SECONDS));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals("control-polled", controls.submit(() -> "control-polled").get(2, TimeUnit.SECONDS));
            assertFalse(lane.submit(deliveries::incrementAndGet));
            assertEquals(1, deliveries.get());
        } finally {
            release.countDown();
            controls.shutdownNow();
        }
    }

    @Test
    void closedLaneRejectsWork() {
        var lane = new TemporalDeliveryLane();
        lane.close();
        assertFalse(lane.submit(() -> fail("must not execute")));
    }
}
