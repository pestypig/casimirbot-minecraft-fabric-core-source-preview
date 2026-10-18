package com.casimirbot.helixplayer.fabric;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Single-flight transport only. No action authority or controller ownership. */
final class TemporalDeliveryLane implements AutoCloseable {
    private final AtomicBoolean pending = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "helix-player-temporal-delivery");
        thread.setDaemon(true);
        return thread;
    });

    boolean submit(Runnable task) {
        if (!pending.compareAndSet(false, true)) return false;
        try {
            executor.execute(() -> {
                try { task.run(); }
                finally { pending.set(false); }
            });
            return true;
        } catch (RejectedExecutionException closed) {
            pending.set(false);
            return false;
        }
    }

    @Override public void close() { executor.shutdownNow(); }
}
