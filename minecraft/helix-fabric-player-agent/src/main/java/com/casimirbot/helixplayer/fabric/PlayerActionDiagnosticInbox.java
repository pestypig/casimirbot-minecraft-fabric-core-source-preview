package com.casimirbot.helixplayer.fabric;

import com.casimirbot.helixsensor.HelixJson;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Explicitly enabled, bounded local handoff for direct-Codex reference runs.
 *
 * <p>This inbox is not a Helix authority path. It accepts only the typed player
 * action contract and is claimed and deleted before execution. A local user
 * may persist the selected scope across client restarts; startup clears stale
 * staged requests, and disable or emergency stop revokes the persisted scope.</p>
 */
final class PlayerActionDiagnosticInbox {
    static final String FILE_NAME = "helix-fabric-player-agent.diagnostic-inbox.json";
    static final String SCHEMA = "helix.minecraft.player.direct_diagnostic_request.v1";
    static final String AUTHORIZATION_FILE_NAME =
        "helix-fabric-player-agent.local-control.json";
    static final String AUTHORIZATION_SCHEMA =
        "helix.minecraft.player.local_control_authorization.v1";
    static final long MAX_AGE_MILLIS = 2 * 60 * 1000L;
    static final long MAX_BYTES = 256 * 1024L;
    static final long MAX_AUTHORIZATION_BYTES = 4 * 1024L;

