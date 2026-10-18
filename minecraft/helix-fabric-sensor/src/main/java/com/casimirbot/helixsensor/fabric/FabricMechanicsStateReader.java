package com.casimirbot.helixsensor.fabric;

import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.ReadOnlyScoreInfo;
import net.minecraft.world.scores.ScoreHolder;
import net.minecraft.world.scores.Scoreboard;

public final class FabricMechanicsStateReader {
    static final String CRIMSON_CURSE_MOD_ID = "mr_crimson_curse";

    private FabricMechanicsStateReader() {}

    public static Map<String, Object> read(MinecraftServer server) {
        if (!FabricLoader.getInstance().isModLoaded(CRIMSON_CURSE_MOD_ID)) {
            return Map.of();
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("mod_id", CRIMSON_CURSE_MOD_ID);
        FabricLoader
            .getInstance()
            .getModContainer(CRIMSON_CURSE_MOD_ID)
            .ifPresent(container ->
                state.put(
                    "mod_version",
                    container.getMetadata().getVersion().getFriendlyString()
                )
            );
        state.put("state_source", "allowlisted_scoreboard_observation");
        state.put("raw_command_output_included", false);
        state.put("raw_nbt_included", false);

        Integer mass = score(server.getScoreboard(), "Mass", "Global");
        Integer points = score(server.getScoreboard(), "Points", "Global");
        if (mass == null && points == null) {
            state.put("status", "not_initialized");
            return state;
        }
        state.put("status", "observed");
        if (mass != null) state.put("global_mass", mass);
        if (points != null) {
            state.put("global_points", points);
            state.put("infection_phase", phaseForPoints(points));
        }
        return state;
    }

    static Integer score(
        Scoreboard scoreboard,
        String objectiveName,
        String holderName
    ) {
        Objective objective = scoreboard.getObjective(objectiveName);
        if (objective == null) return null;
        ReadOnlyScoreInfo score = scoreboard.getPlayerScoreInfo(
            ScoreHolder.forNameOnly(holderName),
            objective
        );
        return score == null ? null : score.value();
    }

    static int phaseForPoints(int points) {
        if (points == Integer.MIN_VALUE) return -1;
        if (points < 0) return 0;
        if (points < 10) return 1;
        if (points < 40) return 2;
        if (points < 150) return 3;
        if (points < 300) return 4;
        return 5;
    }
}
