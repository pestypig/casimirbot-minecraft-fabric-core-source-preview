package com.casimirbot.helixsensor.manifest;

import com.casimirbot.helixsensor.HelixSensorConfig;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public final class EnvironmentSourceManifestFactory {
    public static final List<String> SUPPORTED_PROBE_TYPES = List.of(
        "actor_status",
        "nearby_entities",
        "reachability",
        "line_of_sight",
        "crop_state",
        "hazard_check",
        "inventory_check",
        "local_map_summary",
        "perception_snapshot"
    );

    public static final List<String> FORBIDDEN_PROBE_TYPES = List.of(
        "move_actor",
        "use_item",
        "take_item",
        "place_block",
        "break_block",
        "attack_entity",
        "open_container"
    );

    private EnvironmentSourceManifestFactory() {}

    public static Map<String, Object> build(
        HelixSensorConfig config,
        String adapterVersion,
        String createdAt
    ) {
        return build(config, adapterVersion, createdAt, List.of());
    }

    public static Map<String, Object> build(
        HelixSensorConfig config,
        String adapterVersion,
        String createdAt,
        List<String> additionalSupportedProbeTypes
    ) {
        List<String> supportedProbeTypes = Stream.concat(
            SUPPORTED_PROBE_TYPES.stream(),
            additionalSupportedProbeTypes == null
                ? Stream.empty()
                : additionalSupportedProbeTypes.stream()
        ).distinct().toList();
        return Map.ofEntries(
            Map.entry("schema", "helix.environment_source_manifest.v1"),
            Map.entry(
                "manifest_id",
                "manifest:" + config.sourceId() + ":" + adapterVersion
            ),
            Map.entry("source_id", config.sourceId()),
            Map.entry("room_id", config.roomId()),
            Map.entry("domain", "minecraft"),
            Map.entry("domain_adapter", config.domainAdapter()),
            Map.entry("source_label", config.sourceLabel()),
            Map.entry("adapter_version", adapterVersion),
            Map.entry(
                "protocol_version",
                "helix.environment_source_manifest.v1"
            ),
            Map.entry(
                "modalities",
                List.of("environment_state", "environment_affordance")
            ),
            Map.entry(
                "supported_snapshot_sections",
                List.of(
                    "actor_state",
                    "inventory_state",
                    "object_state",
                    "local_map",
                    "focus",
                    "affordances",
                    "domain_specific"
                )
            ),
            Map.entry("supported_probe_types", supportedProbeTypes),
            Map.entry("forbidden_probe_types", FORBIDDEN_PROBE_TYPES),
            Map.entry(
                "snapshot_policy",
                Map.of(
                    "baseline_interval_ms",
                    config.snapshotIntervalTicks() * 50,
                    "burst_interval_ms",
                    config.burstIntervalTicks() * 50,
                    "send_only_changed_sections",
                    config.sendOnlyChangedSections(),
                    "include_section_hashes",
                    config.includeSectionHashes(),
                    "max_payload_bytes",
                    config.maxPayloadBytes(),
                    "raw_payload_included",
                    false,
                    "raw_nbt_included",
                    false
                )
            ),
            Map.entry(
                "sensor_scope_policy",
                Map.of(
                    "default_scope",
                    config.sensorScopePolicy().defaultScope().wireValue(),
                    "can_report_privileged_state",
                    config.sensorScopePolicy().allowPrivilegedContainerScan() ||
                    config.sensorScopePolicy().allowPrivilegedEntityScan(),
                    "privileged_state_requires_caveat",
                    true,
                    "player_memory_requires_prior_observation",
                    true
                )
            ),
            Map.entry(
                "execution_policy",
                Map.of(
                    "may_execute_live_actions",
                    false,
                    "may_perform_read_only_probes",
                    true,
                    "require_human_approval_for_execution",
                    true
                )
            ),
            Map.entry(
                "auth_policy",
                Map.of(
                    "bearer_required",
                    config.bearerToken() != null &&
                    !config.bearerToken().isBlank() &&
                    !"replace-me".equals(config.bearerToken())
                )
            ),
            Map.entry("assistant_answer", false),
            Map.entry("raw_content_included", false),
            Map.entry("context_policy", "compact_context_pack_only"),
            Map.entry("created_at", createdAt)
        );
    }
}