    private static final Set<String> MOVEMENT_ACTIONS = Set.of(
        "navigate_to", "look_at", "track_target", "walk", "jump", "follow"
    );
    private static final Set<String> FULL_ACTIONS = Set.of(
        "navigate_to", "look_at", "track_target", "walk", "jump", "interact", "attack",
        "combat_guard",
        "hotbar_select", "equip", "follow", "collect", "mine", "place",
        "craft", "consume", "inventory_transfer", "execute_sequence",
        "execute_reactive_program", "arm_viability_guardian",
        "disarm_viability_guardian"
    );
    private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9:._-]{1,200}$");
    private static final Pattern RESOURCE_LOCATION = Pattern.compile(
        "^[a-z0-9_.-]+:[a-z0-9_./-]+$"
    );
    private static final Pattern SUBJECT_REF = Pattern.compile("^[A-Za-z0-9:._/-]{1,320}$");

    enum Scope {
        DISABLED,
        MOVEMENT,
        FULL;

        boolean permits(String actionKind) {
            return switch (this) {
                case DISABLED -> false;
                case MOVEMENT -> MOVEMENT_ACTIONS.contains(actionKind);
                case FULL -> FULL_ACTIONS.contains(actionKind);
            };
        }

        String wireName() {
            return name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    record DiagnosticRequest(
        String requestId,
        String actionKind,
        Map<String, Object> arguments,
        long maxDurationTicks,
        String controlEngine
    ) {}

    record PollResult(DiagnosticRequest request, String failureCode) {
        static PollResult absent() {
            return new PollResult(null, "");
        }

        static PollResult rejected(String failureCode) {
            return new PollResult(null, failureCode);
        }
    }

    private static final class ValidationFailure extends RuntimeException {
        private final String code;

        ValidationFailure(String code) {
            super(code);
            this.code = code;
        }
    }

    private PlayerActionDiagnosticInbox() {}

    static Path defaultPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    static Path defaultAuthorizationPath() {
        return FabricLoader.getInstance().getConfigDir().resolve(AUTHORIZATION_FILE_NAME);
    }

    static Scope loadPersistedScope() throws IOException {
        return loadPersistedScope(defaultAuthorizationPath());
    }

    static Scope loadPersistedScope(Path authorization) throws IOException {
        if (!Files.exists(authorization, LinkOption.NOFOLLOW_LINKS)) {
            return Scope.DISABLED;
        }
        if (
            !Files.isRegularFile(authorization, LinkOption.NOFOLLOW_LINKS) ||
            Files.size(authorization) > MAX_AUTHORIZATION_BYTES
        ) {
            Files.deleteIfExists(authorization);
            return Scope.DISABLED;
        }
        try {
            Map<String, Object> root = requiredObject(
                HelixJson.parse(Files.readString(authorization, StandardCharsets.UTF_8))
            );
            exactKeys(root, Set.of("schema", "scope"), Set.of());
            if (!AUTHORIZATION_SCHEMA.equals(text(root, "schema"))) {
                throw invalid("player_diagnostic_authorization_schema_invalid");
            }
            return switch (text(root, "scope")) {
                case "movement" -> Scope.MOVEMENT;
                case "full" -> Scope.FULL;
                default -> throw invalid("player_diagnostic_authorization_scope_invalid");
            };
        } catch (RuntimeException invalid) {
            Files.deleteIfExists(authorization);
            return Scope.DISABLED;
        }
    }

    static void persistScope(Scope scope) throws IOException {
        persistScope(defaultAuthorizationPath(), scope);
    }

    static void persistScope(Path authorization, Scope scope) throws IOException {
        if (scope == Scope.DISABLED) {
            Files.deleteIfExists(authorization);
            Files.deleteIfExists(pendingAuthorizationPath(authorization));
            return;
        }
        Path parent = authorization.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("Local control path has no parent.");
        Files.createDirectories(parent);
        Path pending = pendingAuthorizationPath(authorization);
        Files.deleteIfExists(pending);
        try {
            Files.writeString(
                pending,
                HelixJson.stringify(Map.of(
                    "schema", AUTHORIZATION_SCHEMA,
                    "scope", scope.wireName()
                )),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
            );
            try {
                Files.move(
                    pending,
                    authorization,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                    pending,
                    authorization,
                    StandardCopyOption.REPLACE_EXISTING
                );
            }
        } finally {
            Files.deleteIfExists(pending);
        }
    }

    static void clearDefault() throws IOException {
        Path inbox = defaultPath();
        Files.deleteIfExists(inbox);
        Files.deleteIfExists(processingPath(inbox));
    }

    static PollResult consumeDefault(long nowMillis, Scope scope) throws IOException {
        return consume(defaultPath(), nowMillis, scope);
    }

    static PollResult consume(Path inbox, long nowMillis, Scope scope) throws IOException {
        if (scope == Scope.DISABLED) return PollResult.absent();
        if (!Files.exists(inbox, LinkOption.NOFOLLOW_LINKS)) return PollResult.absent();
        if (!Files.isRegularFile(inbox, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(inbox);
            return PollResult.rejected("player_diagnostic_inbox_not_regular");
        }

        Path claimed = processingPath(inbox);
        Files.deleteIfExists(claimed);
        try {
            Files.move(
                inbox,
                claimed,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(inbox, claimed, StandardCopyOption.REPLACE_EXISTING);
        }

        try {
            if (!Files.isRegularFile(claimed, LinkOption.NOFOLLOW_LINKS)) {
                return PollResult.rejected("player_diagnostic_inbox_not_regular");
            }
            if (Files.size(claimed) > MAX_BYTES) {
                return PollResult.rejected("player_diagnostic_inbox_too_large");
            }
            long modifiedAt = Files.getLastModifiedTime(
                claimed,
                LinkOption.NOFOLLOW_LINKS
            ).toMillis();
            if (modifiedAt > nowMillis + 5_000L || nowMillis - modifiedAt > MAX_AGE_MILLIS) {
                return PollResult.rejected("player_diagnostic_inbox_stale");
            }

            try {
                String json = Files.readString(claimed, StandardCharsets.UTF_8);
                Map<String, Object> root = requiredObject(HelixJson.parse(json));
                exactKeys(
                    root,
                    Set.of(
                        "schema", "request_id", "action_kind", "arguments",
                        "max_duration_ticks", "control_engine"
                    ),
                    Set.of()
                );
                if (!SCHEMA.equals(text(root, "schema"))) {
                    throw invalid("player_diagnostic_inbox_schema_invalid");
                }
                String requestId = text(root, "request_id");
                if (!REQUEST_ID.matcher(requestId).matches()) {
                    throw invalid("player_diagnostic_inbox_request_id_invalid");
                }
                String actionKind = text(root, "action_kind");
                if (!FULL_ACTIONS.contains(actionKind)) {
                    throw invalid("player_diagnostic_inbox_action_invalid");
                }
                if (!scope.permits(actionKind)) {
                    throw invalid("player_diagnostic_inbox_scope_denied");
                }
                String controlEngine = enumText(
                    root,
                    "control_engine",
                    Set.of("native_fabric", "baritone")
                );
                if ("baritone".equals(controlEngine) && !"navigate_to".equals(actionKind)) {
                    throw invalid("player_diagnostic_inbox_control_engine_invalid");
                }
                long maxDurationTicks = integer(
                    root,
                    "max_duration_ticks",
                    1,
                    36_000
                );
                Map<String, Object> arguments = validateArguments(
                    actionKind,
                    requiredObject(root.get("arguments")),
                    controlEngine
                );
                return new PollResult(
                    new DiagnosticRequest(
                        requestId,
                        actionKind,
                        arguments,
                        maxDurationTicks,
                        controlEngine
                    ),
                    ""
                );
            } catch (ValidationFailure failure) {
                return PollResult.rejected(failure.code);
            } catch (RuntimeException failure) {
                return PollResult.rejected("player_diagnostic_inbox_invalid");
            }
        } finally {
            Files.deleteIfExists(claimed);
        }
    }

    private static Map<String, Object> validateArguments(
        String actionKind,
        Map<String, Object> raw,
        String controlEngine
    ) {
        Map<String, Object> value = new LinkedHashMap<>(raw);
        Object embeddedActionKind = value.remove("action_kind");
        if (embeddedActionKind != null && !actionKind.equals(embeddedActionKind)) {
            throw invalid("player_diagnostic_inbox_action_mismatch");
        }
        return switch (actionKind) {
            case "navigate_to" -> navigateArguments(value, controlEngine);
            case "look_at" -> lookArguments(value);
            case "track_target" -> trackingArguments(value);
            case "walk" -> walkArguments(value);
            case "jump" -> jumpArguments(value);
            case "interact" -> interactArguments(value);
            case "attack" -> attackArguments(value);
            case "combat_guard" -> combatGuardArguments(value);
            case "hotbar_select" -> hotbarArguments(value);
            case "equip" -> equipArguments(value);
            case "follow" -> followArguments(value);
            case "collect" -> collectArguments(value);
            case "mine" -> mineArguments(value);
            case "place" -> placeArguments(value);
            case "craft" -> craftArguments(value);
            case "consume" -> consumeArguments(value);
            case "inventory_transfer" -> transferArguments(value);
            case "execute_sequence" -> sequenceArguments(value);
            case "execute_reactive_program" -> reactiveProgramArguments(value);
            case "arm_viability_guardian" -> viabilityGuardianArguments(value);
            case "disarm_viability_guardian" -> disarmViabilityGuardianArguments(value);
            default -> throw invalid("player_diagnostic_inbox_action_invalid");
        };
    }

    private static Map<String, Object> viabilityGuardianArguments(
        Map<String, Object> value
    ) {
        exactKeys(
            value,
            Set.of(
                "profile_id", "duration_ticks", "minimum_air",
                "dangerous_vertical_velocity", "maximum_swim_ticks",
                "maximum_observation_age_ticks", "response_repertoire"
            ),
            Set.of()
        );
        if (!MinecraftViabilityGuardian.PROFILE_ID.equals(text(value, "profile_id"))) {
            throw invalid("player_diagnostic_inbox_guardian_profile_invalid");
        }
        List<Object> repertoire = requiredList(value.get("response_repertoire"));
        Set<String> requiredResponses = Set.of(
            "swim_up", "release_controls", "request_semantic_replan"
        );
        if (
            repertoire.size() != requiredResponses.size() ||
            !requiredResponses.equals(Set.copyOf(repertoire.stream().map(String::valueOf).toList()))
        ) {
            throw invalid("player_diagnostic_inbox_guardian_repertoire_invalid");
        }
        return Map.ofEntries(
            Map.entry("profile_id", MinecraftViabilityGuardian.PROFILE_ID),
            Map.entry("duration_ticks", integer(value, "duration_ticks", 200, 36_000)),
            Map.entry("minimum_air", integer(value, "minimum_air", 1, 300)),
            Map.entry(
                "dangerous_vertical_velocity",
                finite(value, "dangerous_vertical_velocity", -16, -0.01)
            ),
            Map.entry(
                "maximum_swim_ticks",
                integer(value, "maximum_swim_ticks", 1, 1_200)
            ),
            Map.entry(
                "maximum_observation_age_ticks",
                integer(value, "maximum_observation_age_ticks", 0, 20)
            ),
            Map.entry("response_repertoire", List.copyOf(repertoire))
        );
    }

    private static Map<String, Object> disarmViabilityGuardianArguments(
        Map<String, Object> value
    ) {
        exactKeys(value, Set.of("profile_id"), Set.of());
        if (!MinecraftViabilityGuardian.PROFILE_ID.equals(text(value, "profile_id"))) {
            throw invalid("player_diagnostic_inbox_guardian_profile_invalid");
        }
        return Map.of("profile_id", MinecraftViabilityGuardian.PROFILE_ID);
    }

    private static Map<String, Object> sequenceArguments(Map<String, Object> value) {
        Map<String, Object> sequence = new LinkedHashMap<>(value);
        sequence.put("action_kind", "execute_sequence");
        try {
            FluidSequenceEngine.validate(sequence);
        } catch (IllegalArgumentException error) {
            throw invalid("player_diagnostic_inbox_sequence_invalid");
        }
        return Map.copyOf(sequence);
    }

    private static Map<String, Object> reactiveProgramArguments(
        Map<String, Object> value
    ) {
        Map<String, Object> program = new LinkedHashMap<>(value);
        program.put("action_kind", "execute_reactive_program");
        try {
            ConcurrentReactiveScheduler.validate(program);
            for (Object laneRaw : requiredList(program.get("lanes"))) {
                Map<String, Object> lane = requiredObject(laneRaw);
                for (Object nodeRaw : requiredList(lane.get("nodes"))) {
                    Map<String, Object> node = requiredObject(nodeRaw);
                    String nodeKind = text(node, "node_kind");
                    if (!Set.of("action", "repeat", "maintain").contains(nodeKind)) {
                        continue;
                    }
                    Map<String, Object> action = requiredObject(node.get("action"));
                    validateArguments(text(action, "action_kind"), action, "native_fabric");
                }
            }
        } catch (IllegalArgumentException error) {
            throw invalid("player_diagnostic_inbox_reactive_program_invalid");
        }
        return Map.copyOf(program);
    }

    private static Map<String, Object> navigateArguments(
        Map<String, Object> value,
        String controlEngine
    ) {
        exactKeys(
            value,
            Set.of(
                "destination", "arrival_radius", "allow_sprint", "allow_dig",
                "allow_place", "engine_preference"
            ),
            Set.of()
        );
        String preference = enumText(
            value,
            "engine_preference",
            Set.of("adapter_selected", "native_fabric", "baritone")
        );
        if (
            ("baritone".equals(preference) && !"baritone".equals(controlEngine)) ||
            ("native_fabric".equals(preference) && !"native_fabric".equals(controlEngine))
        ) throw invalid("player_diagnostic_inbox_control_engine_mismatch");
        if (bool(value, "allow_dig") || bool(value, "allow_place")) {
            throw invalid("player_diagnostic_inbox_navigation_mutation_invalid");
        }
        return Map.of(
            "destination", position(value.get("destination")),
            "arrival_radius", finite(value, "arrival_radius", 0.25, 16),
            "allow_sprint", bool(value, "allow_sprint"),
            "allow_dig", false,
            "allow_place", false,
            "engine_preference", preference
        );
    }

    private static Map<String, Object> lookArguments(Map<String, Object> value) {
        exactKeys(value, Set.of("target", "max_turn_degrees_per_tick"), Set.of());
        Map<String, Object> target = requiredObject(value.get("target"));
        String kind = enumText(
            target,
            "target_kind",
            Set.of("position", "current_focus", "relative_rotation", "environment_subject")
        );
        Map<String, Object> normalizedTarget;
        switch (kind) {
            case "position" -> {
                exactKeys(target, Set.of("target_kind", "position"), Set.of());
                normalizedTarget = Map.of(
                    "target_kind", kind,
                    "position", position(target.get("position"))
                );
            }
            case "current_focus" -> {
                exactKeys(target, Set.of("target_kind"), Set.of());
                normalizedTarget = Map.of("target_kind", kind);
            }
            case "relative_rotation" -> {
                exactKeys(
                    target,
                    Set.of(
                        "target_kind", "yaw_delta_degrees", "pitch_delta_degrees"
                    ),
                    Set.of()
                );
                normalizedTarget = Map.of(
                    "target_kind", kind,
                    "yaw_delta_degrees", finite(
                        target,
                        "yaw_delta_degrees",
                        -180,
                        180
                    ),
                    "pitch_delta_degrees", finite(
                        target,
                        "pitch_delta_degrees",
                        -180,
                        180
                    )
                );
            }
            case "environment_subject" -> {
                exactKeys(target, Set.of("target_kind", "subject_ref"), Set.of());
                normalizedTarget = Map.of(
                    "target_kind", kind,
                    "subject_ref", subject(target, "subject_ref")
                );
            }
            default -> throw invalid("player_diagnostic_inbox_look_target_invalid");
        }
        return Map.of(
            "target", normalizedTarget,
            "max_turn_degrees_per_tick", finite(
                value,
                "max_turn_degrees_per_tick",
                Double.MIN_NORMAL,
                180
            )
        );
    }

    private static Map<String, Object> walkArguments(Map<String, Object> value) {
        exactKeys(value, Set.of("direction", "duration_ms", "sprint"), Set.of("jump"));
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("direction", enumText(value, "direction", Set.of("forward", "back", "left", "right")));
        normalized.put("duration_ms", integer(value, "duration_ms", 50, 10_000));
        normalized.put("sprint", bool(value, "sprint"));
        normalized.put("jump", value.containsKey("jump") && bool(value, "jump"));
        return Map.copyOf(normalized);
    }

    private static Map<String, Object> trackingArguments(Map<String, Object> value) {
        exactKeys(
            value,
            Set.of(
                "target", "aim_point", "max_acquisition_distance",
                "max_duration_ms", "max_turn_degrees_per_tick",
                "max_angular_acceleration_degrees_per_tick_squared",
                "prediction_ticks", "deadband_degrees", "reacquire_ticks",
                "require_line_of_sight", "stop_below_health"
            ),
            Set.of()
        );
        Map<String, Object> target = requiredObject(value.get("target"));
        String targetKind = enumText(
            target,
            "target_kind",
            Set.of("entity_type", "current_focus_entity", "particle_type")
        );
        Map<String, Object> normalizedTarget;
        if ("entity_type".equals(targetKind)) {
            exactKeys(
                target,
                Set.of("target_kind", "entity_type_id", "selection"),
                Set.of()
            );
            if (!"nearest".equals(enumText(target, "selection", Set.of("nearest")))) {
                throw invalid("player_diagnostic_inbox_tracking_selection_invalid");
            }
            normalizedTarget = Map.of(
                "target_kind", targetKind,
                "entity_type_id", resource(target, "entity_type_id"),
                "selection", "nearest"
            );
        } else if ("particle_type".equals(targetKind)) {
            exactKeys(
                target,
                Set.of(
                    "target_kind", "particle_type_id", "selection",
                    "continuity", "handoff_radius", "max_handoffs"
                ),
                Set.of()
            );
            if (!"nearest".equals(enumText(target, "selection", Set.of("nearest")))) {
                throw invalid("player_diagnostic_inbox_tracking_selection_invalid");
            }
            String continuity = enumText(
                target,
                "continuity",
                Set.of("single_instance", "same_type_stream")
            );
            double handoffRadius = finite(target, "handoff_radius", 0, 8);
            long maxHandoffs = integer(target, "max_handoffs", 0, 1_000);
            if (
                ("single_instance".equals(continuity) &&
                    (handoffRadius != 0 || maxHandoffs != 0)) ||
                ("same_type_stream".equals(continuity) &&
                    (handoffRadius <= 0 || maxHandoffs < 1))
            ) {
                throw invalid("player_diagnostic_inbox_particle_continuity_invalid");
            }
            normalizedTarget = Map.of(
                "target_kind", targetKind,
                "particle_type_id", resource(target, "particle_type_id"),
                "selection", "nearest",
                "continuity", continuity,
                "handoff_radius", handoffRadius,
                "max_handoffs", maxHandoffs
            );
        } else {
            exactKeys(target, Set.of("target_kind"), Set.of());
            normalizedTarget = Map.of("target_kind", targetKind);
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("target", normalizedTarget);
        normalized.put(
            "aim_point",
            enumText(
                value,
                "aim_point",
                Set.of("center", "render_center", "eyes", "feet")
            )
        );
        normalized.put(
            "max_acquisition_distance",
            finite(value, "max_acquisition_distance", 1, 128)
        );
        normalized.put(
            "max_duration_ms",
            integer(value, "max_duration_ms", 1_000, 5 * 60_000)
        );
        normalized.put(
            "max_turn_degrees_per_tick",
            finite(value, "max_turn_degrees_per_tick", 0.1, 180)
        );
        normalized.put(
            "max_angular_acceleration_degrees_per_tick_squared",
            finite(
                value,
                "max_angular_acceleration_degrees_per_tick_squared",
                0.01,
                180
            )
        );
        normalized.put("prediction_ticks", integer(value, "prediction_ticks", 0, 10));
        normalized.put("deadband_degrees", finite(value, "deadband_degrees", 0, 10));
        normalized.put("reacquire_ticks", integer(value, "reacquire_ticks", 0, 200));
        normalized.put("require_line_of_sight", bool(value, "require_line_of_sight"));
        normalized.put("stop_below_health", finite(value, "stop_below_health", 1, 20));
        return Map.copyOf(normalized);
    }

    private static Map<String, Object> attackArguments(Map<String, Object> value) {
        exactKeys(
            value,
            Set.of(
                "target_ref", "target_entity_type_id", "target_classification",
                "max_acquisition_distance", "require_line_of_sight",
                "minimum_attack_cooldown", "max_attack_pulses",
                "max_duration_ms", "stop_below_health", "friendly_fire"
            ),
            Set.of()
        );
        if (!"hostile".equals(enumText(
            value,
            "target_classification",
            Set.of("hostile")
        ))) {
            throw invalid("player_diagnostic_inbox_attack_target_class_invalid");
        }
        if (!bool(value, "require_line_of_sight") || bool(value, "friendly_fire")) {
            throw invalid("player_diagnostic_inbox_attack_safety_invalid");
        }
        return Map.ofEntries(
            Map.entry("target_ref", subject(value, "target_ref")),
            Map.entry("target_entity_type_id", resource(value, "target_entity_type_id")),
            Map.entry("target_classification", "hostile"),
            Map.entry(
                "max_acquisition_distance",
                finite(value, "max_acquisition_distance", 1, 16)
            ),
            Map.entry("require_line_of_sight", true),
            Map.entry(
                "minimum_attack_cooldown",
                finite(value, "minimum_attack_cooldown", 0.1, 1)
            ),
            Map.entry("max_attack_pulses", integer(value, "max_attack_pulses", 1, 64)),
            Map.entry("max_duration_ms", integer(value, "max_duration_ms", 1_000, 60_000)),
            Map.entry("stop_below_health", finite(value, "stop_below_health", 1, 20)),
            Map.entry("friendly_fire", false)
        );
    }

    private static Map<String, Object> combatGuardArguments(Map<String, Object> value) {
        exactKeys(
            value,
            Set.of(
                "hostile_entity_type_ids", "max_acquisition_distance",
                "require_line_of_sight", "minimum_attack_cooldown",
                "max_attack_pulses", "max_target_switches", "target_commit_ticks",
                "retreat_start_distance", "retreat_stop_distance",
                "retreat_when_hostile_count_at_least", "max_duration_ms",
                "stop_below_health", "friendly_fire"
            ),
            Set.of(
                "combat_mode", "approach_policy", "max_approach_ticks", "cover_policy",
                "max_cover_ticks", "projectile_response",
                "projectile_evasion_horizon_ticks", "max_evasion_ticks",
                "shield_hand", "max_shield_hold_ticks"
            )
        );
        if (!bool(value, "require_line_of_sight") || bool(value, "friendly_fire")) {
            throw invalid("player_diagnostic_inbox_combat_guard_safety_invalid");
        }
        List<Object> rawTypes = requiredList(value.get("hostile_entity_type_ids"));
        if (rawTypes.isEmpty() || rawTypes.size() > 16) {
            throw invalid("player_diagnostic_inbox_combat_guard_types_invalid");
        }
        List<String> hostileTypes = new ArrayList<>();
        for (Object rawType : rawTypes) {
            if (!(rawType instanceof String typeId) ||
                !RESOURCE_LOCATION.matcher(typeId).matches()) {
                throw invalid("player_diagnostic_inbox_combat_guard_types_invalid");
            }
            hostileTypes.add(typeId);
        }
        if (Set.copyOf(hostileTypes).size() != hostileTypes.size()) {
            throw invalid("player_diagnostic_inbox_combat_guard_types_invalid");
        }
        double retreatStart = finite(value, "retreat_start_distance", 1, 6);
        double retreatStop = finite(value, "retreat_stop_distance", 1, 8);
        if (retreatStop <= retreatStart) {
            throw invalid("player_diagnostic_inbox_combat_guard_retreat_invalid");
        }
        String approachPolicy = value.containsKey("approach_policy")
            ? enumText(
                value,
                "approach_policy",
                Set.of("none", "direct_bounded", "local_reroute_bounded")
            )
            : "none";
        long maxApproachTicks = value.containsKey("max_approach_ticks")
            ? integer(value, "max_approach_ticks", 0, 1_200)
            : 0;
        if (!"none".equals(approachPolicy) != (maxApproachTicks > 0)) {
            throw invalid("player_diagnostic_inbox_combat_guard_approach_invalid");
        }
        String coverPolicy = value.containsKey("cover_policy")
            ? enumText(value, "cover_policy", Set.of("none", "lateral_bounded"))
            : "none";
        long maxCoverTicks = value.containsKey("max_cover_ticks")
            ? integer(value, "max_cover_ticks", 0, 1_200)
            : 0;
        if ("lateral_bounded".equals(coverPolicy) != (maxCoverTicks > 0)) {
            throw invalid("player_diagnostic_inbox_combat_guard_cover_invalid");
        }
        String projectileResponse = value.containsKey("projectile_response")
            ? enumText(
                value,
                "projectile_response",
                Set.of("none", "sidestep", "shield_or_sidestep")
            )
            : "none";
        long projectileHorizon = value.containsKey("projectile_evasion_horizon_ticks")
            ? integer(value, "projectile_evasion_horizon_ticks", 1, 20)
            : 8;
        long maxEvasionTicks = value.containsKey("max_evasion_ticks")
            ? integer(value, "max_evasion_ticks", 0, 1_200)
            : 0;
        String shieldHand = value.containsKey("shield_hand")
            ? enumText(value, "shield_hand", Set.of("none", "off_hand"))
            : "none";
        long maxShieldHoldTicks = value.containsKey("max_shield_hold_ticks")
            ? integer(value, "max_shield_hold_ticks", 0, 1_200)
            : 0;
        if (!"none".equals(projectileResponse) && maxEvasionTicks == 0) {
            throw invalid("player_diagnostic_inbox_combat_guard_projectile_invalid");
        }
        if ("shield_or_sidestep".equals(projectileResponse)) {
            if (!"off_hand".equals(shieldHand) || maxShieldHoldTicks == 0) {
                throw invalid("player_diagnostic_inbox_combat_guard_shield_invalid");
            }
        } else if (!"none".equals(shieldHand) || maxShieldHoldTicks != 0) {
            throw invalid("player_diagnostic_inbox_combat_guard_shield_invalid");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("hostile_entity_type_ids", List.copyOf(hostileTypes));
        normalized.put(
            "combat_mode",
            value.containsKey("combat_mode")
                ? enumText(value, "combat_mode", Set.of("engage", "disengage_to_distance"))
                : "engage"
        );
        normalized.put("max_acquisition_distance", finite(value, "max_acquisition_distance", 2, 32));
        normalized.put("require_line_of_sight", true);
        normalized.put("minimum_attack_cooldown", finite(value, "minimum_attack_cooldown", 0.1, 1));
        normalized.put("max_attack_pulses", integer(value, "max_attack_pulses", 1, 256));
        normalized.put("max_target_switches", integer(value, "max_target_switches", 0, 64));
        normalized.put("target_commit_ticks", integer(value, "target_commit_ticks", 0, 200));
        normalized.put("retreat_start_distance", retreatStart);
        normalized.put("retreat_stop_distance", retreatStop);
        normalized.put("retreat_when_hostile_count_at_least", integer(value, "retreat_when_hostile_count_at_least", 1, 16));
        normalized.put("max_duration_ms", integer(value, "max_duration_ms", 1_000, 120_000));
        normalized.put("stop_below_health", finite(value, "stop_below_health", 1, 20));
        normalized.put("friendly_fire", false);
        normalized.put("approach_policy", approachPolicy);
        normalized.put("max_approach_ticks", maxApproachTicks);
        normalized.put("cover_policy", coverPolicy);
        normalized.put("max_cover_ticks", maxCoverTicks);
        normalized.put("projectile_response", projectileResponse);
        normalized.put("projectile_evasion_horizon_ticks", projectileHorizon);
        normalized.put("max_evasion_ticks", maxEvasionTicks);
        normalized.put("shield_hand", shieldHand);
        normalized.put("max_shield_hold_ticks", maxShieldHoldTicks);
        return Map.copyOf(normalized);
    }

    private static Map<String, Object> jumpArguments(Map<String, Object> value) {
        exactKeys(value, Set.of("count"), Set.of());
        return Map.of("count", integer(value, "count", 1, 10));
    }

    private static Map<String, Object> interactArguments(Map<String, Object> value) {
        exactKeys(value, Set.of("target", "hand", "interaction"), Set.of());
        return Map.of(
            "target", enumText(
                value,
                "target",
                Set.of("current_focus", "looked_at_block", "looked_at_entity")
            ),
            "hand", enumText(value, "hand", Set.of("main_hand", "off_hand")),
            "interaction", enumText(value, "interaction", Set.of("use", "interact"))
        );
    }

    private static Map<String, Object> hotbarArguments(Map<String, Object> value) {
        exactKeys(value, Set.of("slot"), Set.of());
        return Map.of("slot", integer(value, "slot", 0, 8));
    }

    private static Map<String, Object> equipArguments(Map<String, Object> value) {
        exactKeys(value, Set.of("item_id", "destination"), Set.of());
        return Map.of(
            "item_id", resource(value, "item_id"),
            "destination", enumText(
                value,
                "destination",
                Set.of("main_hand", "off_hand", "head", "chest", "legs", "feet")
            )
        );
    }

    private static Map<String, Object> followArguments(Map<String, Object> value) {
        exactKeys(
            value,
            Set.of(
                "target_subject_native_id", "distance", "max_duration_ms",
                "stop_below_health"
            ),
            Set.of("subject_ref", "target_subject_label")
        );
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put(
            "target_subject_native_id",
            subject(value, "target_subject_native_id")
        );
        normalized.put("distance", finite(value, "distance", 1, 64));
        normalized.put(
            "max_duration_ms",
            integer(value, "max_duration_ms", 1_000, 30 * 60_000)
        );
        normalized.put(
            "stop_below_health",
            finite(value, "stop_below_health", 1, 20)
        );
        if (value.containsKey("subject_ref")) {
            normalized.put("subject_ref", subject(value, "subject_ref"));
        }
        if (value.containsKey("target_subject_label")) {
            normalized.put(
                "target_subject_label",
                boundedText(value, "target_subject_label", 1, 320)
            );
        }
        return Map.copyOf(normalized);
    }

    private static Map<String, Object> collectArguments(Map<String, Object> value) {
        exactKeys(value, Set.of("item_or_block_id", "count", "search_radius"), Set.of());
        return Map.of(
            "item_or_block_id", resource(value, "item_or_block_id"),
            "count", integer(value, "count", 1, 2_304),
            "search_radius", finite(value, "search_radius", Double.MIN_NORMAL, 128)
        );
    }

    private static Map<String, Object> mineArguments(Map<String, Object> value) {
        exactKeys(
            value,
            Set.of("block_id", "count", "search_radius"),
            Set.of("target_position")
        );
        long count = integer(value, "count", 1, 4_096);
        if (value.containsKey("target_position") && count != 1) {
            throw invalid("player_diagnostic_inbox_exact_mine_count_invalid");
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("block_id", resource(value, "block_id"));
        normalized.put("count", count);
        normalized.put("search_radius", integer(value, "search_radius", 1, 32));
        if (value.containsKey("target_position")) {
            normalized.put("target_position", blockPosition(value.get("target_position")));
        }
        return Map.copyOf(normalized);
    }

    private static Map<String, Object> placeArguments(Map<String, Object> value) {
        exactKeys(
            value,
            Set.of("block_id"),
            Set.of(
                "positions", "position_binding", "placement_method",
                "source_item_id", "hand", "cleanup_after_landing"
            )
        );
        boolean hasPositions = value.containsKey("positions");
        boolean hasBinding = value.containsKey("position_binding");
        if (hasPositions == hasBinding) {
            throw invalid("player_diagnostic_inbox_position_source_invalid");
        }
        String method = value.containsKey("placement_method")
            ? enumText(
                value,
                "placement_method",
                Set.of("block_item", "item_use")
            )
            : "block_item";
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("block_id", resource(value, "block_id"));
        if (hasPositions) {
            List<Object> rawPositions = requiredList(value.get("positions"));
            if (rawPositions.isEmpty() || rawPositions.size() > 256) {
                throw invalid("player_diagnostic_inbox_positions_invalid");
            }
            List<Map<String, Object>> positions = new ArrayList<>();
            for (Object rawPosition : rawPositions) {
                positions.add(blockPosition(rawPosition));
            }
            normalized.put("positions", List.copyOf(positions));
        } else {
            normalized.put(
                "position_binding",
                predictedCollisionPositionBinding(value.get("position_binding"))
            );
        }
        normalized.put("placement_method", method);
        if ("item_use".equals(method)) {
            normalized.put("source_item_id", resource(value, "source_item_id"));
            normalized.put(
                "hand",
                enumText(value, "hand", Set.of("main_hand", "off_hand"))
            );
            if (
                value.containsKey("cleanup_after_landing") &&
                bool(value, "cleanup_after_landing")
            ) {
                if (
                    !"minecraft:water".equals(normalized.get("block_id")) ||
                    !"minecraft:water_bucket".equals(normalized.get("source_item_id")) ||
                    !hasBinding
                ) {
                    throw invalid("player_diagnostic_inbox_landing_cleanup_invalid");
                }
                normalized.put("cleanup_after_landing", true);
            }
        } else if (value.containsKey("source_item_id") || value.containsKey("hand")) {
            throw invalid("player_diagnostic_inbox_block_item_source_invalid");
        } else if (value.containsKey("cleanup_after_landing")) {
            throw invalid("player_diagnostic_inbox_landing_cleanup_invalid");
        }
        return Map.copyOf(normalized);
    }

    private static Map<String, Object> predictedCollisionPositionBinding(Object raw) {
        Map<String, Object> value = requiredObject(raw);
        exactKeys(
            value,
            Set.of(
                "binding_kind", "horizon_ticks", "max_distance_blocks",
                "require_replaceable"
            ),
            Set.of()
        );
        if (!"predicted_collision_cell".equals(text(value, "binding_kind"))) {
            throw invalid("player_diagnostic_inbox_position_binding_invalid");
        }
        if (!bool(value, "require_replaceable")) {
            throw invalid("player_diagnostic_inbox_position_binding_invalid");
        }
        return Map.of(
            "binding_kind", "predicted_collision_cell",
            "horizon_ticks", integer(value, "horizon_ticks", 1, 20),
            "max_distance_blocks", finite(
                value,
                "max_distance_blocks",
                Double.MIN_NORMAL,
                6
            ),
            "require_replaceable", true
        );
    }

    private static Map<String, Object> craftArguments(Map<String, Object> value) {
        exactKeys(
            value,
            Set.of("output_item_id", "count"),
            Set.of("recipe_id")
        );
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("output_item_id", resource(value, "output_item_id"));
        normalized.put("count", integer(value, "count", 1, 2_304));
        if (value.get("recipe_id") != null) {
            normalized.put("recipe_id", resource(value, "recipe_id"));
        }
        return Map.copyOf(normalized);
    }

    private static Map<String, Object> consumeArguments(Map<String, Object> value) {
        exactKeys(
            value,
            Set.of(
                "item_id", "count", "hand", "max_duration_ms",
                "stop_below_health", "minimum_food_gain"
            ),
            Set.of("expected_remainder_item_id")
        );
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("item_id", resource(value, "item_id"));
        normalized.put("count", integer(value, "count", 1, 64));
        normalized.put("hand", enumText(value, "hand", Set.of("main_hand")));
        normalized.put("max_duration_ms", integer(value, "max_duration_ms", 1_000, 60_000));
        normalized.put("stop_below_health", finite(value, "stop_below_health", 1, 20));
        normalized.put("minimum_food_gain", integer(value, "minimum_food_gain", 0, 20));
        if (value.get("expected_remainder_item_id") != null) {
            normalized.put(
                "expected_remainder_item_id",
                resource(value, "expected_remainder_item_id")
            );
        }
        return Map.copyOf(normalized);
    }

    private static Map<String, Object> transferArguments(Map<String, Object> value) {
        exactKeys(
            value,
            Set.of("direction", "item_id", "count", "container_target"),
            Set.of()
        );
        return Map.of(
            "direction", enumText(value, "direction", Set.of("deposit", "withdraw")),
            "item_id", resource(value, "item_id"),
            "count", integer(value, "count", 1, 2_304),
            "container_target", enumText(
                value,
                "container_target",
                Set.of("current_open_container", "looked_at_container")
            )
        );
    }

    private static Map<String, Object> position(Object raw) {
        Map<String, Object> value = requiredObject(raw);
        exactKeys(value, Set.of("x", "y", "z"), Set.of());
        return Map.of(
            "x", finite(value, "x", -30_000_000, 30_000_000),
            "y", finite(value, "y", -2_048, 2_048),
            "z", finite(value, "z", -30_000_000, 30_000_000)
        );
    }

    private static Map<String, Object> blockPosition(Object raw) {
        Map<String, Object> value = requiredObject(raw);
        exactKeys(value, Set.of("x", "y", "z"), Set.of());
        return Map.of(
            "x", integer(value, "x", -30_000_000, 30_000_000),
            "y", integer(value, "y", -2_048, 2_048),
            "z", integer(value, "z", -30_000_000, 30_000_000)
        );
    }

    private static String resource(Map<String, Object> value, String key) {
        String text = boundedText(value, key, 1, 320);
        if (!RESOURCE_LOCATION.matcher(text).matches()) {
            throw invalid("player_diagnostic_inbox_resource_invalid");
        }
        return text;
    }

    private static String subject(Map<String, Object> value, String key) {
        String text = boundedText(value, key, 1, 320);
        if (!SUBJECT_REF.matcher(text).matches()) {
            throw invalid("player_diagnostic_inbox_subject_invalid");
        }
        return text;
    }

    private static String enumText(
        Map<String, Object> value,
        String key,
        Set<String> allowed
    ) {
        String text = boundedText(value, key, 1, 320);
        if (!allowed.contains(text)) {
            throw invalid("player_diagnostic_inbox_enum_invalid");
        }
        return text;
    }

    private static String boundedText(
        Map<String, Object> value,
        String key,
        int minimum,
        int maximum
    ) {
        String text = text(value, key);
        if (text.length() < minimum || text.length() > maximum) {
            throw invalid("player_diagnostic_inbox_text_invalid");
        }
        return text;
    }

    private static String text(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        if (!(raw instanceof String text)) {
            throw invalid("player_diagnostic_inbox_text_invalid");
        }
        return text.trim();
    }

    private static boolean bool(Map<String, Object> value, String key) {
        Object raw = value.get(key);
        if (!(raw instanceof Boolean bool)) {
            throw invalid("player_diagnostic_inbox_boolean_invalid");
        }
        return bool;
    }

    private static double finite(
        Map<String, Object> value,
        String key,
        double minimum,
        double maximum
    ) {
        Object raw = value.get(key);
        if (!(raw instanceof Number number)) {
            throw invalid("player_diagnostic_inbox_number_invalid");
        }
        double resolved = number.doubleValue();
        if (!Double.isFinite(resolved) || resolved < minimum || resolved > maximum) {
            throw invalid("player_diagnostic_inbox_number_invalid");
        }
        return resolved;
    }

    private static long integer(
        Map<String, Object> value,
        String key,
        long minimum,
        long maximum
    ) {
        Object raw = value.get(key);
        if (!(raw instanceof Number number)) {
            throw invalid("player_diagnostic_inbox_integer_invalid");
        }
        double floating = number.doubleValue();
        long resolved = number.longValue();
        if (
            !Double.isFinite(floating) || floating != resolved ||
            resolved < minimum || resolved > maximum
        ) throw invalid("player_diagnostic_inbox_integer_invalid");
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requiredObject(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw invalid("player_diagnostic_inbox_object_invalid");
        }
        return new LinkedHashMap<>((Map<String, Object>) map);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> requiredList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            throw invalid("player_diagnostic_inbox_list_invalid");
        }
        return new ArrayList<>((List<Object>) list);
    }

    private static void exactKeys(
        Map<String, Object> value,
        Set<String> required,
        Set<String> optional
    ) {
        if (!value.keySet().containsAll(required)) {
            throw invalid("player_diagnostic_inbox_fields_invalid");
        }
        for (String key : value.keySet()) {
            if (!required.contains(key) && !optional.contains(key)) {
                throw invalid("player_diagnostic_inbox_fields_invalid");
            }
        }
    }

    private static ValidationFailure invalid(String code) {
        return new ValidationFailure(code);
    }

    private static Path processingPath(Path inbox) {
        return inbox.resolveSibling(inbox.getFileName() + ".processing");
    }

    private static Path pendingAuthorizationPath(Path authorization) {
        return authorization.resolveSibling(authorization.getFileName() + ".pending");
    }
}
