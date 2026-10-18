package com.casimirbot.helixsensor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

final class FabricClientPerceptionBridgeTest {
    private final UUID firstPlayer = UUID.fromString(
        "11111111-1111-1111-1111-111111111111"
    );
    private final UUID secondPlayer = UUID.fromString(
        "22222222-2222-2222-2222-222222222222"
    );

    @AfterEach
    void clear() {
        FabricClientPerceptionBridge.clearAll();
    }

    @Test
    void keepsPlayerBoundFreshClientObservationsSeparate() {
        FabricClientPerceptionBridge.publish(
            firstPlayer,
            new FabricClientPerceptionBridge.Snapshot(
                90L,
                100L,
                "open",
                "InventoryScreen",
                false
            )
        );

        assertEquals(
            "InventoryScreen",
            FabricClientPerceptionBridge.fresh(firstPlayer, 104L, 10L).screenKind()
        );
        assertNull(
            FabricClientPerceptionBridge.fresh(secondPlayer, 104L, 10L)
        );
    }

    @Test
    void rejectsStaleAndOutOfOrderServerSamples() {
        FabricClientPerceptionBridge.publish(
            firstPlayer,
            new FabricClientPerceptionBridge.Snapshot(
                90L,
                100L,
                "closed",
                "none",
                false
            )
        );
        FabricClientPerceptionBridge.publish(
            firstPlayer,
            new FabricClientPerceptionBridge.Snapshot(
                80L,
                99L,
                "open",
                "StaleScreen",
                true
            )
        );

        assertEquals(
            "closed",
            FabricClientPerceptionBridge.fresh(firstPlayer, 105L, 10L).screenState()
        );
        assertNull(FabricClientPerceptionBridge.fresh(firstPlayer, 111L, 10L));
    }

    @Test
    void clearRemovesOnlyTheDisconnectedPlayer() {
        FabricClientPerceptionBridge.Snapshot snapshot =
            new FabricClientPerceptionBridge.Snapshot(
                90L,
                100L,
                "closed",
                "none",
                false
            );
        FabricClientPerceptionBridge.publish(firstPlayer, snapshot);
        FabricClientPerceptionBridge.publish(secondPlayer, snapshot);

        FabricClientPerceptionBridge.clear(firstPlayer);

        assertNull(FabricClientPerceptionBridge.fresh(firstPlayer, 100L, 10L));
        assertEquals(
            snapshot,
            FabricClientPerceptionBridge.fresh(secondPlayer, 100L, 10L)
        );
    }
}
