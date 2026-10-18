package com.casimirbot.helixsensor.fabric;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Server-owned containment for disposable combat fixtures.
 *
 * <p>This is deliberately World Authority test infrastructure, not a Player
 * Embodiment combat action. One lease binds an exact player, a validated entity
 * tag, a captured arena envelope, a health floor and an expiry. Every terminal
 * path freezes only tagged hostile fixtures and retains a bounded receipt for
 * later command-result evidence.</p>
 */
final class FabricCombatArenaWatchdog {
    static final int MIN_SECONDS = 5;
    static final int MAX_SECONDS = 120;
    static final int MIN_RADIUS = 4;
    static final int MAX_RADIUS = 64;
    private static final Pattern SAFE_TAG = Pattern.compile("[A-Za-z0-9_.-]{1,32}");

    record Operation(boolean ok, String message) {}

    record Decision(boolean contain, String reason) {
        static Decision continueWatching() {
            return new Decision(false, "watching");
        }
    }

    private record Lease(
        UUID playerId,
        String playerName,
        String mobTag,
        double healthFloor,
        ResourceKey<Level> dimension,
        AABB arenaBounds,
        long armedAtTick,
        long expiresAtTick
    ) {}

    private record Receipt(
        String playerName,
        String mobTag,
        String reason,
        int containedCount,
        double playerHealth,
        double healthFloor,
        long settledAtTick
    ) {
        String render() {
            return "combat_watchdog status=settled" +
                " player=" + playerName +
                " mob_tag=" + mobTag +
                " reason=" + reason +
                " contained_count=" + containedCount +
                " player_health=" + compact(playerHealth) +
                " health_floor=" + compact(healthFloor) +
                " settled_tick=" + settledAtTick;
        }
    }

    private static final Map<UUID, Lease> ACTIVE = new LinkedHashMap<>();
    private static final Map<UUID, Receipt> LAST = new LinkedHashMap<>();

    private FabricCombatArenaWatchdog() {}

    static Operation arm(
        ServerPlayer player,
        String mobTag,
        double healthFloor,
        int horizontalRadius,
        int seconds
    ) {
        if (player == null) return new Operation(false, "Combat watchdog requires an exact online player.");
        if (!validTag(mobTag)) return new Operation(false, "Combat watchdog mob tag is invalid.");
        if (!Double.isFinite(healthFloor) || healthFloor < 1 || healthFloor > 20) {
            return new Operation(false, "Combat watchdog health floor must be between 1 and 20.");
        }
        if (horizontalRadius < MIN_RADIUS || horizontalRadius > MAX_RADIUS) {
            return new Operation(false, "Combat watchdog radius is outside the bounded envelope.");
        }
        if (seconds < MIN_SECONDS || seconds > MAX_SECONDS) {
            return new Operation(false, "Combat watchdog duration is outside the bounded envelope.");
        }
        UUID playerId = player.getUUID();
        if (ACTIVE.containsKey(playerId)) {
            return new Operation(false, "Combat watchdog is already armed for this player.");
        }
        long now = serverLevel(player).getGameTime();
        Vec3 center = player.position();
        AABB arenaBounds = new AABB(
            center.x - horizontalRadius,
            center.y - 8,
            center.z - horizontalRadius,
            center.x + horizontalRadius,
            center.y + 16,
            center.z + horizontalRadius
        );
        ACTIVE.put(playerId, new Lease(
            playerId,
            player.getGameProfile().getName(),
            mobTag,
            healthFloor,
            player.level().dimension(),
            arenaBounds,
            now,
            now + seconds * 20L
        ));
        LAST.remove(playerId);
        return new Operation(true,
            "combat_watchdog status=armed player=" + player.getGameProfile().getName() +
            " mob_tag=" + mobTag +
            " health_floor=" + compact(healthFloor) +
            " radius=" + horizontalRadius +
            " expires_tick=" + (now + seconds * 20L)
        );
    }

