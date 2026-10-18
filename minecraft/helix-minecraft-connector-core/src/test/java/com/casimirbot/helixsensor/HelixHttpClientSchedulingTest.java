package com.casimirbot.helixsensor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class HelixHttpClientSchedulingTest {
    @Test
    void onDemandProbeTrafficRunsBeforePeriodicControlAndTelemetry() {
        assertEquals(
            HelixHttpClient.RequestLane.PRIORITY,
            HelixHttpClient.nextRequestLane(true, true, true)
        );
        assertEquals(
            HelixHttpClient.RequestLane.CONTROL_PLANE,
            HelixHttpClient.nextRequestLane(false, true, true)
        );
        assertEquals(
            HelixHttpClient.RequestLane.ORDINARY,
            HelixHttpClient.nextRequestLane(false, false, true)
        );
    }

    @Test
    void periodicControlRetryYieldsToAQueuedProbe() {
        assertFalse(HelixHttpClient.retryMayContinue(true, true, true));
        assertTrue(HelixHttpClient.retryMayContinue(true, false, true));
        assertTrue(HelixHttpClient.retryMayContinue(true, true, false));
    }
}
