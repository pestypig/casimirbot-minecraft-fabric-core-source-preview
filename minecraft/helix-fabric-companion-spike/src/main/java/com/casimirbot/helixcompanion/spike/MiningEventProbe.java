package com.casimirbot.helixcompanion.spike;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;

/** Test-only-scoped event accounting keyed by exact absolute block position. */
public final class MiningEventProbe {
    private static final ConcurrentHashMap<BlockPos, State> ACTIVE = new ConcurrentHashMap<>();

    static {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            State probe = ACTIVE.get(pos);
            if (probe == null) return true;
            probe.before.incrementAndGet();
            return !probe.cancel;
        });
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            State probe = ACTIVE.get(pos);
            if (probe != null) probe.after.incrementAndGet();
        });
        PlayerBlockBreakEvents.CANCELED.register((world, player, pos, state, blockEntity) -> {
            State probe = ACTIVE.get(pos);
            if (probe != null) probe.canceled.incrementAndGet();
        });
    }

    private MiningEventProbe() {}

    public static Scope open(BlockPos target, boolean cancel) {
        State state = new State(cancel);
        if (ACTIVE.putIfAbsent(target.immutable(), state) != null) {
            throw new IllegalStateException("mining_event_probe_target_already_active");
        }
        return new Scope(target.immutable(), state);
    }

    private static final class State {
        private final boolean cancel;
        private final AtomicInteger before = new AtomicInteger();
        private final AtomicInteger after = new AtomicInteger();
        private final AtomicInteger canceled = new AtomicInteger();

        private State(boolean cancel) {
            this.cancel = cancel;
        }
    }

    public static final class Scope implements AutoCloseable {
        private final BlockPos target;
        private final State state;
        private boolean closed;

        private Scope(BlockPos target, State state) {
            this.target = target;
            this.state = state;
        }

        public Counts counts() {
            return new Counts(
                state.before.get(),
                state.after.get(),
                state.canceled.get()
            );
        }

        @Override
        public void close() {
            if (!closed) {
                ACTIVE.remove(target, state);
                closed = true;
            }
        }
    }

    public record Counts(int before, int after, int canceled) {}
}
