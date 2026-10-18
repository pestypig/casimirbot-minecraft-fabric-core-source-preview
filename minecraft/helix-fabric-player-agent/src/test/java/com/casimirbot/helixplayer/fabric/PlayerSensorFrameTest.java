package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class PlayerSensorFrameTest {
    @Test
    void projectsOneImmutableTickIntoTheExistingControllerSnapshot() {
        PlayerSensorFrame frame = frame(
            new PlayerSensorFrame.Focus(
                PlayerSensorFrame.FocusKind.BLOCK,
                4, 63, -2, "up", 4.5, 64.001, -1.5, 2.25
            )
        );

        assertEquals(7, frame.worldRevision());
        assertEquals(91, frame.gameTick());
        assertEquals(125_000, frame.captureDurationNanos());
        assertEquals("minecraft:overworld", frame.dimensionId());
        assertTrue(frame.focus().isBlock(4, 63, -2));
        assertFalse(frame.focus().isBlock(4, 63, -1));
        assertEquals(4.25, frame.snapshot().x());
        assertEquals("screen_open", frame.snapshot().manualInputReason());
    }

    @Test
    void rejectsNonFiniteMechanicsEvidence() {
        assertThrows(IllegalArgumentException.class, () -> new PlayerSensorFrame(
            1, 1, 0, "minecraft:overworld", true,
            Double.NaN, 64, 65.62, 0, 0, 0, 0,
            0, 0, 20, true, false, false, false,
            false, null, PlayerSensorFrame.Focus.miss(0, 65.62, 0)
        ));
    }

    static PlayerSensorFrame frame(PlayerSensorFrame.Focus focus) {
        return new PlayerSensorFrame(
            7, 91, 125_000, "minecraft:overworld", true,
            4.25, 64, 65.62, -4.5,
            0.05, -0.08, 0.1,
            12, -4, 18, false, true, false, true,
            true, "screen_open", focus
        );
    }
}
