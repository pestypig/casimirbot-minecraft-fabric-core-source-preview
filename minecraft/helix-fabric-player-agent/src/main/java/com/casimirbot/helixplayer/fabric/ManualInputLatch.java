package com.casimirbot.helixplayer.fabric;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Captures raw client input before the resident controller writes its admitted
 * key state. Minecraft can process a real GLFW event and then let the
 * controller overwrite the corresponding {@code KeyMapping} in the same tick;
 * sampling only {@code KeyMapping.isDown()} can therefore miss a genuine local
 * takeover. The latch is armed only for an active workflow and is consumed by
 * the existing manual-override policy on the client thread.
 */
public final class ManualInputLatch {
    public enum KeyboardAction {
        FORWARD("forward_key_pressed"),
        BACK("back_key_pressed"),
        LEFT("left_key_pressed"),
        RIGHT("right_key_pressed"),
        JUMP("jump_key_pressed"),
        SPRINT("sprint_key_pressed"),
        OTHER_GAMEPLAY("unspecified_manual_input");

        private final String reason;

        KeyboardAction(String reason) {
            this.reason = reason;
        }
    }

    private static final AtomicBoolean ARMED = new AtomicBoolean(false);
    private static final AtomicReference<String> REASON = new AtomicReference<>();
    private static final AtomicLong DETECTED_NANOS = new AtomicLong(-1L);

    private ManualInputLatch() {}

    public static void arm() {
        REASON.set(null);
        DETECTED_NANOS.set(-1L);
        ARMED.set(true);
    }

    public static void disarm() {
        ARMED.set(false);
        REASON.set(null);
        DETECTED_NANOS.set(-1L);
    }

    public static void recordKeyboardAction(KeyboardAction action) {
        if (action != null) record(action.reason);
    }

    public static void recordMousePress(int button) {
        record(switch (button) {
            case 0 -> "left_mouse_pressed";
            case 1 -> "right_mouse_pressed";
            case 2 -> "middle_mouse_pressed";
            default -> "unspecified_manual_input";
        });
    }

    public static String consume() {
        if (!ARMED.get()) return null;
        return REASON.getAndSet(null);
    }

    /** Advisory observation only; never consumes or resets takeover input. */
    static boolean pending() {
        return ARMED.get() && REASON.get() != null;
    }

    static long detectedNanos() {
        return DETECTED_NANOS.get();
    }

    static boolean armedForTest() {
        return ARMED.get();
    }

    private static void record(String reason) {
        if (!ARMED.get()) return;
        if (REASON.compareAndSet(null, reason)) {
            DETECTED_NANOS.compareAndSet(-1L, System.nanoTime());
        }
    }
}
