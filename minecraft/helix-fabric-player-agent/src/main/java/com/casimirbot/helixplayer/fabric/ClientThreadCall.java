package com.casimirbot.helixplayer.fabric;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded native-thread handoff, not an execution/retry authority. */
final class ClientThreadCall {
    static <T> T await(Executor client, Callable<T> operation, long timeoutMs) throws Exception {
        if (timeoutMs <= 0) throw new IllegalArgumentException("A finite client-thread wait is required.");
        FutureTask<T> task = new FutureTask<>(operation);
        client.execute(task);
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | InterruptedException failure) {
            // Suppress a callback that has not begun. Never interrupt Minecraft's
            // thread. If already running, effects remain uncertain: no replay.
            task.cancel(false);
            throw failure;
        }
    }
    private ClientThreadCall() {}
}
