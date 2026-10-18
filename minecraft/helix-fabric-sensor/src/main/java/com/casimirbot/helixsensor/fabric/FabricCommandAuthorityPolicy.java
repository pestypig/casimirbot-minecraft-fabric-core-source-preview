package com.casimirbot.helixsensor.fabric;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

final class FabricCommandAuthorityPolicy {
    private static final Set<String> PLAYER_CATEGORIES = Set.of(
        "player_state", "player_inventory", "player_movement"
    );
    private static final Pattern NON_SELF_SELECTOR = Pattern.compile(
        "(?i)(?<![a-z0-9_])@[aenpr](?:\\[[^\\r\\n]*?\\])?"
    );
    private static final Pattern SELF_SELECTOR = Pattern.compile(
        "(?i)(?<![a-z0-9_])@s(?:\\[[^\\r\\n]*?\\])?"
    );
    private static final Pattern HELIX_GAMEPLAY_ROOT = Pattern.compile(
        "(?i)^/?(?:[a-z0-9_.-]+:)?helixgame(?:\\s|$)"
    );

    private FabricCommandAuthorityPolicy() {}

    static boolean profileAllows(
        String profile,
        String category,
        String effect
    ) {
        return switch (profile) {
            case "observe" ->
                "query".equals(category) && "read_only".equals(effect);
            case "player_assistant" ->
                ("query".equals(category) || PLAYER_CATEGORIES.contains(category)) &&
                ("read_only".equals(effect) || "player_mutation".equals(effect));
            case "world_operator" ->
                !"server_administration".equals(category) &&
                !"server_administration".equals(effect) &&
                !"unknown".equals(effect);
            case "server_administrator" -> true;
            default -> false;
        };
    }

    static boolean playerSubjectRequired(String profile, String category) {
        return !"server_administrator".equals(profile) &&
            PLAYER_CATEGORIES.contains(category);
    }

    static boolean selectedPlayerSourceRequired(
        String profile,
        String category,
        String command,
        boolean selectedPlayerBound
    ) {
        if (playerSubjectRequired(profile, category)) return true;
        return selectedPlayerBound &&
            (
                SELF_SELECTOR.matcher(command).find() ||
                HELIX_GAMEPLAY_ROOT.matcher(command.trim()).find()
            );
    }

    static boolean confinedToSelectedPlayer(
        String command,
        String selectedPlayerName,
        Collection<String> onlinePlayerNames
    ) {
        if (NON_SELF_SELECTOR.matcher(command).find()) return false;
        String normalized = command.toLowerCase(Locale.ROOT);
        String selected = selectedPlayerName.toLowerCase(Locale.ROOT);
        for (String onlineName : onlinePlayerNames) {
            String candidate = onlineName.toLowerCase(Locale.ROOT);
            if (candidate.equals(selected)) continue;
            Pattern playerToken = Pattern.compile(
                "(?i)(?<![a-z0-9_])" + Pattern.quote(candidate) +
                "(?![a-z0-9_])"
            );
            if (playerToken.matcher(normalized).find()) return false;
        }
        return true;
    }
}
