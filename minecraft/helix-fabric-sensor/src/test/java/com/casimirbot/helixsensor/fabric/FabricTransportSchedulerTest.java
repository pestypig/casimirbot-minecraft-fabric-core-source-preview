package com.casimirbot.helixsensor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class FabricTransportSchedulerTest {
    @Test
    void keepsManifestRefreshIndependentFromFastHeartbeats() {
        assertEquals(
            15_000L,
            FabricConnectorRuntime.manifestRefreshIntervalMillisFor(100)
        );
        assertEquals(
            15_000L,
            FabricConnectorRuntime.manifestRefreshIntervalMillisFor(300)
        );
        assertEquals(
            30_000L,
            FabricConnectorRuntime.manifestRefreshIntervalMillisFor(600)
        );
    }

    @Test
    void refreshesWithoutMinecraftTicksAndStopsAfterClose() throws Exception {
        AtomicInteger calls = new AtomicInteger(0);
        CountDownLatch refreshedTwice = new CountDownLatch(2);
        FabricTransportScheduler scheduler = new FabricTransportScheduler(
            "helix-fabric-manifest-refresh-test"
        );

        scheduler.start(
            () -> {
                calls.incrementAndGet();
                refreshedTwice.countDown();
            },
            10L
        );

        assertTrue(
            refreshedTwice.await(Duration.ofSeconds(1).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS),
            "wall-clock refresh should run without a Minecraft server tick"
        );
        scheduler.close();
        int callsAtClose = calls.get();
        Thread.sleep(50L);
        assertEquals(callsAtClose, calls.get());
    }
}
