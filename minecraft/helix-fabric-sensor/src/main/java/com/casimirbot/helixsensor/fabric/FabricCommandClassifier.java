package com.casimirbot.helixsensor.fabric;

import java.util.Locale;
import java.util.Set;

final class FabricCommandClassifier {
    record Classification(String category, String effectClass) {}

    private static final Set<String> SERVER_ADMIN = Set.of(
        "ban", "ban-ip", "banlist", "deop", "jfr", "kick", "op",
        "pardon", "pardon-ip", "perf", "publish", "reload", "save-all",
        "save-off", "save-on", "stop", "whitelist",
        // Server-originated communication can address arbitrary players and
        // therefore uses the server-administration authority rail.
        "me", "msg", "playsound", "say", "stopsound", "tell", "tellraw", "title", "w"
    );
    private static final Set<String> PLAYER_INVENTORY = Set.of(
        "clear", "give", "item", "loot", "recipe"
    );
    private static final Set<String> PLAYER_MOVEMENT = Set.of(
        "return", "spawnpoint", "spreadplayers", "teleport", "tp"
    );
    private static final Set<String> PLAYER_STATE = Set.of(
        "advancement", "attribute", "damage", "effect", "enchant",
        "experience", "gamemode", "kill", "spectate", "xp"
    );
    private static final Set<String> WORLD_TIME_WEATHER = Set.of(
        "difficulty", "gamerule", "time", "weather", "worldborder"
    );
    private static final Set<String> WORLD_BUILD = Set.of(
        "clone", "fill", "fillbiome", "forceload", "place", "setblock",
        "setworldspawn"
    );
    private static final Set<String> ENTITY_CONTROL = Set.of(
        "bossbar", "ride", "summon", "tag", "team", "teammsg", "tm"
    );
    private static final Set<String> READ_ONLY = Set.of(
        "help", "list", "locate", "seed"
    );

    private FabricCommandClassifier() {}

    static Classification classify(String command) {
        String normalized = command.trim().replaceFirst("^/+", "");
        String lower = normalized.toLowerCase(Locale.ROOT);
        String root = lower.split("\\s+", 2)[0];
        int runIndex = lower.lastIndexOf(" run ");
        if ("execute".equals(root) && runIndex >= 0) {
            return classify(normalized.substring(runIndex + 5));
        }
        if ("helixgame".equals(root)) {
            if (
                lower.matches("^helixgame\\s+(?:ping|checkpoint\\s+status|fall_rescue\\s+status|combat_watchdog\\s+status)(?:\\s|$).*$")
            ) {
                return new Classification("query", "read_only");
            }
            if (lower.matches("^helixgame\\s+checkpoint\\s+restore(?:\\s|$).*$")) {
                return new Classification("world_build", "world_mutation");
            }
            if (
                lower.matches("^helixgame\\s+fall_rescue\\s+(?:arm|disarm)(?:\\s|$).*$")
            ) {
                return new Classification("world_build", "world_mutation");
            }
            if (
                lower.matches("^helixgame\\s+combat_watchdog\\s+(?:arm|disarm)(?:\\s|$).*$")
            ) {
                return new Classification("entity_control", "world_mutation");
            }
            if (
                lower.matches("^helixgame\\s+checkpoint\\s+(?:capture(?:_box)?|discard)(?:\\s|$).*$")
            ) {
                return new Classification(
                    "server_administration",
                    "server_administration"
                );
            }
        }
        if ("data".equals(root) && lower.matches("^data\\s+get(?:\\s|$).*$")) {
            return new Classification("query", "read_only");
        }
        if (
            "scoreboard".equals(root) &&
            (lower.contains(" players get ") || lower.endsWith(" players list"))
        ) {
            return new Classification("query", "read_only");
        }
        if (
            lower.matches("^time\\s+query(?:\\s|$).*$") ||
            lower.matches("^gamerule\\s+\\S+$") ||
            lower.matches("^difficulty$") ||
            lower.matches("^worldborder\\s+get$") ||
            lower.matches("^tick\\s+query$")
        ) {
            return new Classification("query", "read_only");
        }
        if (READ_ONLY.contains(root)) {
            return new Classification("query", "read_only");
        }
        if (SERVER_ADMIN.contains(root)) {
            return new Classification("server_administration", "server_administration");
        }
        if (PLAYER_INVENTORY.contains(root)) {
            return new Classification("player_inventory", "player_mutation");
        }
        if (PLAYER_MOVEMENT.contains(root)) {
            return new Classification("player_movement", "player_mutation");
        }
        if (PLAYER_STATE.contains(root)) {
            return new Classification("player_state", "player_mutation");
        }
        if (WORLD_TIME_WEATHER.contains(root)) {
            return new Classification("world_time_weather", "world_mutation");
        }
        if (
            "data".equals(root) &&
            lower.matches("^data\\s+(?:merge|modify|remove)\\s+entity(?:\\s|$).*$")
        ) {
            return new Classification("entity_control", "world_mutation");
        }
        if (WORLD_BUILD.contains(root)) {
            return new Classification("world_build", "world_mutation");
        }
        if (ENTITY_CONTROL.contains(root)) {
            return new Classification("entity_control", "world_mutation");
        }
        if ("data".equals(root) || "scoreboard".equals(root)) {
            return new Classification("world_build", "world_mutation");
        }
        return new Classification("mod_command", "unknown");
    }
}
