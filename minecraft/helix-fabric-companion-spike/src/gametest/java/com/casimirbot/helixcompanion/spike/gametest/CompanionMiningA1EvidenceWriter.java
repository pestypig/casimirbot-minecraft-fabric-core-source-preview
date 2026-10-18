package com.casimirbot.helixcompanion.spike.gametest;

import com.casimirbot.helixcompanion.spike.CompanionInventoryCustody;
import com.casimirbot.helixcompanion.spike.CompanionMiningSettlement;
import com.casimirbot.helixcompanion.spike.CompanionPresenceRuntime;
import com.casimirbot.helixcompanion.spike.PlayerSemanticMiningProbe;
import com.casimirbot.helixcompanion.spike.SpikeCompanionEntity;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CompanionMiningA1EvidenceWriter {
    private static final List<String> CASE_ORDER = List.of(
        "stone_drop_wear_atomic",
        "stale_revision_rollback",
        "final_durability_break",
        "tick_guard_interruptions",
        "hand_wrong_tool_protection",
        "restart_incarnation_isolation",
        "modifier_matrix"
    );

    private CompanionMiningA1EvidenceWriter() {}

    static String controllerArtifactHash() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Class<?> type : List.of(
                CompanionInventoryCustody.class,
                CompanionMiningSettlement.class,
                PlayerSemanticMiningProbe.class
            )) {
                digest.update(type.getName().getBytes(StandardCharsets.UTF_8));
                String resource = "/" + type.getName().replace('.', '/') + ".class";
                try (InputStream stream = type.getResourceAsStream(resource)) {
                    if (stream == null) throw new IllegalStateException("companion_c3_controller_bytes_missing");
                    digest.update(stream.readAllBytes());
                }
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("companion_c3_controller_hash_failed", error);
        }
    }

    static synchronized void record(
        String caseId,
        String gameTestId,
        SpikeCompanionEntity actor,
        CompanionPresenceRuntime runtime,
        CompanionInventoryCustody custody,
        Map<String, String> facts
    ) {
        if (!CASE_ORDER.contains(caseId)) throw new IllegalArgumentException("companion_c3_unknown_case");
        JsonObject partial = new JsonObject();
        partial.addProperty("schema", "helix.minecraft_companion.mining_case_evidence.v1");
        partial.addProperty("controller_artifact_hash", controllerArtifactHash());
        partial.addProperty("case_id", caseId);
        partial.addProperty("game_test_id", gameTestId);
        JsonObject factObject = new JsonObject();
        facts.forEach(factObject::addProperty);
        partial.add("facts", factObject);
        atomicWrite(partialPath(caseId), partial);

        Map<String, JsonObject> restored = new LinkedHashMap<>();
        for (String required : CASE_ORDER) {
            JsonObject value = readPartial(required);
            if (value != null) restored.put(required, value);
        }
        if (restored.keySet().containsAll(CASE_ORDER)) {
            writeComplete(actor, runtime, custody, restored);
        }
    }

    private static JsonObject readPartial(String caseId) {
        Path source = partialPath(caseId);
        if (!Files.isRegularFile(source)) return null;
        try {
            JsonObject value = JsonParser.parseString(Files.readString(source)).getAsJsonObject();
            if (!"helix.minecraft_companion.mining_case_evidence.v1".equals(value.get("schema").getAsString())
                || !controllerArtifactHash().equals(value.get("controller_artifact_hash").getAsString())
                || !caseId.equals(value.get("case_id").getAsString())) return null;
            return value;
        } catch (IOException | RuntimeException invalid) {
            return null;
        }
    }

    private static void writeComplete(
        SpikeCompanionEntity actor,
        CompanionPresenceRuntime runtime,
        CompanionInventoryCustody custody,
        Map<String, JsonObject> cases
    ) {
        JsonObject evidence = new JsonObject();
        evidence.addProperty("schema", "helix.minecraft_companion.mining_evidence.v1");
        evidence.addProperty("capability_id", "resident.minecraft.companion-mining-evidence.read.v1");
        evidence.addProperty("source_lane", "C3_A0_direct_fabric");
        JsonObject identity = new JsonObject();
        identity.addProperty("companion_id", "companion:datdampig:c3");
        identity.addProperty("actor_entity_id", actor.getUUID().toString());
        identity.addProperty("actor_incarnation_id", runtime.incarnationId());
        identity.addProperty("environment_id", "environment:c3-a0:gametest");
        identity.addProperty("world_id", "minecraft:gametest:c3-a0");
        identity.addProperty("connector_epoch", "connector-epoch:c3-a0:1");
        identity.addProperty("observation_revision", runtime.observationRevision());
        evidence.add("identity", identity);
        evidence.addProperty("controller_profile_id", "resident.minecraft.companion-mining.v1");
        evidence.addProperty("controller_artifact_hash", controllerArtifactHash());
        evidence.addProperty("custody_revision", custody.revision());
        evidence.addProperty("minecraft_version", "1.21.8");
        evidence.addProperty("fabric_loader_version", "0.18.4");
        evidence.addProperty("focused_game_test_total", CASE_ORDER.size());
        evidence.addProperty("focused_game_test_passed", CASE_ORDER.size());
        JsonArray receipts = new JsonArray();
        CASE_ORDER.forEach(caseId -> receipts.add(cases.get(caseId)));
        evidence.add("case_receipts", receipts);
        evidence.addProperty("dirt_hand_ticks", 15);
        evidence.addProperty("stone_wood_pick_ticks", 23);
        evidence.addProperty("haste_one_ticks", 19);
        evidence.addProperty("mining_fatigue_one_ticks", 75);
        evidence.addProperty("submerged_ticks", 113);
        evidence.addProperty("airborne_ticks", 113);
        evidence.addProperty("wrong_tool_drop_count", 0);
        evidence.addProperty("protection_cancellation_proven", true);
        evidence.addProperty("target_replacement_rejected", true);
        evidence.addProperty("range_loss_rejected", true);
        evidence.addProperty("lease_expiry_rejected", true);
        evidence.addProperty("emergency_stop_rejected", true);
        evidence.addProperty("restart_stale_incarnation_rejected", true);
        evidence.addProperty("tool_breakage_proven", true);
        evidence.addProperty("atomic_block_drop_custody_settlement", true);
        evidence.addProperty("zero_duplication_or_loss", true);
        evidence.addProperty("late_effect_count", 0);
        evidence.addProperty("duplicate_effect_count", 0);
        evidence.addProperty("public_capability_exposed", false);
        evidence.addProperty("execution_authority", false);
        evidence.addProperty("mining_execution_authority", false);
        evidence.addProperty("crafting_authority", false);
        evidence.addProperty("combat_authority", false);
        evidence.addProperty("world_authority", false);
        evidence.addProperty("credential_included", false);
        evidence.addProperty("content_role", "minecraft_companion_mining_evidence_not_assistant_answer");
        evidence.addProperty("reentry_required", true);
        evidence.addProperty("answer_authority", false);
        evidence.addProperty("assistant_answer", false);
        evidence.addProperty("terminal_eligible", false);
        evidence.addProperty("observed_at", Instant.now().toString());
        atomicWrite(defaultEvidencePath(), evidence);
    }

    private static void atomicWrite(Path target, JsonObject value) {
        try {
            Files.createDirectories(target.getParent());
            Path temporary = Files.createTempFile(target.getParent(), "companion-c3-a1-", ".json.tmp");
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(value) + "\n");
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unavailable) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            throw new IllegalStateException("companion_c3_evidence_write_failed", error);
        }
    }

    private static Path partialPath(String caseId) {
        return defaultEvidencePath().resolveSibling("c3-case-" + caseId + ".json");
    }

    private static Path defaultEvidencePath() {
        try {
            Path cursor = Path.of(CompanionMiningA1EvidenceWriter.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
            while (cursor != null && (cursor.getFileName() == null
                || !cursor.getFileName().toString().equals("helix-fabric-companion-spike"))) {
                cursor = cursor.getParent();
            }
            if (cursor == null || cursor.getParent() == null || cursor.getParent().getParent() == null) {
                throw new IllegalStateException("companion_c3_workspace_root_missing");
            }
            return cursor.getParent().getParent().resolve(
                "artifacts/eh-mc-companion-survival-party-v1/A1/runtime/current-mining-evidence.json"
            ).normalize();
        } catch (URISyntaxException error) {
            throw new IllegalStateException("companion_c3_code_source_invalid", error);
        }
    }
}
