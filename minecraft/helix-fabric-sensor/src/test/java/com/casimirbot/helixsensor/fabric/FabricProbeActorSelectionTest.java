package com.casimirbot.helixsensor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

final class FabricProbeActorSelectionTest {
    private static final List<FabricProbeExecutor.ActorCandidate> PLAYERS = List.of(
        new FabricProbeExecutor.ActorCandidate(
            0,
            "minecraft:player:Alex",
            "11111111-1111-1111-1111-111111111111",
            "Alex"
        ),
        new FabricProbeExecutor.ActorCandidate(
            1,
            "minecraft:player:Alexa",
            "22222222-2222-2222-2222-222222222222",
            "Alexa"
        )
    );

    @Test
    void selectsOnlyExactCanonicalUuidOrLabelIdentity() {
        assertEquals(
            0,
            FabricProbeExecutor.selectActor(
                PLAYERS,
                "minecraft:player:Alex"
            ).selectedIndex()
        );
        assertEquals(
            1,
            FabricProbeExecutor.selectActor(
                PLAYERS,
                "22222222-2222-2222-2222-222222222222"
            ).selectedIndex()
        );
        assertEquals(
            1,
            FabricProbeExecutor.selectActor(PLAYERS, "Alexa").selectedIndex()
        );
    }

    @Test
    void rejectsSuffixAndFailsClosedWhenCurrentActorIsAmbiguous() {
        FabricProbeExecutor.ActorSelection suffix =
            FabricProbeExecutor.selectActor(PLAYERS, "lex");
        assertEquals("target_unavailable", suffix.status());
        assertNull(suffix.selectedIndex());

        FabricProbeExecutor.ActorSelection current =
            FabricProbeExecutor.selectActor(PLAYERS, "");
        assertEquals("target_ambiguous", current.status());
        assertNull(current.selectedIndex());
    }

    @Test
    void selectsTheOnlyOnlineCurrentActor() {
        FabricProbeExecutor.ActorSelection selection =
            FabricProbeExecutor.selectActor(List.of(PLAYERS.get(0)), "");
        assertEquals("selected", selection.status());
        assertEquals(0, selection.selectedIndex());
    }
}
