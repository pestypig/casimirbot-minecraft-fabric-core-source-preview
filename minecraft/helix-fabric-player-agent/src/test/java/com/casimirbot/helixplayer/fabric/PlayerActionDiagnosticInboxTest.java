package com.casimirbot.helixplayer.fabric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.casimirbot.helixsensor.HelixJson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class PlayerActionDiagnosticInboxTest {
    @TempDir
    Path tempDir;

    @Test
    void atomicallyConsumesAValidatedMovementRequest() throws Exception {
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-walk",
            "action_kind", "walk",
            "arguments", Map.of(
                "action_kind", "walk",
                "direction", "forward",
                "duration_ms", 250,
                "sprint", false
            ),
            "max_duration_ticks", 25,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.MOVEMENT
            );

        assertEquals("walk", result.request().actionKind());
        assertEquals("forward", result.request().arguments().get("direction"));
        assertFalse(result.request().arguments().containsKey("action_kind"));
        assertFalse(Files.exists(inbox));
        assertFalse(Files.exists(processing(inbox)));
    }

    @Test
    void fullScopeAcceptsBoundedHostileCombatGuard() throws Exception {
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-combat-guard",
            "action_kind", "combat_guard",
            "arguments", Map.ofEntries(
                Map.entry("action_kind", "combat_guard"),
                Map.entry("hostile_entity_type_ids", List.of("minecraft:zombie")),
                Map.entry("max_acquisition_distance", 16),
                Map.entry("require_line_of_sight", true),
                Map.entry("minimum_attack_cooldown", 0.9),
                Map.entry("max_attack_pulses", 32),
                Map.entry("max_target_switches", 8),
                Map.entry("target_commit_ticks", 10),
                Map.entry("retreat_start_distance", 2.5),
                Map.entry("retreat_stop_distance", 4.0),
                Map.entry("retreat_when_hostile_count_at_least", 2),
                Map.entry("max_duration_ms", 30_000),
                Map.entry("stop_below_health", 4),
                Map.entry("friendly_fire", false)
            ),
            "max_duration_ticks", 600,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.FULL
            );

        assertEquals("combat_guard", result.request().actionKind());
        assertEquals(
            List.of("minecraft:zombie"),
            result.request().arguments().get("hostile_entity_type_ids")
        );
        assertEquals(false, result.request().arguments().get("friendly_fire"));
        assertEquals("none", result.request().arguments().get("approach_policy"));
        assertEquals("none", result.request().arguments().get("cover_policy"));
        assertEquals("none", result.request().arguments().get("projectile_response"));
    }

    @Test
    void fullScopeAcceptsExplicitRangedCombatRepertoire() throws Exception {
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-ranged-combat-guard",
            "action_kind", "combat_guard",
            "arguments", Map.ofEntries(
                Map.entry("action_kind", "combat_guard"),
                Map.entry("hostile_entity_type_ids", List.of("minecraft:skeleton")),
                Map.entry("max_acquisition_distance", 24),
                Map.entry("require_line_of_sight", true),
                Map.entry("minimum_attack_cooldown", 0.9),
                Map.entry("max_attack_pulses", 48),
                Map.entry("max_target_switches", 4),
                Map.entry("target_commit_ticks", 10),
                Map.entry("retreat_start_distance", 2.5),
                Map.entry("retreat_stop_distance", 4.0),
                Map.entry("retreat_when_hostile_count_at_least", 2),
                Map.entry("max_duration_ms", 30_000),
                Map.entry("stop_below_health", 12),
                Map.entry("friendly_fire", false),
                Map.entry("approach_policy", "local_reroute_bounded"),
                Map.entry("max_approach_ticks", 300),
                Map.entry("cover_policy", "lateral_bounded"),
                Map.entry("max_cover_ticks", 120),
                Map.entry("projectile_response", "shield_or_sidestep"),
                Map.entry("projectile_evasion_horizon_ticks", 8),
                Map.entry("max_evasion_ticks", 200),
                Map.entry("shield_hand", "off_hand"),
                Map.entry("max_shield_hold_ticks", 200)
            ),
            "max_duration_ticks", 600,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.FULL
            );

        assertEquals("local_reroute_bounded", result.request().arguments().get("approach_policy"));
        assertEquals(300L, result.request().arguments().get("max_approach_ticks"));
        assertEquals("lateral_bounded", result.request().arguments().get("cover_policy"));
        assertEquals(120L, result.request().arguments().get("max_cover_ticks"));
        assertEquals("shield_or_sidestep", result.request().arguments().get("projectile_response"));
        assertEquals("off_hand", result.request().arguments().get("shield_hand"));
    }

    @Test
    void fullScopeRejectsProjectileResponseWithoutEvasionBudget() throws Exception {
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-invalid-ranged-combat-guard",
            "action_kind", "combat_guard",
            "arguments", Map.ofEntries(
                Map.entry("action_kind", "combat_guard"),
                Map.entry("hostile_entity_type_ids", List.of("minecraft:skeleton")),
                Map.entry("max_acquisition_distance", 24),
                Map.entry("require_line_of_sight", true),
                Map.entry("minimum_attack_cooldown", 0.9),
                Map.entry("max_attack_pulses", 48),
                Map.entry("max_target_switches", 4),
                Map.entry("target_commit_ticks", 10),
                Map.entry("retreat_start_distance", 2.5),
                Map.entry("retreat_stop_distance", 4.0),
                Map.entry("retreat_when_hostile_count_at_least", 2),
                Map.entry("max_duration_ms", 30_000),
                Map.entry("stop_below_health", 12),
                Map.entry("friendly_fire", false),
                Map.entry("projectile_response", "sidestep"),
                Map.entry("max_evasion_ticks", 0)
            ),
            "max_duration_ticks", 600,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.FULL
            );

        assertNull(result.request());
        assertEquals(
            "player_diagnostic_inbox_combat_guard_projectile_invalid",
            result.failureCode()
        );
    }

    @Test
    void fullScopeAcceptsTheExactResidentGuardianProfile() throws Exception {
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-resident-guardian",
            "action_kind", "arm_viability_guardian",
            "arguments", Map.ofEntries(
                Map.entry("profile_id", MinecraftViabilityGuardian.PROFILE_ID),
                Map.entry("duration_ticks", 2_400),
                Map.entry("minimum_air", 80),
                Map.entry("dangerous_vertical_velocity", -0.72),
                Map.entry("maximum_swim_ticks", 200),
                Map.entry("maximum_observation_age_ticks", 1),
                Map.entry(
                    "response_repertoire",
                    List.of("swim_up", "release_controls", "request_semantic_replan")
                )
            ),
            "max_duration_ticks", 2_500,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.FULL
            );

        assertEquals("arm_viability_guardian", result.request().actionKind());
        assertEquals(2_400L, result.request().arguments().get("duration_ticks"));
        assertEquals(
            MinecraftViabilityGuardian.PROFILE_ID,
            result.request().arguments().get("profile_id")
        );
    }

    @Test
    void fullScopeAcceptsExactResidentGuardianDisarm() throws Exception {
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-resident-disarm",
            "action_kind", "disarm_viability_guardian",
            "arguments", Map.of(
                "profile_id", MinecraftViabilityGuardian.PROFILE_ID
            ),
            "max_duration_ticks", 100,
            "control_engine", "native_fabric"
        ));

        var result = PlayerActionDiagnosticInbox.consume(
            inbox,
            System.currentTimeMillis(),
            PlayerActionDiagnosticInbox.Scope.FULL
        );

        assertEquals("disarm_viability_guardian", result.request().actionKind());
        assertEquals(
            MinecraftViabilityGuardian.PROFILE_ID,
            result.request().arguments().get("profile_id")
        );
    }

    @Test
    void movementScopeRejectsWorldMutationAndDeletesTheRequest() throws Exception {
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-mine",
            "action_kind", "mine",
            "arguments", Map.of(
                "block_id", "minecraft:stone",
                "count", 1,
                "search_radius", 4
            ),
            "max_duration_ticks", 200,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.MOVEMENT
            );

        assertNull(result.request());
        assertEquals("player_diagnostic_inbox_scope_denied", result.failureCode());
        assertFalse(Files.exists(inbox));
    }

    @Test
    void fullScopeAcceptsOneExactMiningTarget() throws Exception {
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-exact-mine",
            "action_kind", "mine",
            "arguments", Map.of(
                "block_id", "minecraft:iron_ore",
                "count", 1,
                "search_radius", 32,
                "target_position", Map.of("x", -11, "y", 40, "z", -2)
            ),
            "max_duration_ticks", 200,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.FULL
            );

        assertEquals("mine", result.request().actionKind());
        assertEquals(
            Map.of("x", -11L, "y", 40L, "z", -2L),
            result.request().arguments().get("target_position")
        );
    }

    @Test
    void fullScopeAcceptsBoundedMushroomStewConsumption() throws Exception {
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-consume-stew",
            "action_kind", "consume",
            "arguments", Map.of(
                "item_id", "minecraft:mushroom_stew",
                "count", 1,
                "hand", "main_hand",
                "max_duration_ms", 8_000,
                "stop_below_health", 8,
                "minimum_food_gain", 6,
                "expected_remainder_item_id", "minecraft:bowl"
            ),
            "max_duration_ticks", 200,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.FULL
            );

        assertEquals("consume", result.request().actionKind());
        assertEquals("minecraft:mushroom_stew", result.request().arguments().get("item_id"));
        assertEquals("main_hand", result.request().arguments().get("hand"));
        assertEquals("minecraft:bowl", result.request().arguments().get("expected_remainder_item_id"));
    }

    @Test
    void fullScopeRejectsOffHandConsumption() throws Exception {
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-consume-off-hand",
            "action_kind", "consume",
            "arguments", Map.of(
                "item_id", "minecraft:mushroom_stew",
                "count", 1,
                "hand", "off_hand",
                "max_duration_ms", 8_000,
                "stop_below_health", 8,
                "minimum_food_gain", 6
            ),
            "max_duration_ticks", 200,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.FULL
            );

        assertNull(result.request());
        assertEquals("player_diagnostic_inbox_enum_invalid", result.failureCode());
    }

    @Test
    void fullScopeRejectsExactMiningWithMultipleRequestedBlocks() throws Exception {
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-invalid-exact-mine",
            "action_kind", "mine",
            "arguments", Map.of(
                "block_id", "minecraft:iron_ore",
                "count", 2,
                "search_radius", 32,
                "target_position", Map.of("x", -11, "y", 40, "z", -2)
            ),
            "max_duration_ticks", 200,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.FULL
            );

        assertNull(result.request());
        assertEquals(
            "player_diagnostic_inbox_exact_mine_count_invalid",
            result.failureCode()
        );
    }

    @Test
    void fullScopeAcceptsExactPlacementPositions() throws Exception {
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-place",
            "action_kind", "place",
            "arguments", Map.of(
                "block_id", "minecraft:cobblestone",
                "positions", List.of(Map.of("x", 1, "y", 64, "z", 2))
            ),
            "max_duration_ticks", 400,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.FULL
            );

        assertEquals("place", result.request().actionKind());
        assertEquals(
            List.of(Map.of("x", 1L, "y", 64L, "z", 2L)),
            result.request().arguments().get("positions")
        );
    }

    @Test
    void fullScopeRetainsExactItemUsePlacementAuthority() throws Exception {
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-item-use-place",
            "action_kind", "place",
            "arguments", Map.of(
                "block_id", "minecraft:water",
                "positions", List.of(Map.of("x", 1, "y", 64, "z", 2)),
                "placement_method", "item_use",
                "source_item_id", "minecraft:water_bucket",
                "hand", "off_hand"
            ),
            "max_duration_ticks", 400,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.FULL
            );

        assertEquals("place", result.request().actionKind());
        assertEquals(
            "item_use",
            result.request().arguments().get("placement_method")
        );
        assertEquals(
            "minecraft:water_bucket",
            result.request().arguments().get("source_item_id")
        );
        assertEquals("off_hand", result.request().arguments().get("hand"));
    }

    @Test
    void fullScopeAcceptsBoundedPredictedCollisionPlacement() throws Exception {
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-bound-place",
            "action_kind", "place",
            "arguments", Map.of(
                "block_id", "minecraft:water",
                "position_binding", Map.of(
                    "binding_kind", "predicted_collision_cell",
                    "horizon_ticks", 12,
                    "max_distance_blocks", 5,
                    "require_replaceable", true
                ),
                "placement_method", "item_use",
                "source_item_id", "minecraft:water_bucket",
                "hand", "main_hand",
                "cleanup_after_landing", true
            ),
            "max_duration_ticks", 400,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.FULL
            );

        assertEquals("place", result.request().actionKind());
        assertFalse(result.request().arguments().containsKey("positions"));
        assertEquals(
            true,
            result.request().arguments().get("cleanup_after_landing")
        );
        assertEquals(
            Map.of(
                "binding_kind", "predicted_collision_cell",
                "horizon_ticks", 12L,
                "max_distance_blocks", 5.0,
                "require_replaceable", true
            ),
            result.request().arguments().get("position_binding")
        );
    }

    @Test
    void fullScopeRejectsLandingCleanupOutsideWaterBucketBinding() throws Exception {
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-invalid-cleanup",
            "action_kind", "place",
            "arguments", Map.of(
                "block_id", "minecraft:water",
                "positions", List.of(Map.of("x", 1, "y", 64, "z", 2)),
                "placement_method", "item_use",
                "source_item_id", "minecraft:water_bucket",
                "hand", "main_hand",
                "cleanup_after_landing", true
            ),
            "max_duration_ticks", 400,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.FULL
            );

        assertNull(result.request());
        assertEquals(
            "player_diagnostic_inbox_landing_cleanup_invalid",
            result.failureCode()
        );
    }

    @Test
    void fullScopeRejectsAmbiguousPlacementPositionSources() throws Exception {
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-ambiguous-place",
            "action_kind", "place",
            "arguments", Map.of(
                "block_id", "minecraft:water",
                "positions", List.of(Map.of("x", 1, "y", 64, "z", 2)),
                "position_binding", Map.of(
                    "binding_kind", "predicted_collision_cell",
                    "horizon_ticks", 12,
                    "max_distance_blocks", 5,
                    "require_replaceable", true
                )
            ),
            "max_duration_ticks", 400,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.FULL
            );

        assertNull(result.request());
        assertEquals(
            "player_diagnostic_inbox_position_source_invalid",
            result.failureCode()
        );
    }

    @Test
    void movementScopeAcceptsABoundedEntityCameraTracker() throws Exception {
        Map<String, Object> arguments = Map.ofEntries(
            Map.entry("target", Map.of(
                "target_kind", "entity_type",
                "entity_type_id", "minecraft:bat",
                "selection", "nearest"
            )),
            Map.entry("aim_point", "render_center"),
            Map.entry("max_acquisition_distance", 64),
            Map.entry("max_duration_ms", 30_000),
            Map.entry("max_turn_degrees_per_tick", 20),
            Map.entry("max_angular_acceleration_degrees_per_tick_squared", 4),
            Map.entry("prediction_ticks", 2),
            Map.entry("deadband_degrees", 0.5),
            Map.entry("reacquire_ticks", 10),
            Map.entry("require_line_of_sight", false),
            Map.entry("stop_below_health", 4)
        );
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-tracker",
            "action_kind", "track_target",
            "arguments", arguments,
            "max_duration_ticks", 700,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.MOVEMENT
            );

        assertEquals("track_target", result.request().actionKind());
        assertEquals(30_000L, result.request().arguments().get("max_duration_ms"));
        assertEquals("render_center", result.request().arguments().get("aim_point"));
        assertEquals(
            "minecraft:bat",
            ((Map<?, ?>) result.request().arguments().get("target")).get("entity_type_id")
        );
    }

    @Test
    void fullScopeAcceptsAnExactHostileAttackAtExtendedAcquisitionRange() throws Exception {
        Map<String, Object> arguments = Map.ofEntries(
            Map.entry("target_ref", "target:00dd51226cf33aa465b609dc08fa100b0ae2c3bc"),
            Map.entry("target_entity_type_id", "minecraft:zombie"),
            Map.entry("target_classification", "hostile"),
            Map.entry("max_acquisition_distance", 16),
            Map.entry("require_line_of_sight", true),
            Map.entry("minimum_attack_cooldown", 0.9),
            Map.entry("max_attack_pulses", 8),
            Map.entry("max_duration_ms", 15_000),
            Map.entry("stop_below_health", 6),
            Map.entry("friendly_fire", false)
        );
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-attack",
            "action_kind", "attack",
            "arguments", arguments,
            "max_duration_ticks", 300,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.FULL
            );

        assertEquals("attack", result.request().actionKind());
        assertEquals(16.0, result.request().arguments().get("max_acquisition_distance"));
        assertEquals(
            "target:00dd51226cf33aa465b609dc08fa100b0ae2c3bc",
            result.request().arguments().get("target_ref")
        );
    }

    @Test
    void movementScopeAcceptsAnExactParticleCameraTracker() throws Exception {
        Map<String, Object> arguments = Map.ofEntries(
            Map.entry("target", Map.of(
                "target_kind", "particle_type",
                "particle_type_id", "minecraft:enchant",
                "selection", "nearest",
                "continuity", "single_instance",
                "handoff_radius", 0,
                "max_handoffs", 0
            )),
            Map.entry("aim_point", "center"),
            Map.entry("max_acquisition_distance", 16),
            Map.entry("max_duration_ms", 5_000),
            Map.entry("max_turn_degrees_per_tick", 6),
            Map.entry("max_angular_acceleration_degrees_per_tick_squared", 1),
            Map.entry("prediction_ticks", 0),
            Map.entry("deadband_degrees", 1),
            Map.entry("reacquire_ticks", 4),
            Map.entry("require_line_of_sight", true),
            Map.entry("stop_below_health", 4)
        );
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-particle-tracker",
            "action_kind", "track_target",
            "arguments", arguments,
            "max_duration_ticks", 120,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.MOVEMENT
            );

        assertEquals("track_target", result.request().actionKind());
        assertEquals(
            "minecraft:enchant",
            ((Map<?, ?>) result.request().arguments().get("target")).get("particle_type_id")
        );
        assertEquals(
            "single_instance",
            ((Map<?, ?>) result.request().arguments().get("target")).get("continuity")
        );
    }

    @Test
    void fullScopeAcceptsABoundedFluidSequence() throws Exception {
        Map<String, Object> arguments = Map.ofEntries(
            Map.entry("sequence_schema", "helix.minecraft.player_sequence.v1"),
            Map.entry("sequence_id", "sequence:inbox-test"),
            Map.entry("ruleset", "survival_tas"),
            Map.entry("execution_plane", "player_embodiment"),
            Map.entry("scheduler_engine", "native_fabric"),
            Map.entry("optimization", Map.of(
                "primary", "minimize_world_ticks",
                "record_wall_clock", true,
                "stop_on_first_verified_success", true
            )),
            Map.entry("start_node_id", "node:input"),
            Map.entry("max_total_ticks", 20),
            Map.entry("required_checkpoint_ids", List.of()),
            Map.entry("mutation_scope", Map.of(
                "world_mutation_allowed", false,
                "max_block_mutations", 0,
                "max_inventory_transfers", 0,
                "allowed_block_ids", List.of(),
                "allowed_regions", List.of(),
                "combat_allowed", false
            )),
            Map.entry("nodes", List.of(
                Map.of(
                    "node_id", "node:input",
                    "node_kind", "input_segment",
                    "earliest_tick", 0,
                    "duration_ticks", 1,
                    "controls", Map.of(
                        "forward", 1,
                        "strafe", 0,
                        "sprint", false,
                        "sneak", false,
                        "jump", "idle",
                        "use", "idle"
                    ),
                    "on_complete", "node:succeeded",
                    "on_failure", "node:failed"
                ),
                Map.of(
                    "node_id", "node:succeeded",
                    "node_kind", "terminal",
                    "terminal_outcome", "succeeded",
                    "reason_code", "inbox_sequence_complete"
                ),
                Map.of(
                    "node_id", "node:failed",
                    "node_kind", "terminal",
                    "terminal_outcome", "failed",
                    "reason_code", "inbox_sequence_failed"
                )
            ))
        );
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-sequence",
            "action_kind", "execute_sequence",
            "arguments", arguments,
            "max_duration_ticks", 120,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.FULL
            );

        assertEquals("execute_sequence", result.request().actionKind());
        assertEquals("execute_sequence", result.request().arguments().get("action_kind"));
        assertEquals("sequence:inbox-test", result.request().arguments().get("sequence_id"));
    }

    @Test
    void fullScopeAcceptsABoundedConcurrentGuardianProgram() throws Exception {
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-guardian",
            "action_kind", "execute_reactive_program",
            "arguments", reactiveProgramArguments(),
            "max_duration_ticks", 120,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.FULL
            );

        assertEquals("execute_reactive_program", result.request().actionKind());
        assertEquals(
            "execute_reactive_program",
            result.request().arguments().get("action_kind")
        );
        assertEquals(
            "program:inbox-guardian-test",
            result.request().arguments().get("program_id")
        );
    }

    @Test
    void movementScopeCannotConsumeAConcurrentGuardianProgram() throws Exception {
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-guardian-scope",
            "action_kind", "execute_reactive_program",
            "arguments", reactiveProgramArguments(),
            "max_duration_ticks", 120,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.MOVEMENT
            );

        assertNull(result.request());
        assertEquals("player_diagnostic_inbox_scope_denied", result.failureCode());
    }

    @Test
    void guardianProgramCannotSmuggleAnExtraEmbeddedActionField() throws Exception {
        Map<String, Object> program = new LinkedHashMap<>(reactiveProgramArguments());
        List<Object> lanes = new java.util.ArrayList<>((List<?>) program.get("lanes"));
        Map<String, Object> lane = new LinkedHashMap<>((Map<String, Object>) lanes.get(0));
        List<Object> nodes = new java.util.ArrayList<>((List<?>) lane.get("nodes"));
        Map<String, Object> node = new LinkedHashMap<>((Map<String, Object>) nodes.get(0));
        Map<String, Object> action = new LinkedHashMap<>((Map<String, Object>) node.get("action"));
        action.put("command", "/op player");
        node.put("action", Map.copyOf(action));
        nodes.set(0, Map.copyOf(node));
        lane.put("nodes", List.copyOf(nodes));
        lanes.set(0, Map.copyOf(lane));
        program.put("lanes", List.copyOf(lanes));
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-guardian-extra",
            "action_kind", "execute_reactive_program",
            "arguments", Map.copyOf(program),
            "max_duration_ticks", 120,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.FULL
            );

        assertNull(result.request());
        assertEquals("player_diagnostic_inbox_fields_invalid", result.failureCode());
    }

    @Test
    void rejectsNavigationThatAttemptsToSmuggleMutationPermission() throws Exception {
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-nav",
            "action_kind", "navigate_to",
            "arguments", Map.of(
                "destination", Map.of("x", 2, "y", 64, "z", 2),
                "arrival_radius", 1,
                "allow_sprint", false,
                "allow_dig", true,
                "allow_place", false,
                "engine_preference", "native_fabric"
            ),
            "max_duration_ticks", 400,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.FULL
            );

        assertNull(result.request());
        assertEquals(
            "player_diagnostic_inbox_navigation_mutation_invalid",
            result.failureCode()
        );
    }

    @Test
    void rejectsUnknownFieldsAndStaleRequests() throws Exception {
        Path unknown = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-extra",
            "action_kind", "jump",
            "arguments", Map.of("count", 1, "command", "/op player"),
            "max_duration_ticks", 40,
            "control_engine", "native_fabric"
        ));
        PlayerActionDiagnosticInbox.PollResult rejected =
            PlayerActionDiagnosticInbox.consume(
                unknown,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.FULL
            );
        assertEquals("player_diagnostic_inbox_fields_invalid", rejected.failureCode());

        Path stale = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-stale",
            "action_kind", "jump",
            "arguments", Map.of("count", 1),
            "max_duration_ticks", 40,
            "control_engine", "native_fabric"
        ));
        long now = System.currentTimeMillis();
        Files.setLastModifiedTime(
            stale,
            FileTime.fromMillis(now - PlayerActionDiagnosticInbox.MAX_AGE_MILLIS - 1)
        );
        PlayerActionDiagnosticInbox.PollResult staleResult =
            PlayerActionDiagnosticInbox.consume(
                stale,
                now,
                PlayerActionDiagnosticInbox.Scope.FULL
            );
        assertEquals("player_diagnostic_inbox_stale", staleResult.failureCode());
    }

    @Test
    void disabledScopeDoesNotClaimAStagedFile() throws Exception {
        Path inbox = write(Map.of(
            "schema", PlayerActionDiagnosticInbox.SCHEMA,
            "request_id", "direct_diagnostic_request:test-disabled",
            "action_kind", "jump",
            "arguments", Map.of("count", 1),
            "max_duration_ticks", 40,
            "control_engine", "native_fabric"
        ));

        PlayerActionDiagnosticInbox.PollResult result =
            PlayerActionDiagnosticInbox.consume(
                inbox,
                System.currentTimeMillis(),
                PlayerActionDiagnosticInbox.Scope.DISABLED
            );

        assertNull(result.request());
        assertTrue(result.failureCode().isBlank());
        assertTrue(Files.exists(inbox));
    }

    @Test
    void persistsAndRevokesAnExplicitLocalControlPreference() throws Exception {
        Path authorization = tempDir.resolve(
            PlayerActionDiagnosticInbox.AUTHORIZATION_FILE_NAME
        );

        PlayerActionDiagnosticInbox.persistScope(
            authorization,
            PlayerActionDiagnosticInbox.Scope.FULL
        );
        assertEquals(
            PlayerActionDiagnosticInbox.Scope.FULL,
            PlayerActionDiagnosticInbox.loadPersistedScope(authorization)
        );

        PlayerActionDiagnosticInbox.persistScope(
            authorization,
            PlayerActionDiagnosticInbox.Scope.DISABLED
        );
        assertFalse(Files.exists(authorization));
        assertEquals(
            PlayerActionDiagnosticInbox.Scope.DISABLED,
            PlayerActionDiagnosticInbox.loadPersistedScope(authorization)
        );
    }

    @Test
    void invalidSavedPreferenceFailsClosedAndIsRemoved() throws Exception {
        Path authorization = tempDir.resolve(
            PlayerActionDiagnosticInbox.AUTHORIZATION_FILE_NAME
        );
        Files.writeString(
            authorization,
            HelixJson.stringify(Map.of(
                "schema", PlayerActionDiagnosticInbox.AUTHORIZATION_SCHEMA,
                "scope", "server_admin"
            ))
        );

        assertEquals(
            PlayerActionDiagnosticInbox.Scope.DISABLED,
            PlayerActionDiagnosticInbox.loadPersistedScope(authorization)
        );
        assertFalse(Files.exists(authorization));
    }

    private Path write(Map<String, Object> payload) throws Exception {
        Path inbox = tempDir.resolve(PlayerActionDiagnosticInbox.FILE_NAME);
        Files.writeString(inbox, HelixJson.stringifyIncludingNulls(payload));
        return inbox;
    }

    private static Map<String, Object> reactiveProgramArguments() {
        return Map.ofEntries(
            Map.entry("program_schema", "helix.minecraft.reactive_program.v1"),
            Map.entry("program_id", "program:inbox-guardian-test"),
            Map.entry("ruleset", "survival_tas"),
            Map.entry("execution_plane", "player_embodiment"),
            Map.entry("scheduler_engine", "native_fabric_concurrent"),
            Map.entry("max_total_ticks", 40),
            Map.entry("completion_policy", Map.of(
                "mode", "all_required",
                "cancel_remaining_on_settle", true
            )),
            Map.entry("mutation_scope", Map.of(
                "world_mutation_allowed", false,
                "max_block_mutations", 0,
                "max_inventory_transfers", 0,
                "allowed_block_ids", List.of(),
                "allowed_regions", List.of(),
                "combat_allowed", false
            )),
            Map.entry("lanes", List.of(Map.of(
                "lane_id", "lane:camera",
                "lane_kind", "camera",
                "priority", 80,
                "required", true,
                "activation", "immediate",
                "resource_ceiling", List.of("camera"),
                "start_node_id", "node:look",
                "nodes", List.of(
                    Map.of(
                        "node_id", "node:look",
                        "node_kind", "action",
                        "earliest_tick", 0,
                        "timeout_ticks", 20,
                        "action", Map.of(
                            "action_kind", "look_at",
                            "target", Map.of(
                                "target_kind", "relative_rotation",
                                "yaw_delta_degrees", 10,
                                "pitch_delta_degrees", 0
                            ),
                            "max_turn_degrees_per_tick", 5
                        ),
                        "on_success", "node:succeeded",
                        "on_failure", "node:failed",
                        "on_timeout", "node:failed"
                    ),
                    Map.of(
                        "node_id", "node:succeeded",
                        "node_kind", "terminal",
                        "terminal_outcome", "succeeded",
                        "reason_code", "guardian_complete"
                    ),
                    Map.of(
                        "node_id", "node:failed",
                        "node_kind", "terminal",
                        "terminal_outcome", "failed",
                        "reason_code", "guardian_failed"
                    )
                )
            ))),
            Map.entry("races", List.of()),
            Map.entry("interrupts", List.of())
        );
    }

    private static Path processing(Path inbox) {
        return inbox.resolveSibling(inbox.getFileName() + ".processing");
    }
}
