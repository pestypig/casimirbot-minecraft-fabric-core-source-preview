package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ClientThreadCallTest {
    @Test void timeoutSuppressesCallbackThatHasNotStarted() {
        var queued = new AtomicReference<Runnable>();
        var effects = new AtomicInteger();
        assertThrows(TimeoutException.class, () -> ClientThreadCall.await(queued::set, effects::incrementAndGet, 10));
        queued.get().run();
        assertEquals(0, effects.get());
    }

    @Test void successfulAndFailedCallbacksRetainTheirResults() throws Exception {
        assertEquals(7, ClientThreadCall.await(Runnable::run, () -> 7, 100));
        var error = assertThrows(ExecutionException.class, () ->
            ClientThreadCall.await(Runnable::run, () -> { throw new IllegalStateException("fixture"); }, 100));
        assertInstanceOf(IllegalStateException.class, error.getCause());
    }

    @Test void alreadyRunningCallbackIsNotInterruptedOrReplayed() throws Exception {
        var release = new CountDownLatch(1);
        var finished = new CountDownLatch(1);
        var effects = new AtomicInteger();
        var started = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            assertThrows(TimeoutException.class, () -> ClientThreadCall.await(task -> {
                worker.execute(task);
                try { assertTrue(started.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new RuntimeException(e); }
            }, () -> {
                started.countDown();
                try { release.await(); effects.incrementAndGet(); }
                finally { finished.countDown(); }
                return 1;
            }, 10));
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(1, effects.get(), "Timeout is not proof of no effects");
        } finally { release.countDown(); worker.shutdownNow(); }
    }
}
