package com.casimirbot.helixsensor.fabric;

import com.casimirbot.helixsensor.HelixSensorConfig;
import com.casimirbot.helixsensor.snapshot.PayloadLimiter;
import com.casimirbot.helixsensor.snapshot.SectionHasher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;

public final class FabricSnapshotBuilder {
    public record SnapshotBatch(
        String snapshotId,
        String snapshotTimestamp,
        Map<String, Object> payload
    ) {}

    private FabricSnapshotBuilder() {}

    public static SnapshotBatch build(
        MinecraftServer server,
        HelixSensorConfig config,
        long sourceTick
    ) {
        String now = Instant.now().toString();
        List<Map<String, Object>> events = new ArrayList<>();
        String latestId = null;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Map<String, Object> snapshot = playerSnapshot(
                player,
                config,
                sourceTick,
                now
            );
            latestId = String.valueOf(snapshot.get("snapshot_id"));
            events.add(worldEvent(snapshot, config));
        }
        if (events.isEmpty()) {
            Map<String, Object> snapshot = sourceSnapshot(
                config,
                sourceTick,
                now
            );
            latestId = String.valueOf(snapshot.get("snapshot_id"));
            events.add(worldEvent(snapshot, config));
        }
        return new SnapshotBatch(
            latestId,
            now,
            Map.of("events", List.copyOf(events))
        );
    }

    private static Map<String, Object> playerSnapshot(
        ServerPlayer player,
        HelixSensorConfig config,
        long sourceTick,
        String now
    ) {
        String actorLabel = player.getGameProfile().getName();
        String actorId = FabricProbeExecutor.canonicalActorId(actorLabel);
        List<Map<String, Object>> items = inventoryItems(player, config);
        Map<String, Object> actorState = Map.ofEntries(
            Map.entry("sensor_scope", "player_observable"),
            Map.entry("health", round(player.getHealth())),
            Map.entry("max_health", round(player.getMaxHealth())),
            Map.entry("food_level", player.getFoodData().getFoodLevel()),
            Map.entry(
                "saturation",
                round(player.getFoodData().getSaturationLevel())
            ),
            Map.entry(
                "position",
                Map.of(
                    "x",
                    round(player.getX()),
                    "y",
                    round(player.getY()),
                    "z",
                    round(player.getZ())
                )
            )
        );
        Map<String, Object> inventoryState = Map.of(
            "sensor_scope",
            "player_observable",
            "carried_items",
            items,
            "inventory_hash",
            SectionHasher.hash(items)
        );
        List<Map<String, Object>> nearby = nearbyEntities(player, config);
        Map<String, Object> objectState = Map.of(
            "sensor_scope",
            "sensor_observable",
            "nearby_entities",
            nearby,
            "nearby_containers",
            List.of(),
            "resources",
            List.of(),
            "hazards",
            List.of()
        );
        Map<String, Object> localMap = floorSummary(player);
        Map<String, Object> focus = Map.of(
            "target_kind",
            "none",
            "sensor_scope",
            "player_observable"
        );
        Map<String, Object> affordances = Map.of(
            "sensor_scope",
            "sensor_observable",
            "summary",
            List.of(
                "actor status observable",
                "inventory observable",
                "nearby entities observable",
                "bounded read-only probes available"
            )
        );
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("actor_state", actorState);
        sections.put("inventory_state", inventoryState);
        sections.put("object_state", objectState);
        sections.put("local_map", localMap);
        sections.put("focus", focus);
        sections.put("affordances", affordances);
        Map<String, String> hashes = sectionHashes(sections);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schema", "helix.environment_state_snapshot.v1");
        snapshot.put(
            "snapshot_id",
            "snapshot:" + config.sourceId() + ":" + player.getUUID() + ":" + sourceTick
        );
        snapshot.put("domain", "minecraft");
        snapshot.put("domain_adapter", config.domainAdapter());
        snapshot.put("room_id", config.roomId());
        snapshot.put("world_id", config.worldId());
        snapshot.put("source_id", config.sourceId());
        snapshot.put("actor_id", actorId);
        snapshot.put("stable_actor_id", player.getUUID().toString());
        snapshot.put("actor_label", actorLabel);
        snapshot.put("ts", now);
        snapshot.put("source_tick", sourceTick);
        snapshot.put(
            "coordinate_frame",
            Map.of(
                "kind",
                "world_xyz",
                "dimension",
                FabricProbeExecutor.dimension(player),
                "units",
                "blocks"
            )
        );
        snapshot.put(
            "location",
            Map.of(
                "dimension",
                FabricProbeExecutor.dimension(player),
                "x",
                player.getX(),
                "y",
                player.getY(),
                "z",
                player.getZ()
            )
        );
        snapshot.putAll(sections);
        snapshot.put("section_hashes", hashes);
        snapshot.put("changed_sections", List.copyOf(sections.keySet()));
        snapshot.put(
            "domain_specific",
            Map.of(
                "minecraft",
                Map.of(
                    "raw_nbt_included",
                    false,
                    "platform",
                    "fabric",
                    "loaded_mods",
                    loadedMods(),
                    "mechanics_state",
                    FabricMechanicsStateReader.read(player.getServer())
                )
            )
        );
        snapshot.put(
            "evidence_refs",
            List.of("minecraft:fabric:snapshot:source_tick:" + sourceTick)
        );
        snapshot.put("deterministic", true);
        snapshot.put("model_invoked", false);
        snapshot.put("assistant_answer", false);
        snapshot.put("raw_content_included", false);
        snapshot.put("raw_payload_included", false);
        snapshot.put("context_policy", "compact_context_pack_only");
        return PayloadLimiter.truncateSnapshot(
            snapshot,
            config.maxPayloadBytes()
        );
    }

    private static Map<String, Object> sourceSnapshot(
        HelixSensorConfig config,
        long sourceTick,
        String now
    ) {
        Map<String, Object> actorState = Map.of(
            "sensor_scope",
            "sensor_observable",
            "active_player_count",
            0
        );
        Map<String, Object> inventoryState = Map.of(
            "sensor_scope",
            "unknown",
            "carried_items",
            List.of(),
            "inventory_hash",
            SectionHasher.hash(List.of())
        );
        Map<String, Object> objectState = Map.of(
            "sensor_scope",
            "sensor_observable",
            "nearby_entities",
            List.of(),
            "nearby_containers",
            List.of(),
            "resources",
            List.of(),
            "hazards",
            List.of()
        );
        Map<String, Object> localMap = Map.of(
            "sensor_scope",
            "unknown",
            "cells",
            List.of()
        );
        Map<String, Object> focus = Map.of(
            "target_kind",
            "none",
            "sensor_scope",
            "unknown"
        );
        Map<String, Object> affordances = Map.of(
            "sensor_scope",
            "sensor_observable",
            "summary",
            List.of("Fabric integrated or dedicated server is active")
        );
        Map<String, Object> sections = new LinkedHashMap<>();
        sections.put("actor_state", actorState);
        sections.put("inventory_state", inventoryState);
        sections.put("object_state", objectState);
        sections.put("local_map", localMap);
        sections.put("focus", focus);
        sections.put("affordances", affordances);

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schema", "helix.environment_state_snapshot.v1");
        snapshot.put(
            "snapshot_id",
            "snapshot:" + config.sourceId() + ":server:" + sourceTick
        );
        snapshot.put("domain", "minecraft");
        snapshot.put("domain_adapter", config.domainAdapter());
        snapshot.put("room_id", config.roomId());
        snapshot.put("world_id", config.worldId());
        snapshot.put("source_id", config.sourceId());
        snapshot.put("actor_id", "minecraft:server");
        snapshot.put("actor_label", "Minecraft Fabric server");
        snapshot.put("ts", now);
        snapshot.put("source_tick", sourceTick);
        snapshot.put(
            "coordinate_frame",
            Map.of(
                "kind",
                "world_xyz",
                "dimension",
                "minecraft:overworld",
                "units",
                "blocks"
            )
        );
        snapshot.putAll(sections);
        snapshot.put("section_hashes", sectionHashes(sections));
        snapshot.put("changed_sections", List.copyOf(sections.keySet()));
        snapshot.put(
            "domain_specific",
            Map.of(
                "minecraft",
                Map.of(
                    "raw_nbt_included",
                    false,
                    "platform",
                    "fabric",
                    "loaded_mods",
                    loadedMods(),
                    "mechanics_state",
                    Map.of()
                )
            )
        );
        snapshot.put(
            "evidence_refs",
            List.of("minecraft:fabric:snapshot:source_tick:" + sourceTick)
        );
        snapshot.put("deterministic", true);
        snapshot.put("model_invoked", false);
        snapshot.put("assistant_answer", false);
        snapshot.put("raw_content_included", false);
        snapshot.put("raw_payload_included", false);
        snapshot.put("context_policy", "compact_context_pack_only");
        return PayloadLimiter.truncateSnapshot(
            snapshot,
            config.maxPayloadBytes()
        );
    }

    private static List<Map<String, Object>> inventoryItems(
        ServerPlayer player,
        HelixSensorConfig config
    ) {
        List<Map<String, Object>> items = new ArrayList<>();
        int size = Math.min(
            player.getInventory().getContainerSize(),
            config.snapshotOptions().maxInventoryStacks()
        );
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            items.add(
                Map.of(
                    "slot",
                    slot,
                    "item",
                    String.valueOf(
                        BuiltInRegistries.ITEM.getKey(stack.getItem())
                    ),
                    "item_label",
                    stack.getHoverName().getString(),
                    "count",
                    stack.getCount()
                )
            );
        }
        return List.copyOf(items);
    }

    private static List<Map<String, Object>> nearbyEntities(
        ServerPlayer player,
        HelixSensorConfig config
    ) {
        double radius = config.snapshotOptions().nearbyEntityRadius();
        return FabricProbeExecutor
            .serverLevel(player)
            .getEntities(
                player,
                player.getBoundingBox().inflate(radius, radius / 2.0d, radius),
                entity -> entity != player
            )
            .stream()
            .sorted((left, right) ->
                Double.compare(
                    left.distanceToSqr(player),
                    right.distanceToSqr(player)
                )
            )
            .limit(config.snapshotOptions().maxEntities())
            .map(entity -> entitySnapshot(entity, player))
            .toList();
    }

    private static Map<String, Object> entitySnapshot(
        Entity entity,
        ServerPlayer player
    ) {
        return Map.of(
            "entity_type",
            String.valueOf(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType())),
            "entity_label",
            entity.getName().getString(),
            "distance_blocks",
            round(Math.sqrt(entity.distanceToSqr(player))),
            "hostile",
            entity instanceof Monster,
            "scoreboard_tags",
            entity.getTags().stream().sorted().limit(16).toList()
        );
    }

    private static Map<String, Object> floorSummary(ServerPlayer player) {
        int solid = 0;
        int hazardous = 0;
        int liquid = 0;
        int sampled = 0;
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                sampled++;
                var state = FabricProbeExecutor
                    .serverLevel(player)
                    .getBlockState(player.blockPosition().offset(dx, -1, dz));
                if (!state.isAir()) solid++;
                if (!state.getFluidState().isEmpty()) liquid++;
                if (FabricProbeExecutor.hazardType(state) != null) hazardous++;
            }
        }
        return Map.of(
            "sensor_scope",
            "sensor_observable",
            "sampled_floor_blocks",
            sampled,
            "solid_floor_blocks",
            solid,
            "open_floor_blocks",
            sampled - solid,
            "hazardous_floor_blocks",
            hazardous,
            "liquid_floor_blocks",
            liquid
        );
    }

    private static Map<String, String> sectionHashes(
        Map<String, Object> sections
    ) {
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : sections.entrySet()) {
            hashes.put(entry.getKey(), SectionHasher.hash(entry.getValue()));
        }
        return hashes;
    }

    private static List<Map<String, String>> loadedMods() {
        return FabricLoader
            .getInstance()
            .getAllMods()
            .stream()
            .sorted((left, right) ->
                left
                    .getMetadata()
                    .getId()
                    .compareTo(right.getMetadata().getId())
            )
            .limit(256)
            .map(container ->
                Map.of(
                    "mod_id",
                    container.getMetadata().getId(),
                    "version",
                    container.getMetadata().getVersion().getFriendlyString()
                )
            )
            .toList();
    }

    private static Map<String, Object> worldEvent(
        Map<String, Object> snapshot,
        HelixSensorConfig config
    ) {
        return Map.ofEntries(
            Map.entry("schema", "helix.world_event.v1"),
            Map.entry("world_id", config.worldId()),
            Map.entry("room_id", config.roomId()),
            Map.entry("source_id", config.sourceId()),
            Map.entry("actor_id", snapshot.get("actor_id")),
            Map.entry("actor_label", snapshot.get("actor_label")),
            Map.entry("ts", snapshot.get("ts")),
            Map.entry("event_type", "environment_state_snapshot"),
            Map.entry("evidence_refs", snapshot.get("evidence_refs")),
            Map.entry(
                "meta",
                Map.of(
                    "snapshot_schema",
                    "helix.environment_state_snapshot.v1",
                    "domain",
                    "minecraft",
                    "domain_adapter",
                    config.domainAdapter(),
                    "snapshot",
                    snapshot
                )
            )
        );
    }

    private static double round(double value) {
        return Math.round(value * 100.0d) / 100.0d;
    }
}