    static Operation disarm(MinecraftServer server, ServerPlayer player) {
        Lease lease = ACTIVE.remove(player.getUUID());
        if (lease == null) return new Operation(false, "Combat watchdog is not armed for this player.");
        Receipt receipt = contain(server, lease, "operator_disarmed", player.getHealth());
        LAST.put(player.getUUID(), receipt);
        return new Operation(true, receipt.render());
    }

    static Operation status(ServerPlayer player) {
        Lease active = ACTIVE.get(player.getUUID());
        if (active != null) {
            long now = serverLevel(player).getGameTime();
            return new Operation(true,
                "combat_watchdog status=armed player=" + active.playerName() +
                " mob_tag=" + active.mobTag() +
                " health_floor=" + compact(active.healthFloor()) +
                " remaining_ticks=" + Math.max(0, active.expiresAtTick() - now)
            );
        }
        Receipt receipt = LAST.get(player.getUUID());
        return receipt == null
            ? new Operation(true, "combat_watchdog status=inactive player=" + player.getGameProfile().getName())
            : new Operation(true, receipt.render());
    }

    static void tick(MinecraftServer server) {
        for (Lease lease : java.util.List.copyOf(ACTIVE.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(lease.playerId());
            long now = currentTick(server, lease, player);
            Decision decision = decide(
                player != null,
                player != null && player.isAlive(),
                player == null ? 0 : player.getHealth(),
                lease.healthFloor(),
                now,
                lease.expiresAtTick()
            );
            if (!decision.contain()) continue;
            ACTIVE.remove(lease.playerId());
            double health = player == null ? 0 : player.getHealth();
            LAST.put(lease.playerId(), contain(server, lease, decision.reason(), health));
        }
    }

    static void clear(MinecraftServer server) {
        for (Lease lease : java.util.List.copyOf(ACTIVE.values())) {
            LAST.put(lease.playerId(), contain(server, lease, "server_stopping", 0));
        }
        ACTIVE.clear();
    }

    static Decision decide(
        boolean playerOnline,
        boolean playerAlive,
        double playerHealth,
        double healthFloor,
        long currentTick,
        long expiresAtTick
    ) {
        if (!playerOnline) return new Decision(true, "player_disconnected");
        if (!playerAlive) return new Decision(true, "player_dead");
        if (playerHealth <= healthFloor) return new Decision(true, "player_health_floor_reached");
        if (currentTick >= expiresAtTick) return new Decision(true, "watchdog_expired");
        return Decision.continueWatching();
    }

    static boolean validTag(String value) {
        return value != null && SAFE_TAG.matcher(value).matches();
    }

    private static Receipt contain(
        MinecraftServer server,
        Lease lease,
        String reason,
        double playerHealth
    ) {
        ServerLevel level = server.getLevel(lease.dimension());
        int contained = 0;
        if (level != null) {
            for (Monster hostile : level.getEntitiesOfClass(
                Monster.class,
                lease.arenaBounds(),
                entity -> entity.getTags().contains(lease.mobTag())
            )) {
                hostile.setNoAi(true);
                hostile.setInvulnerable(true);
                hostile.setDeltaMovement(Vec3.ZERO);
                hostile.getNavigation().stop();
                contained++;
            }
        }
        return new Receipt(
            lease.playerName(),
            lease.mobTag(),
            reason,
            contained,
            playerHealth,
            lease.healthFloor(),
            currentTick(server, lease, null)
        );
    }

    private static long currentTick(MinecraftServer server, Lease lease, ServerPlayer player) {
        if (player != null) return serverLevel(player).getGameTime();
        ServerLevel level = server.getLevel(lease.dimension());
        return level == null ? lease.armedAtTick() : level.getGameTime();
    }

    private static String compact(double value) {
        if (Math.rint(value) == value) return Long.toString((long) value);
        return Double.toString(value);
    }

    private static ServerLevel serverLevel(ServerPlayer player) {
        return (ServerLevel) player.level();
    }
}
