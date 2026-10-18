package com.casimirbot.helixsensor.fabric;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class FabricTransportScheduler implements AutoCloseable {
    private final ScheduledExecutorService executor;
    private final AtomicBoolean started = new AtomicBoolean(false);

    FabricTransportScheduler(String threadName) {
        this(
            Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, threadName);
                thread.setDaemon(true);
                return thread;
            })
        );
    }

    FabricTransportScheduler(ScheduledExecutorService executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    void start(Runnable task, long intervalMillis) {
        Objects.requireNonNull(task, "task");
        if (intervalMillis <= 0L) {
            throw new IllegalArgumentException("intervalMillis must be positive");
        }
        if (!started.compareAndSet(false, true)) return;
        executor.scheduleWithFixedDelay(
            task,
            intervalMillis,
            intervalMillis,
            TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
