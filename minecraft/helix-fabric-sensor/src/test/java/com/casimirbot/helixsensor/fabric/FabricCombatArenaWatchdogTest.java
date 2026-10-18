package com.casimirbot.helixsensor.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FabricCombatArenaWatchdogTest {
    @Test
    void containsOnEveryUnsafeTerminalBoundary() {
        assertDecision("player_disconnected", false, false, 0, 12, 10, 100);
        assertDecision("player_dead", true, false, 0, 12, 10, 100);
        assertDecision("player_health_floor_reached", true, true, 12, 12, 10, 100);
        assertDecision("player_health_floor_reached", true, true, 11.5, 12, 10, 100);
        assertDecision("watchdog_expired", true, true, 20, 12, 100, 100);
    }

    @Test
    void keepsWatchingOnlyWhileActorAndLeaseRemainSafe() {
        FabricCombatArenaWatchdog.Decision decision =
            FabricCombatArenaWatchdog.decide(true, true, 12.5, 12, 99, 100);
        assertFalse(decision.contain());
        assertEquals("watching", decision.reason());
    }

    @Test
    void admitsOnlyBoundedLiteralFixtureTags() {
        assertTrue(FabricCombatArenaWatchdog.validTag("helix_c1_bow-skeleton.2"));
        assertFalse(FabricCombatArenaWatchdog.validTag(""));
        assertFalse(FabricCombatArenaWatchdog.validTag("@e[type=zombie]"));
        assertFalse(FabricCombatArenaWatchdog.validTag("tag with spaces"));
        assertFalse(FabricCombatArenaWatchdog.validTag("a".repeat(33)));
    }

    private static void assertDecision(
        String reason,
        boolean online,
        boolean alive,
        double health,
        double floor,
        long tick,
        long expiry
    ) {
        FabricCombatArenaWatchdog.Decision decision =
            FabricCombatArenaWatchdog.decide(online, alive, health, floor, tick, expiry);
        assertTrue(decision.contain());
        assertEquals(reason, decision.reason());
    }
}
