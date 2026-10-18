package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class ManualInputLatchTest {
    @Test
    void observingPendingInputDoesNotConsumeOrResetTakeover() {
        ManualInputLatch.disarm();
        assertFalse(ManualInputLatch.pending());
        ManualInputLatch.arm();
        assertFalse(ManualInputLatch.pending());
        ManualInputLatch.recordKeyboardAction(ManualInputLatch.KeyboardAction.LEFT);
        long detected = ManualInputLatch.detectedNanos();
        assertTrue(ManualInputLatch.pending());
        assertTrue(ManualInputLatch.pending());
        assertEquals(detected, ManualInputLatch.detectedNanos());
        assertEquals("left_key_pressed", ManualInputLatch.consume());
        assertFalse(ManualInputLatch.pending());
        ManualInputLatch.recordMousePress(0);
        assertTrue(ManualInputLatch.pending());
        ManualInputLatch.disarm();
        assertFalse(ManualInputLatch.pending());
    }

    @AfterEach
    void resetLatch() {
        ManualInputLatch.disarm();
    }

    @Test
    void ignoresInputOutsideAnActiveWorkflow() {
        ManualInputLatch.disarm();
        ManualInputLatch.recordKeyboardAction(ManualInputLatch.KeyboardAction.FORWARD);
        ManualInputLatch.recordMousePress(0);

        assertFalse(ManualInputLatch.armedForTest());
        assertNull(ManualInputLatch.consume());
    }

    @Test
    void capturesAndConsumesTheFirstContractValidInputForAnActiveWorkflow() {
        ManualInputLatch.arm();
        ManualInputLatch.recordKeyboardAction(ManualInputLatch.KeyboardAction.FORWARD);
        ManualInputLatch.recordMousePress(0);

        assertTrue(ManualInputLatch.armedForTest());
        assertEquals("forward_key_pressed", ManualInputLatch.consume());
        assertNull(ManualInputLatch.consume());
    }

    @Test
    void mapsMovementAndMouseInputsToStableResultReasons() {
        ManualInputLatch.KeyboardAction[] keyboardInputs = {
            ManualInputLatch.KeyboardAction.BACK,
            ManualInputLatch.KeyboardAction.LEFT,
            ManualInputLatch.KeyboardAction.RIGHT,
            ManualInputLatch.KeyboardAction.JUMP,
            ManualInputLatch.KeyboardAction.SPRINT
        };
        String[] keyboardReasons = {
            "back_key_pressed",
            "left_key_pressed",
            "right_key_pressed",
            "jump_key_pressed",
            "sprint_key_pressed"
        };
        for (int index = 0; index < keyboardInputs.length; index++) {
            ManualInputLatch.arm();
            ManualInputLatch.recordKeyboardAction(keyboardInputs[index]);
            assertEquals(keyboardReasons[index], ManualInputLatch.consume());
        }

        int[] mouseInputs = {0, 1, 2};
        String[] mouseReasons = {
            "left_mouse_pressed",
            "right_mouse_pressed",
            "middle_mouse_pressed"
        };
        for (int index = 0; index < mouseInputs.length; index++) {
            ManualInputLatch.arm();
            ManualInputLatch.recordMousePress(mouseInputs[index]);
            assertEquals(mouseReasons[index], ManualInputLatch.consume());
        }
    }

    @Test
    void mapsOtherRawInputsToTheBoundedFallbackReason() {
        ManualInputLatch.arm();
        ManualInputLatch.recordKeyboardAction(ManualInputLatch.KeyboardAction.OTHER_GAMEPLAY);
        assertEquals("unspecified_manual_input", ManualInputLatch.consume());

        ManualInputLatch.arm();
        ManualInputLatch.recordMousePress(7);
        assertEquals("unspecified_manual_input", ManualInputLatch.consume());
    }

    @Test
    void ignoresUnclassifiedKeyboardInputWhilePreservingGameplayTakeover() {
        ManualInputLatch.arm();
        ManualInputLatch.recordKeyboardAction(null);
        assertNull(ManualInputLatch.consume());

        ManualInputLatch.recordKeyboardAction(ManualInputLatch.KeyboardAction.FORWARD);
        assertEquals("forward_key_pressed", ManualInputLatch.consume());
    }

    @Test
    void rearmingAndDisarmingCannotReplayStaleInput() {
        ManualInputLatch.arm();
        ManualInputLatch.recordMousePress(0);
        ManualInputLatch.arm();
        assertNull(ManualInputLatch.consume());

        ManualInputLatch.recordMousePress(0);
        ManualInputLatch.disarm();
        ManualInputLatch.arm();
        assertNull(ManualInputLatch.consume());
    }
}
